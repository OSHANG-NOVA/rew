package com.rew.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 GT 配方的只读快照。
 *
 * <p>快照是「列表展示 + 搜索 + 编辑器初值」的唯一数据源，与运行时 GT 对象解耦：
 * 这样首次全量扫描后可以把结果落盘，之后秒开，不必每次重扫 GT 的配方库。
 * 被禁用的配方也必须靠快照留存 —— 它们已从 GT 的配方库中被剔除，
 * 无法再从游戏内读到。
 */
public final class RecipeSnapshot {

    /** 完整配方 ID，如 {@code gtceu:macerator/iron_ingot}。 */
    public String id = "";

    /** 配方类型 ID，如 {@code gtceu:macerator}。 */
    public String typeId = "";

    /** 所属分类 ID（多数类型与 typeId 相同，聚合类型会有多个）。 */
    public String categoryId = "";

    /** 配方耗时（tick）。 */
    public int duration = 0;

    /** 输入 EU/t（来自 tickInputs 的 EU 能力）。 */
    public long inputEUt = 0L;

    /** 输入电流（安培）。 */
    public long inputAmperage = 1L;

    /** 输出 EU/t（发电机类配方）。 */
    public long outputEUt = 0L;

    /** 输出电流（安培）。 */
    public long outputAmperage = 1L;

    public List<ContentRef> itemInputs = new ArrayList<>();
    public List<ContentRef> itemOutputs = new ArrayList<>();
    public List<ContentRef> fluidInputs = new ArrayList<>();
    public List<ContentRef> fluidOutputs = new ArrayList<>();

    /** 条件列表（下侧条件表的初值）。 */
    public List<ConditionRef> conditions = new ArrayList<>();

    /** 运行时标记：是否已被本工具禁用（不写入缓存）。 */
    public transient boolean disabled = false;

    /** 运行时标记：是否被本工具修改过（不写入缓存）。 */
    public transient boolean modified = false;

    /** 运行时标记：是否由本工具新建（不写入缓存）。 */
    public transient boolean userAdded = false;

    /** 运行时标记：草稿文件路径（相对 config/rew），用于保存时定位。 */
    public transient String draftFile = "";

    /** 深拷贝，避免草稿编辑污染快照缓存。 */
    public RecipeSnapshot copy() {
        RecipeSnapshot s = new RecipeSnapshot();
        s.id = id;
        s.typeId = typeId;
        s.categoryId = categoryId;
        s.duration = duration;
        s.inputEUt = inputEUt;
        s.inputAmperage = inputAmperage;
        s.outputEUt = outputEUt;
        s.outputAmperage = outputAmperage;
        for (ContentRef c : itemInputs) s.itemInputs.add(copyContent(c));
        for (ContentRef c : itemOutputs) s.itemOutputs.add(copyContent(c));
        for (ContentRef c : fluidInputs) s.fluidInputs.add(copyContent(c));
        for (ContentRef c : fluidOutputs) s.fluidOutputs.add(copyContent(c));
        for (ConditionRef c : conditions) {
            ConditionRef n = new ConditionRef();
            n.type = c.type;
            n.json = c.json;
            n.reverse = c.reverse;
            n.tooltip = c.tooltip;
            s.conditions.add(n);
        }
        return s;
    }

    private static ContentRef copyContent(ContentRef c) {
        ContentRef n = new ContentRef();
        n.capability = c.capability;
        n.id = c.id;
        n.amount = c.amount;
        n.chance = c.chance;
        n.maxChance = c.maxChance;
        n.raw = c.raw;
        n.tag = c.tag;
        n.displayName = c.displayName;
        n.iconStack = c.iconStack == null || c.iconStack.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY
                : c.iconStack.copy();
        return n;
    }

    /**
     * 造一条属于指定配方类型的空配方（「右键 → 新建配方」的产物）。
     *
     * <p>各字段给的是能直接进编辑器的最小可用值：耗时与 EU 留 0 表示「还没填」，
     * 输入输出为空表，条件为空表。ID 从 {@code rew:} 命名空间里取一个没被占用的，
     * 由调用方（{@link RecipeIndex#nextDraftId}）保证唯一。
     */
    public static RecipeSnapshot newRecipe(String id, TypeInfo type) {
        RecipeSnapshot s = new RecipeSnapshot();
        s.id = id;
        s.typeId = type == null ? "" : type.id;
        s.categoryId = s.typeId;
        s.duration = 0;
        s.inputEUt = 0L;
        s.inputAmperage = 1L;
        s.outputEUt = 0L;
        s.outputAmperage = 1L;
        s.userAdded = true;
        return s;
    }

    /** 全部输入/输出条目（用于搜索与遍历）。 */
    public List<ContentRef> allContents() {
        List<ContentRef> all = new ArrayList<>(
                itemInputs.size() + itemOutputs.size() + fluidInputs.size() + fluidOutputs.size());
        all.addAll(itemInputs);
        all.addAll(itemOutputs);
        all.addAll(fluidInputs);
        all.addAll(fluidOutputs);
        return all;
    }

    /**
     * 搜索用文本：物品/流体的 ID 与本地化名、条件的注册名与中文描述都拼进去。
     *
     * <p>这样搜「铁锭」和搜 {@code iron_ingot} 都能命中同一条配方，搜「超净间」也能命中
     * 带 cleanroom 条件的配方。本地化名在 {@link ContentRef#localizedName()} 里按 ID 缓存，
     * 全量配方只在建索引时拼一次。
     */
    public String searchBlob() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(id).append(' ').append(typeId).append(' ');
        appendContents(sb, itemInputs);
        appendContents(sb, itemOutputs);
        appendContents(sb, fluidInputs);
        appendContents(sb, fluidOutputs);
        for (ConditionRef c : conditions) {
            sb.append(c.type).append(' ').append(c.display()).append(' ');
        }
        return sb.toString().toLowerCase();
    }

    private static void appendContents(StringBuilder sb, java.util.List<ContentRef> contents) {
        for (ContentRef c : contents) {
            sb.append(c.id).append(' ').append(c.localizedName()).append(' ');
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
