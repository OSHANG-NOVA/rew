package com.rew.data;

/**
 * 配方类型的元数据（列表项所需的一切）。
 *
 * <p>图标策略：运行时优先用 GT 自己的 {@code GTRecipeCategory#getIcon()}（这正是 JEI
 * 类别图标所用的同一张贴图，JEI 侧走 {@code IGui2IDrawable.toDrawable(category.getIcon())}）；
 * 落盘时只保存物品 ID 作为跨会话的降级显示，避免缓存里塞二进制。
 */
public final class TypeInfo {

    /** 类型 ID，如 {@code gtceu:macerator}。 */
    public String id = "";

    /** GT 分组：ELECTRIC / GENERATOR / MULTIBLOCK / STEAM / DUMMY。 */
    public String group = "";

    /** 图标物品 ID（{@code type.getIconSupplier()} 的结果，可能为空 → 运行时回退 barrier）。 */
    public String iconItem = "";

    /** 该类型的配方总数。 */
    public int recipeCount = 0;

    /** 是否多方块类型（{@code group == "multiblock"}）。 */
    public boolean multiblock = false;

    /** 各类能力的上限，决定编辑器每张表最多可放几行（0 = 该类型不使用该能力）。 */
    public int maxItemInputs = 0;
    public int maxItemOutputs = 0;
    public int maxFluidInputs = 0;
    public int maxFluidOutputs = 0;

    /** 是否含 EU 输入/输出。 */
    public boolean hasEUIn = false;
    public boolean hasEUOut = false;

    /** 本地化名，运行时填充。 */
    public transient String displayName = "";

    /** 搜索用小写串。 */
    public String searchBlob() {
        return (id + " " + group + " " + displayName).toLowerCase();
    }
}
