package com.rew.i18n;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.rew.data.ContentRef;
import com.rew.data.RecipeSnapshot;

/**
 * 列表行图标的统一取法。
 *
 * <p>三类图标分开取，不互相替代：
 * <ul>
 * <li>物品 —— 直接取物品自己的贴图；</li>
 * <li>流体 —— 取流体自己的贴图（GT 注册流体时给的那张），见 {@link #fluidOf}；</li>
 * <li>流体桶 —— 桶是物品，走物品贴图，不画成流体。</li>
 * </ul>
 *
 * <p>结果按 ID 缓存。贴图在一次游戏会话内不变，缓存可以一直用到退出。
 */
public final class RewIcons {

    private RewIcons() {}

    private static final java.util.Map<String, ItemStack> ITEMS = new java.util.HashMap<>();
    private static final java.util.Map<String, FluidStack> FLUIDS = new java.util.HashMap<>();
    /** 编程电路：配置号 → 带 NBT 的栈。33 个，数量固定，直接缓存。 */
    private static final java.util.Map<Integer, ItemStack> CIRCUITS = new java.util.HashMap<>();
    /** 配方类型 → 代表机器的物品。空栈表示该类型没有对应机器。 */
    private static java.util.Map<String, ItemStack> MACHINE_BY_TYPE = null;

    /** 物品贴图。查不到返回空栈。 */
    public static ItemStack itemOf(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        ItemStack cached = ITEMS.get(id);
        if (cached != null) return cached;
        ItemStack resolved = resolveItem(id);
        ITEMS.put(id, resolved);
        return resolved;
    }

    /**
     * 带 NBT 的物品栈：{@code itemWithNbt("minecraft:enchanted_book", "{StoredEnchantments:[...]}")}。
     *
     * <p>用途是让「从磁盘读回的草稿」也能画出正确的图标与名字。草稿里存的是 SNBT 字符串
     * （{@link com.rew.data.ContentRef#nbt}），而 {@code iconStack} 是 transient 的，
     * 重新载入后只剩 ID；不重建就会把附魔书画成一本普通书。
     *
     * <p>结果不缓存：NBT 组合是无限的，缓存只会白白涨内存。调用点都在列表渲染，
     * 量级很小。
     */
    public static ItemStack itemWithNbt(String id, String snbt) {
        if (id == null || id.isEmpty() || snbt == null || snbt.isBlank()) return ItemStack.EMPTY;
        try {
            ItemStack base = itemOf(id);
            if (base.isEmpty()) return ItemStack.EMPTY;
            ItemStack stack = base.copy();
            stack.setTag(net.minecraft.nbt.TagParser.parseTag(snbt));
            return stack;
        } catch (Throwable t) {
            // SNBT 解析失败（格式过旧 / 被手工改坏）时退回无 NBT 的物品，不影响其它功能。
            return ItemStack.EMPTY;
        }
    }

    /** 流体本身，供列表画它自己的贴图。查不到返回空栈。 */
    public static FluidStack fluidOf(String id) {
        if (id == null || id.isEmpty()) return FluidStack.EMPTY;
        FluidStack cached = FLUIDS.get(id);
        if (cached != null) return cached;
        FluidStack resolved = resolveFluid(id);
        FLUIDS.put(id, resolved);
        return resolved;
    }

    /**
     * 配方类型的代表机器图标。
     *
     * <p>找出所有能做这个配方类型的机器，按机器 ID 字母序取第一个，用它的物品贴图。
     * 没有对应机器（如占位类型）时返回空栈，列表就不画图标。
     */
    public static ItemStack machineOf(String typeId) {
        if (typeId == null || typeId.isEmpty()) return ItemStack.EMPTY;
        ensureMachines();
        ItemStack stack = MACHINE_BY_TYPE.get(typeId);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** 机器注册表只扫一遍，按配方类型归组后缓存。 */
    private static void ensureMachines() {
        if (MACHINE_BY_TYPE != null) return;
        java.util.Map<String, java.util.List<MachineDefinition>> grouped = new java.util.HashMap<>();
        try {
            for (MachineDefinition def : GTRegistries.MACHINES) {
                GTRecipeType[] types = def.getRecipeTypes();
                if (types == null) continue;
                for (GTRecipeType type : types) {
                    if (type == null || type.registryName == null) continue;
                    grouped.computeIfAbsent(type.registryName.toString(), k -> new java.util.ArrayList<>()).add(def);
                }
            }
        } catch (Throwable ignored) {
            // 注册表尚未就绪时留空，下次再试。
            return;
        }
        java.util.Map<String, ItemStack> result = new java.util.HashMap<>();
        for (var entry : grouped.entrySet()) {
            java.util.List<MachineDefinition> defs = entry.getValue();
            defs.sort(java.util.Comparator.comparing(d -> d.getId().toString()));
            try {
                ItemStack stack = defs.get(0).asStack();
                if (stack != null && !stack.isEmpty()) result.put(entry.getKey(), stack);
            } catch (Throwable ignored) {
                // 个别机器取物品会抛，跳过即可。
            }
        }
        MACHINE_BY_TYPE = result;
    }

    /**
     * 编程电路（{@code gtceu:programmed_circuit}）的图标。
     *
     * <p>33 种变体共用同一个物品，外观上的差别只在 NBT 里的配置号，所以必须按号现造一个栈，
     * 不能退回 {@link #itemOf}（那样只会得到 0 号）。
     */
    public static ItemStack circuitOf(int configuration) {
        if (configuration < 0 || configuration > 32) return ItemStack.EMPTY;
        ItemStack cached = CIRCUITS.get(configuration);
        if (cached != null) return cached;
        ItemStack stack = ItemStack.EMPTY;
        try {
            // 走 GT 自己的工厂，保证与游戏内电路完全一致（含配置号 NBT）。
            stack = com.gregtechceu.gtceu.common.item.IntCircuitBehaviour.stack(configuration);
        } catch (Throwable ignored) {
            // GT 未就绪时留空，列表只是不画图标，不影响其它功能。
        }
        CIRCUITS.put(configuration, stack);
        return stack;
    }

    /**
     * 一条配方在列表里代表它的图标：取产出列表的第一位。
     *
     * <p>优先物品产出，没有物品产出才退到流体产出。调用方据此决定画物品还是画流体。
     */
    public static ContentRef primaryOutput(RecipeSnapshot r) {
        if (r == null) return null;
        if (!r.itemOutputs.isEmpty()) return r.itemOutputs.get(0);
        if (!r.fluidOutputs.isEmpty()) return r.fluidOutputs.get(0);
        return null;
    }

    private static ItemStack resolveItem(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return ItemStack.EMPTY;
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null || item == Items.AIR) return ItemStack.EMPTY;
            return new ItemStack(item);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static FluidStack resolveFluid(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return FluidStack.EMPTY;
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(rl);
            if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
            return new FluidStack(fluid, 1);
        } catch (Throwable t) {
            return FluidStack.EMPTY;
        }
    }
}
