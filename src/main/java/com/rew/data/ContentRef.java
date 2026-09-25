package com.rew.data;

import net.minecraft.world.item.ItemStack;

import com.rew.i18n.RewIcons;
import com.rew.i18n.RewLang;

/**
 * 配方中单个输入/输出条目的可序列化表示。
 *
 * <p>设计要点：{@link #raw} 保存 GT 原始 JSON（{@code Ingredient#toJson} /
 * {@code FluidIngredient#toJson} / {@code EnergyStack} 的 codec 结果），因此从快照
 * 重建配方时是无损的 —— 标签、多选材料、NBT、概率都能原样还原；其余字段只服务于
 * 「列表显示 + 搜索」。
 */
public final class ContentRef {

    /** 能力名，对应 GT {@code RecipeCapability.name}：item / fluid / eu ... */
    public String capability = "";

    /** 显示与搜索用的 ID，如 {@code minecraft:iron_ingot} / {@code minecraft:water}。 */
    public String id = "";

    /** 数量：物品个数 / 流体 mB / EU 总量。用 long 以容纳 EU 的 long 量级。 */
    public long amount = 1L;

    /** 成功概率，10000 = 100%（GT 的 maxChance 基准值）。 */
    public int chance = 10000;

    /** 概率上限，用于判断是否「必定产出」。 */
    public int maxChance = 10000;

    /** 原始 JSON 字符串，用于无损重建。 */
    public String raw = "";

    /** 是否来自标签（tag），仅用于 UI 提示。 */
    public boolean tag = false;

    /** 本地化显示名，运行时填充，不写入缓存。 */
    public transient String displayName = "";

    /**
     * 扫描时记下的代表性物品栈（含 NBT）。
     *
     * <p>药水这类物品只靠 ID 画出来是空瓶，名字也是「药水」而不是「力量药水」。
     * 这里留一份带 NBT 的原栈，图标和名字都用它。不写入缓存，重扫时重新生成。
     */
    public transient ItemStack iconStack = ItemStack.EMPTY;

    public boolean isCertain() {
        return chance >= maxChance;
    }

    /** 概率的百分比文本，必定产出时返回空串。 */
    public String chanceText() {
        if (isCertain() || maxChance <= 0) return "";
        return String.format("%.1f%%", chance * 100.0 / maxChance);
    }

    /**
     * 本地化显示名：优先用语言文件里的中文名，取不到再退回 ID。
     *
     * <p>结果缓存在 {@link #displayName} 上，列表反复渲染时不再查注册表。
     */
    public String localizedName() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        String resolved = "";
        // 带 NBT 的物品（药水等）用原栈的名字，否则只能得到「药水」这种泛称。
        if (iconStack != null && !iconStack.isEmpty()) {
            resolved = iconStack.getHoverName().getString();
        }
        if (resolved == null || resolved.isEmpty()) {
            resolved = "fluid".equals(capability) ? RewLang.fluidName(id) : RewLang.itemName(id);
        }
        displayName = (resolved == null || resolved.isEmpty()) ? id : resolved;
        return displayName;
    }

    /** 这一行该画的物品贴图：优先用带 NBT 的原栈，没有再按 ID 取。 */
    public ItemStack iconItem() {
        if (iconStack != null && !iconStack.isEmpty()) return iconStack;
        return RewIcons.itemOf(id);
    }

    /** 列表里显示的一行文本。 */
    public String display() {
        String name = localizedName();
        if (amount > 1L) name = amount + "x " + name;
        if (!isCertain()) name = name + " [" + chanceText() + "]";
        return name;
    }

    @Override
    public String toString() {
        return amount + "x " + id + (isCertain() ? "" : " (" + chanceText() + ")");
    }
}
