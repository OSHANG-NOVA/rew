package com.rew.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    /**
     * 物品的 NBT（SNBT 字符串），空串表示无 NBT。**会写入草稿文件**。
     *
     * <p>为什么需要一个持久化字段，而不是只靠 {@link #iconStack}：
     * {@code iconStack} 是 transient 的（它带 NBT、体积大，不适合进 54MB 的快照缓存），
     * 于是草稿存盘再读回后，带 NBT 的物品就只剩一个物品 ID 了 ——
     * 附魔书会显示成「附魔书」而不是「锋利 III」，图标也退回普通书。
     * 存一份 SNBT 就能在重新加载时把显示用的栈重建出来。
     *
     * <p>另外它也是 KubeJS 导出时带 NBT 的依据（KJS 写法 {@code "id {SNBT}"}）。
     */
    public String nbt = "";

    /**
     * 是否为 GT 编程电路（{@code IntCircuitIngredient}）。
     *
     * <p>编程电路在 GT 里是特殊原料：33 种变体共用同一个物品，靠 NBT 里的配置号区分，
     * 用物品 ID 根本表达不出「几号电路」。所以这里单列一个标记，UI 上不再当成普通物品，
     * 而是显示成「编程电路 #N」，重建时走 {@code IntCircuitIngredient.of(N)}。
     */
    public boolean circuit = false;

    /** 编程电路的配置号（0~32）；仅 {@link #circuit} 为 true 时有意义。 */
    public int circuitConfig = 0;

    /** 本地化显示名，运行时填充，不写入缓存。 */
    public transient String displayName = "";

    /**
     * 扫描时记下的代表性物品栈（含 NBT）。
     *
     * <p>药水这类物品只靠 ID 画出来是空瓶，名字也是「药水」而不是「力量药水」。
     * 这里留一份带 NBT 的原栈，图标和名字都用它。不写入缓存，重扫时重新生成。
     */
    public transient ItemStack iconStack = ItemStack.EMPTY;

    /**
     * 造一个编程电路条目（「电路」配置项改动时用）。
     *
     * <p>电路恒为单个、必定消耗，所以数量与概率都写死，作者不需要也不该改它们。
     */
    public static ContentRef ofCircuit(int configuration) {
        ContentRef ref = new ContentRef();
        ref.capability = "item";
        ref.circuit = true;
        ref.circuitConfig = Math.max(0, Math.min(32, configuration));
        ref.id = "gtceu:programmed_circuit";
        ref.amount = 1L;
        // 与 GT 的 circuitMeta(n) 对齐：它走 notConsumable，chance 写 0。
        // 电路是「插在电路槽里一直放着」的催化剂，绝不能被机器消耗掉。
        ref.chance = 0;
        ref.maxChance = 10000;
        return ref;
    }

    public boolean isCertain() {
        return chance >= maxChance;
    }

    /**
     * 是否为「不消耗」输入（催化剂）。
     *
     * <p>GT 用 {@code chance == 0} 表达这个概念，而不是另开一个布尔字段：
     * 见 {@code ItemRecipeCapability} 里
     * {@code if (content.chance == 0) { nonConsumables.addTo(ing, count); continue; }}
     * 以及 Fluid / EU 两个同名分支。编程电路、模具这类「占位但吃不到」的输入都靠它。
     *
     * <p>因此 UI 上不能再按百分比显示 —— 0% 会被误读成「永远不产出」，
     * 实际含义恰恰相反：物品必须放着，但不会被机器吃掉。
     */
    public boolean notConsumable() {
        return chance <= 0;
    }

    /** 概率的百分比文本；必定产出时返回空串，不消耗时返回「不消耗」。 */
    public String chanceText() {
        if (notConsumable()) return "不消耗";
        if (isCertain() || maxChance <= 0) return "";
        return String.format("%.1f%%", chance * 100.0 / maxChance);
    }

    /**
     * 本地化显示名：优先用语言文件里的中文名，取不到再退回 ID。
     *
     * <p>结果缓存在 {@link #displayName} 上，列表反复渲染时不再查注册表。
     */
    public String localizedName() {
        // 编程电路的名字由配置号决定，与物品名无关（33 种变体同名同贴图，只有编号不同）。
        if (circuit) return "编程电路 #" + circuitConfig;
        if (displayName != null && !displayName.isEmpty()) return displayName;
        String resolved = "";
        // 带 NBT 的物品（药水等）用原栈的名字，否则只能得到「药水」这种泛称。
        if (iconStack != null && !iconStack.isEmpty()) {
            resolved = iconStack.getHoverName().getString();
        }
        // 草稿从磁盘读回时 iconStack（transient）为空，改由持久化的 nbt 字段重建名字，
        // 否则「锋利 III」会退化成「附魔书」——名字看着对，实际不是同一样东西。
        if (resolved == null || resolved.isEmpty()) {
            resolved = nameFromNbt();
        }
        if (resolved == null || resolved.isEmpty()) {
            resolved = "fluid".equals(capability) ? RewLang.fluidName(id) : RewLang.itemName(id);
        }
        displayName = (resolved == null || resolved.isEmpty()) ? id : resolved;
        return displayName;
    }

    /**
     * 从 {@link #raw} 里抠出物品的 NBT（SNBT 字符串），没有则返回空串。
     *
     * <p>为什么要从 JSON 里挖而不是另存一个字段：带 NBT 的物品在 GT 里的实际形态是
     * {@code SizedIngredient → StrictNBTIngredient}，它的 JSON 形如
     * <pre>
     * {"content":{"type":"gtceu:sized","count":1,
     *             "ingredient":{"type":"forge:nbt","item":"...","nbt":"{...}"}}, ...}
     * </pre>
     * {@code raw} 已经把这段原样存下来了，再单开一个字段就会有两份真相、还可能不同步。
     * 这里做一次深度优先查找：只要 JSON 树里出现 {@code nbt} 键就取它。
     *
     * <p>导出 KubeJS 时用得上 —— KJS 的物品串写法是 {@code "id {SNBT}"}，
     * 不带上 NBT 的话，附魔书导出后会变成一本空书。
     */
    public String nbtSnbt() {
        // 显式字段优先：它可能来自背包选取（raw 之外还留了原栈），
        // 而 raw 是 GT 的序列化结果，两者应当一致；不一致时以显式字段为准。
        if (nbt != null && !nbt.isBlank()) return nbt;
        if (raw == null || raw.isBlank()) return "";
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(raw);
            return findNbt(el);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 深度优先找第一个 {@code nbt} 字符串字段。 */
    private static String findNbt(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonObject()) {
            com.google.gson.JsonObject obj = el.getAsJsonObject();
            com.google.gson.JsonElement nbt = obj.get("nbt");
            if (nbt != null && nbt.isJsonPrimitive()) return nbt.getAsString();
            for (var entry : obj.entrySet()) {
                String found = findNbt(entry.getValue());
                if (!found.isEmpty()) return found;
            }
            return "";
        }
        if (el.isJsonArray()) {
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                String found = findNbt(child);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    /** 这一行该画的物品贴图：优先用带 NBT 的原栈，没有再按 ID 取。 */
    public ItemStack iconItem() {
        // 编程电路的贴图随配置号变化，必须按号现造，不能退回通用物品。
        if (circuit) return RewIcons.circuitOf(circuitConfig);
        if (iconStack != null && !iconStack.isEmpty()) return iconStack;
        // 草稿从磁盘读回时 iconStack（transient）是空的，但 nbt 字段还在，
        // 用它把带 NBT 的栈重建出来，附魔光效与耐久条才不会丢。
        ItemStack rebuilt = RewIcons.itemWithNbt(id, nbt);
        if (!rebuilt.isEmpty()) return rebuilt;
        return RewIcons.itemOf(id);
    }

    /** 名字同理：有 NBT 时用重建出来的栈取完整名（「锋利 III」而不是「附魔书」）。 */
    private String nameFromNbt() {
        if (nbt == null || nbt.isBlank()) return "";
        try {
            ItemStack stack = RewIcons.itemWithNbt(id, nbt);
            return stack.isEmpty() ? "" : stack.getHoverName().getString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 列表里显示的一行文本。 */
    public String display() {
        String name = localizedName();
        // 电路恒为单个，不带数量前缀。
        if (!circuit && amount > 1L) name = amount + "x " + name;
        if (!isCertain()) name = name + " [" + chanceText() + "]";
        return name;
    }

    @Override
    public String toString() {
        return amount + "x " + id + (isCertain() ? "" : " (" + chanceText() + ")");
    }
}
