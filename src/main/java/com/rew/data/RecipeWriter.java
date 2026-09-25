package com.rew.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.rew.RewMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;

import com.mojang.serialization.JsonOps;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link RecipeSnapshot} 还原成 GT 能吃的 {@code FinishedRecipe}。
 *
 * <p>核心思路：**不做任何有损转换**。快照里每条内容都存了 GT 自己
 * {@code IContentSerializer#toJsonContent} 的原样输出（{@link ContentRef#raw}），
 * 这里用 {@code fromJsonContent} 原路解析回去，标签、NBT、多选材料、概率全部无损。
 * 只有在 {@code raw} 缺失（手写快照 / 旧缓存）时才退化成按 id+数量重建。
 *
 * <p>产出前用 {@code GTRecipeSerializer.CODEC} 编码一次，保证 JSON 结构一定符合
 * GT 当前的解析器预期 —— 这比手拼 JSON 稳得多，GT 改字段时我们自动跟随。
 */
public final class RecipeWriter {

    private RecipeWriter() {}

    /** @return 可直接交给 {@code GTDynamicDataPack} 的配方；类型不存在时返回 null。 */
    @Nullable
    public static FinishedRecipe toFinished(RecipeSnapshot snap) {
        ResourceLocation typeId = ResourceLocation.tryParse(snap.typeId);
        if (typeId == null) return null;
        GTRecipeType type = GTRegistries.RECIPE_TYPES.get(typeId);
        if (type == null) {
            RewMod.LOGGER.warn("[{}] 未知配方类型 {}，跳过 {}", RewMod.MOD_ID, snap.typeId, snap.id);
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(snap.id);
        if (id == null) return null;

        GTRecipe recipe = build(type, id, snap);
        if (recipe == null) return null;

        JsonObject json;
        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, GTRegistries.builtinRegistry());
            JsonElement encoded = GTRecipeSerializer.CODEC.encodeStart(ops, recipe)
                    .getOrThrow(false, msg -> RewMod.LOGGER.error("[{}] 配方编码失败: {}", RewMod.MOD_ID, msg));
            json = encoded.getAsJsonObject();
        } catch (Throwable t) {
            RewMod.LOGGER.error("[{}] 配方 {} 编码失败", RewMod.MOD_ID, snap.id, t);
            return null;
        }

        // 用该配方类型自己注册的那个 serializer 实例 —— Forge 的 FinishedRecipe#serializeRecipe
        // 会用 ForgeRegistries.RECIPE_SERIALIZERS 反查 "type" 字段，静态的 SERIALIZER 注册名是
        // gtceu:machine，只有按类型取到的实例才能反查出正确的 gtceu:<type>。
        RecipeSerializer<?> serializer = BuiltInRegistries.RECIPE_SERIALIZER.get(typeId);

        return new FinishedRecipe() {

            @Override
            public void serializeRecipeData(JsonObject pJson) {
                for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                    pJson.add(e.getKey(), e.getValue());
                }
            }

            @Override
            public ResourceLocation getId() {
                return id;
            }

            @Override
            public RecipeSerializer<?> getType() {
                return serializer == null ? GTRecipeSerializer.SERIALIZER : serializer;
            }

            @Nullable
            @Override
            public JsonObject serializeAdvancement() {
                return null;
            }

            @Nullable
            @Override
            public ResourceLocation getAdvancementId() {
                return null;
            }
        };
    }

    /** 由快照构造 GTRecipe。 */
    @Nullable
    public static GTRecipe build(GTRecipeType type, ResourceLocation id, RecipeSnapshot snap) {
        try {
            Map<RecipeCapability<?>, List<Content>> inputs = new IdentityHashMap<>();
            Map<RecipeCapability<?>, List<Content>> outputs = new IdentityHashMap<>();
            Map<RecipeCapability<?>, List<Content>> tickInputs = new IdentityHashMap<>();
            Map<RecipeCapability<?>, List<Content>> tickOutputs = new IdentityHashMap<>();

            put(inputs, ItemRecipeCapability.CAP, contentsOf(ItemRecipeCapability.CAP, snap.itemInputs));
            put(inputs, FluidRecipeCapability.CAP, contentsOf(FluidRecipeCapability.CAP, snap.fluidInputs));
            put(outputs, ItemRecipeCapability.CAP, contentsOf(ItemRecipeCapability.CAP, snap.itemOutputs));
            put(outputs, FluidRecipeCapability.CAP, contentsOf(FluidRecipeCapability.CAP, snap.fluidOutputs));

            // GT 的 EU 走 tickInputs/tickOutputs（见 GTRecipe#calculateEUt 的实现）。
            if (snap.inputEUt > 0L) {
                long amp = Math.max(1L, snap.inputAmperage);
                tickInputs.put(EURecipeCapability.CAP, List.of(
                        new Content(new EnergyStack(snap.inputEUt, amp), 10000, 10000, 0)));
            }
            if (snap.outputEUt > 0L) {
                long amp = Math.max(1L, snap.outputAmperage);
                tickOutputs.put(EURecipeCapability.CAP, List.of(
                        new Content(new EnergyStack(snap.outputEUt, amp), 10000, 10000, 0)));
            }

            List<RecipeCondition<?>> conditions = new ArrayList<>();
            for (ConditionRef ref : snap.conditions) {
                RecipeCondition<?> condition = conditionOf(ref);
                if (condition != null) conditions.add(condition);
            }

            return new GTRecipe(type, id,
                    inputs, outputs, tickInputs, tickOutputs,
                    new IdentityHashMap<>(), new IdentityHashMap<>(),
                    new IdentityHashMap<>(), new IdentityHashMap<>(),
                    conditions, List.of(), new CompoundTag(), snap.duration,
                    type.getCategory(), -1);
        } catch (Throwable t) {
            RewMod.LOGGER.error("[{}] 配方 {} 构造失败", RewMod.MOD_ID, snap.id, t);
            return null;
        }
    }

    private static void put(Map<RecipeCapability<?>, List<Content>> map,
                            RecipeCapability<?> cap, List<Content> contents) {
        if (contents != null && !contents.isEmpty()) map.put(cap, contents);
    }

    private static List<Content> contentsOf(RecipeCapability<?> cap, List<ContentRef> refs) {
        List<Content> out = new ArrayList<>(refs.size());
        for (ContentRef ref : refs) {
            Content c = contentOf(cap, ref);
            if (c != null) out.add(c);
        }
        return out;
    }

    @Nullable
    private static Content contentOf(RecipeCapability<?> cap, ContentRef ref) {
        int chance = ref.chance <= 0 ? 10000 : ref.chance;
        int maxChance = ref.maxChance <= 0 ? 10000 : ref.maxChance;

        // 编程电路优先于 raw 回放：作者在编辑器里改过配置号后，raw 里还是旧号，
        // 必须以 circuitConfig 为准重新构造，否则改了等于没改。
        if (ref.circuit && cap == ItemRecipeCapability.CAP) {
            try {
                int cfg = Math.max(0, Math.min(32, ref.circuitConfig));
                return new Content(IntCircuitIngredient.of(cfg), chance, maxChance, 0);
            } catch (Throwable t) {
                RewMod.LOGGER.warn("[{}] 编程电路 #{} 重建失败: {}",
                        RewMod.MOD_ID, ref.circuitConfig, t.toString());
                return null;
            }
        }

        // 其次：原样 JSON 回放，无损。
        if (ref.raw != null && !ref.raw.isBlank()) {
            try {
                JsonElement el = JsonParser.parseString(ref.raw);
                return cap.serializer.fromJsonContent(el);
            } catch (Throwable t) {
                RewMod.LOGGER.warn("[{}] 内容 {} 原始 JSON 解析失败，退化为按 ID 重建: {}",
                        RewMod.MOD_ID, ref.id, t.toString());
            }
        }
        // 兜底：只有 id + 数量可用（手写草稿 / 极旧缓存）。
        try {
            if (cap == ItemRecipeCapability.CAP) {
                ResourceLocation rl = ResourceLocation.tryParse(ref.id);
                if (rl == null) return null;
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == null) return null;
                int count = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ref.amount));
                return new Content(Ingredient.of(new ItemStack(item, count)), chance, maxChance, 0);
            }
            if (cap == FluidRecipeCapability.CAP) {
                ResourceLocation rl = ResourceLocation.tryParse(ref.id);
                if (rl == null) return null;
                var fluid = BuiltInRegistries.FLUID.get(rl);
                if (fluid == null) return null;
                int amount = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ref.amount));
                return new Content(FluidIngredient.of(fluid, amount), chance, maxChance, 0);
            }
        } catch (Throwable t) {
            RewMod.LOGGER.warn("[{}] 内容 {} 重建失败: {}", RewMod.MOD_ID, ref.id, t.toString());
        }
        return null;
    }

    @Nullable
    private static RecipeCondition<?> conditionOf(ConditionRef ref) {
        if (ref.json == null || ref.json.isBlank()) return null;
        try {
            return RecipeCondition.deserialize(JsonParser.parseString(ref.json).getAsJsonObject());
        } catch (Throwable t) {
            RewMod.LOGGER.warn("[{}] 条件 {} 还原失败，已丢弃: {}",
                    RewMod.MOD_ID, ref.type, t.toString());
            return null;
        }
    }
}
