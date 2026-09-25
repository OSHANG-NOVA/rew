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
