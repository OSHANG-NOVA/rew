package com.rew.data;

import com.google.gson.JsonElement;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.category.GTRecipeCategory;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

import com.rew.RewMod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 把 GT 运行时的配方库一次性拍成扁平快照。
 *
 * <p>性能取向（这是本工具唯一的重活，必须一次做对）：
 * <ul>
 * <li>只遍历 {@code GTRegistries.RECIPE_TYPES} 一次，每个类型只走一遍它的 {@code categoryMap}；</li>
 * <li>配方计数在扫描过程中就地累加，不做二次遍历（早期版本的 O(类型数 × 配方数) 是纯浪费）；</li>
 * <li>每条配方的 {@link ContentRef#raw} 是唯一的分配热点，用 try/catch 兜住个别 serializer 的异常，
 * 单条失败不影响整轮扫描；</li>
 * <li>整个过程只读，绝不触碰 {@code RecipeDB}，因此不会干扰机器 tick。</li>
 * </ul>
 *
 * <p>被本工具禁用的配方此刻已经不在 {@code categoryMap} 里（GT 在加载阶段就丢掉了它们），
 * 所以调用方要用旧快照做回填，见 {@link #mergeDisabled}。
 */
public final class RecipeScanner {

    private RecipeScanner() {}

    /** GT 编程电路的物品 ID。33 种变体共用同一个物品，靠 NBT 里的配置号区分。 */
    private static final String CIRCUIT_ITEM_ID = "gtceu:programmed_circuit";

    /** 扫描进度回调，供 UI 显示进度用。 */
    public interface Progress {

        void onType(String typeId, int index, int total, int recipesSoFar);
    }

    public static SnapshotStore.SnapshotFile scan(Progress progress) {
        long start = System.currentTimeMillis();

        SnapshotStore.SnapshotFile file = new SnapshotStore.SnapshotFile();
        file.modVersion = RewMod.modVersion();
        file.gtVersion = RewMod.gtVersion();
        file.mcVersion = "1.20.1";

        List<GTRecipeType> types = new ArrayList<>();
        for (GTRecipeType t : GTRegistries.RECIPE_TYPES) {
            types.add(t);
        }
        // 稳定顺序：缓存 diff 与列表显示都依赖它，避免每次启动顺序抖动。
        types.sort(Comparator.comparing(t -> t.registryName.toString()));

        int done = 0;
        for (GTRecipeType type : types) {
            done++;
            TypeInfo info = scanType(type);

            // 就地计数，避免再扫一遍全量配方表。
            Map<String, Integer> perType = new HashMap<>();
            for (GTRecipeCategory category : type.getCategories()) {
                for (GTRecipe recipe : type.getRecipesInCategory(category)) {
                    RecipeSnapshot snap = scanRecipe(recipe, info);
                    if (snap != null) {
                        file.recipes.add(snap);
                        perType.merge(snap.typeId, 1, Integer::sum);
                    }
                }
            }
            info.recipeCount = perType.getOrDefault(info.id, 0);
            file.types.add(info);

            if (progress != null) {
                progress.onType(info.id, done, types.size(), file.recipes.size());
            }
        }

        file.timestamp = System.currentTimeMillis();
        RewMod.LOGGER.info("[{}] 配方扫描完成：{} 个类型 / {} 条配方，耗时 {} ms",
                RewMod.MOD_ID, types.size(), file.recipes.size(), System.currentTimeMillis() - start);
        return file;
    }

    /** 无进度回调的便捷入口。 */
    public static SnapshotStore.SnapshotFile scan() {
        return scan(null);
    }

    private static TypeInfo scanType(GTRecipeType type) {
        TypeInfo info = new TypeInfo();
        info.id = type.registryName.toString();
        info.group = type.group == null ? "" : type.group;
        info.multiblock = "multiblock".equalsIgnoreCase(info.group);

        info.maxItemInputs = type.getMaxInputs(ItemRecipeCapability.CAP);
        info.maxItemOutputs = type.getMaxOutputs(ItemRecipeCapability.CAP);
        info.maxFluidInputs = type.getMaxInputs(FluidRecipeCapability.CAP);
        info.maxFluidOutputs = type.getMaxOutputs(FluidRecipeCapability.CAP);
        info.hasEUIn = type.getMaxInputs(EURecipeCapability.CAP) > 0;
        info.hasEUOut = type.getMaxOutputs(EURecipeCapability.CAP) > 0;

        // 图标：优先取 GT 自己给该类型设的图标物品（JEI 类别图标用的就是同一来源）。
        // 取不到时留空，UI 端回退到 barrier，与 GTRecipeCategory#getIcon 的兜底一致。
        try {
            Supplier<ItemStack> iconSupplier = type.getIconSupplier();
            if (iconSupplier != null) {
                ItemStack stack = iconSupplier.get();
                if (stack != null && !stack.isEmpty()) {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (key != null) info.iconItem = key.toString();
                }
            }
        } catch (Throwable ignored) {
            // 个别类型（如已被其他模组改写过的）取图标可能抛异常，不该拖垮整轮扫描。
        }
        return info;
    }

    private static RecipeSnapshot scanRecipe(GTRecipe recipe, TypeInfo info) {
        try {
            RecipeSnapshot snap = new RecipeSnapshot();
            snap.id = recipe.id == null ? "" : recipe.id.toString();
            snap.typeId = info.id;
            snap.categoryId = recipe.recipeCategory == null ? info.id
                    : recipe.recipeCategory.registryKey.toString();
            snap.duration = recipe.duration;

            EnergyStack in = recipe.getInputEUt();
            if (in != null && !in.isEmpty()) {
                snap.inputEUt = in.voltage();
                snap.inputAmperage = in.amperage();
            }
            EnergyStack out = recipe.getOutputEUt();
            if (out != null && !out.isEmpty()) {
                snap.outputEUt = out.voltage();
                snap.outputAmperage = out.amperage();
            }

            collect(recipe.inputs, snap.itemInputs, snap.fluidInputs);
            collect(recipe.outputs, snap.itemOutputs, snap.fluidOutputs);

            if (recipe.conditions != null) {
                for (RecipeCondition<?> condition : recipe.conditions) {
                    ConditionRef ref = toConditionRef(condition);
                    if (ref != null) snap.conditions.add(ref);
                }
            }
            return snap;
        } catch (Throwable t) {
            RewMod.LOGGER.warn("[{}] 跳过无法快照的配方 {}: {}",
                    RewMod.MOD_ID, recipe.id, t.toString());
            return null;
        }
    }

    private static void collect(Map<RecipeCapability<?>, List<Content>> source,
                                List<ContentRef> items, List<ContentRef> fluids) {
        if (source == null || source.isEmpty()) return;
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : source.entrySet()) {
            RecipeCapability<?> cap = entry.getKey();
            boolean isItem = cap == ItemRecipeCapability.CAP;
            boolean isFluid = cap == FluidRecipeCapability.CAP;
            // 只把物品/流体放进三表模型；EU 单独用 inputEUt/outputEUt 表达。
            if (!isItem && !isFluid) continue;
            List<Content> contents = entry.getValue();
            if (contents == null) continue;
            for (Content content : contents) {
                ContentRef ref = toContentRef(cap, content);
                if (ref == null) continue;
                if (isItem) items.add(ref);
                else fluids.add(ref);
            }
        }
    }

    private static ContentRef toContentRef(RecipeCapability<?> cap, Content content) {
        ContentRef ref = new ContentRef();
        ref.capability = cap.name == null ? "" : cap.name;
        ref.chance = content.chance;
        ref.maxChance = content.maxChance;

        // 无损原始 JSON：重建配方时直接喂回 GT 的 serializer，标签/NBT/多选材料/概率全部原样还原。
        try {
            JsonElement json = cap.serializer.toJsonContent(content);
            ref.raw = json == null ? "" : json.toString();
        } catch (Throwable ignored) {
            ref.raw = "";
        }

        Object inner = content.getContent();
        if (inner instanceof Ingredient ingredient) {
            // 编程电路可能被包在 SizedIngredient / IntProviderIngredient 里
            // （GT 自己的 ItemRecipeCapability 就是这么判断的），所以必须先剥壳再认。
            // SizedIngredient.getInner 是 GT 公开的静态解包方法，逐层剥到最里面那个。
            Ingredient bare = unwrap(ingredient);
            if (bare instanceof IntCircuitIngredient circuit) {
                // 编程电路是 GT 的特殊原料：同一个物品、靠 NBT 里的配置号区分 0~32。
                // 它不该混在普通物品表里，而是单独走「电路配置」这一栏。
                // 配置号字段本身是私有的，但 getItems() 给出的栈带着 NBT，
                // 用 GT 公开的 IntCircuitBehaviour 就能把号读出来。
                ItemStack[] stacks = circuit.getItems();
                ItemStack stack = stacks.length > 0 ? stacks[0] : ItemStack.EMPTY;
                ref.circuit = true;
                ref.id = CIRCUIT_ITEM_ID;
                ref.amount = 1L;
                ref.circuitConfig = readCircuitConfig(stack);
                ref.iconStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
                return ref;
            }

            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length > 0 && !stacks[0].isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stacks[0].getItem());
                ref.id = key == null ? "" : key.toString();
                ref.amount = stacks[0].getCount();
                // 留一份带 NBT 的原栈，药水这类物品才能画出正确贴图和名字。
                ref.iconStack = stacks[0].copy();
                // 兜底：附属模组若用普通 Ingredient 表达电路，靠物品 ID 认出来。
                if (isCircuitStack(stacks[0])) {
                    ref.circuit = true;
                    ref.circuitConfig = readCircuitConfig(stacks[0]);
                }
            }
            // 多个候选说明这是 tag 或复合材料，UI 上给出提示
            ref.tag = stacks.length > 1;
        } else if (inner instanceof FluidIngredient fluidIngredient) {
            FluidStack[] stacks = fluidIngredient.getStacks();
            if (stacks.length > 0 && !stacks[0].isEmpty()) {
                ResourceLocation key = BuiltInRegistries.FLUID.getKey(stacks[0].getFluid());
                ref.id = key == null ? "" : key.toString();
                ref.amount = stacks[0].getAmount();
            }
            ref.tag = stacks.length > 1;
        } else if (inner instanceof EnergyStack energy) {
            ref.id = "eu";
            ref.amount = energy.getTotalEU();
        } else if (inner != null) {
            ref.id = inner.toString();
        }
        return ref;
    }

    /**
     * 剥掉原料的外壳，取到最里面那个真正的 Ingredient。
     *
     * <p>直接委托 GT 自己的 {@code SizedIngredient.getInner}：它对
     * {@code SizedIngredient} 与 {@code IntProviderIngredient} 逐层递归，
     * 语义与 GT 内部判断电路时用的完全一致，我们不必自己维护这套解包规则。
     */
    private static Ingredient unwrap(Ingredient ingredient) {
        if (ingredient == null) return null;
        try {
            return SizedIngredient.getInner(ingredient);
        } catch (Throwable t) {
            return ingredient;
        }
    }

    /** 这个栈是不是 GT 的编程电路。 */
    private static boolean isCircuitStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            return IntCircuitBehaviour.isIntegratedCircuit(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读编程电路的配置号；读不到（没 NBT）按 0 处理，与 GT 自身行为一致。 */
    private static int readCircuitConfig(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            return IntCircuitBehaviour.getCircuitConfiguration(stack);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static ConditionRef toConditionRef(RecipeCondition<?> condition) {
        ConditionRef ref = new ConditionRef();
        try {
            String key = GTRegistries.RECIPE_CONDITIONS.getKey(condition.getType());
            ref.type = key == null ? String.valueOf(condition.getType()) : key;
        } catch (Throwable ignored) {
            ref.type = String.valueOf(condition.getType());
        }
        try {
            ref.json = condition.serialize().toString();
        } catch (Throwable ignored) {
            ref.json = "";
        }
        try {
            ref.reverse = condition.isReverse();
        } catch (Throwable ignored) {
            ref.reverse = false;
        }
        return ref;
    }

    /**
     * 把上一份快照里「已被禁用」的配方补回新快照。
     *
     * <p>必要性：被禁用的配方在 GT 加载阶段就被 {@code RECIPE_FILTERS} 丢弃了，
     * 运行时已经读不到，但工具必须仍能看到并允许作者重新启用它们。
     */
    public static void mergeDisabled(SnapshotStore.SnapshotFile fresh,
                                     SnapshotStore.SnapshotFile previous,
                                     Set<String> disabledIds) {
        if (previous == null || disabledIds == null || disabledIds.isEmpty()) return;
        Set<String> present = new HashSet<>();
        for (RecipeSnapshot s : fresh.recipes) {
            present.add(s.id);
        }
        for (RecipeSnapshot old : previous.recipes) {
            if (disabledIds.contains(old.id) && !present.contains(old.id)) {
                RecipeSnapshot copy = old.copy();
                copy.disabled = true;
                fresh.recipes.add(copy);
            }
        }
    }
}
