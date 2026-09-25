package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.rew.data.ContentRef;
import com.rew.data.RecipeIndex;
import com.rew.data.RecipeSnapshot;
import com.rew.data.TypeInfo;
import com.rew.i18n.RewIcons;
import com.rew.i18n.RewLang;
import com.rew.search.PinyinSearch;
import com.rew.ui.widget.PopupMenuWidget;
import com.rew.ui.widget.ScrollListWidget;
import com.rew.ui.widget.SearchBarWidget;
import com.rew.ui.widget.SeparatorWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * 主编辑器界面：配方类型列表 + 配方列表 + 搜索。
 *
 * <p>布局（全屏）：
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │ 配方编辑工坊                       类型 62 / 配方 8431 │
 * │ ──────────────────────────────────────────────────── │
 * │ [搜索类型…      ]  │ [搜索配方…                     ] │
 * │ ┌──────────────┐  │ ┌──────────────────────────────┐ │
 * │ │ 研磨机  [125] │  │ │ 铁锭 → 铁粉                   │ │
 * │ │ 离心机  [366] │  │ │ ...                          │ │
 * │ └──────────────┘  │ └──────────────────────────────┘ │
 * │ ──────────────────────────────────────────────────── │
 * │ 左键选中类型；左键点配方进入编辑器                      │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>右键配方类型会弹出菜单（当前只有「新建配方」一项），用于给该类型新增一条配方。
 *
 * <p>界面状态（选中的类型、两个搜索词、两个列表的滚动位置）存在 {@link UiState} 里，
 * 因此点进配方编辑器再返回时，视野会停在原处，不会跳回顶部。
 */
public class RewEditorScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int HEADER_H = 14;
    private static final int SEARCH_H = 14;

    private final RecipeIndex index = RecipeIndex.get();

    private final SearchBarWidget typeSearch;
    private final ScrollListWidget<TypeInfo> typeList;
    private final SearchBarWidget recipeSearch;
    private final ScrollListWidget<RecipeSnapshot> recipeList;

    /** 右键菜单：常驻一个隐藏实例，右键时只改内容与位置（见 PopupMenuWidget 的说明）。 */
    private final PopupMenuWidget popupMenu = new PopupMenuWidget();

    private String selectedTypeId = null;

    public RewEditorScreen() {
        super(0, 0, 0, 0);
        // 全屏：宽高在 attach 时由 LDLib 的 fullScreen 逻辑填充，这里先给一个安全初值。
        setSize(480, 270);

        int w = 480;
        int h = 270;

        setBackground(RewTextures.panel());

        addWidget(new LabelWidget(MARGIN, MARGIN, "§b配方编辑工坊"));
        addWidget(new LabelWidget(MARGIN + 150, MARGIN,
                "§7类型 " + index.totalTypes() + " / 配方 " + index.totalRecipes()
                        + (PinyinSearch.available() ? " §a[拼音]" : "")));

        int top = MARGIN + HEADER_H;

        // 标题与内容之间的横线。
        addWidget(SeparatorWidget.horizontal(MARGIN, top - 2, w - MARGIN * 2));

        // 左列：类型
        typeSearch = new SearchBarWidget(MARGIN, top, 200, SEARCH_H,
                "搜索配方类型（中文名 / 拼音 / ID）", q -> refreshTypes());
        addWidget(typeSearch);

        typeList = new ScrollListWidget<>(MARGIN, top + SEARCH_H + 2, 200, h - top - SEARCH_H - 2 - 24);
        typeList.setOnClick(this::selectType);
        typeList.setOnRightClick(this::openTypeMenu);
        addWidget(typeList);

        // 左右两列之间的竖线。
        addWidget(SeparatorWidget.vertical(MARGIN + 200 + MARGIN / 2, top, h - top - 22));

        // 右列：配方
        int rightX = MARGIN + 200 + MARGIN;
        int rightW = w - rightX - MARGIN;

        recipeSearch = new SearchBarWidget(rightX, top, rightW, SEARCH_H,
                "搜索配方（物品中文名 / 拼音 / ID / 条件）", q -> refreshRecipes());
        addWidget(recipeSearch);

        recipeList = new ScrollListWidget<>(rightX, top + SEARCH_H + 2, rightW,
                h - top - SEARCH_H - 2 - 24);
        recipeList.setOnClick(this::openRecipe);
        addWidget(recipeList);

        // 内容与底部提示之间的横线。
        addWidget(SeparatorWidget.horizontal(MARGIN, h - 22, w - MARGIN * 2));

        addWidget(new LabelWidget(MARGIN, h - 18,
                "§8左键选中类型；左键点配方进入编辑器；§7右键类型可新建配方"));

        // 菜单必须排在控件树末尾：LDLib 从后往前分发点击，排最后才能盖住下层列表。
        addWidget(popupMenu);

        restoreState();
    }

    // ------------------------------------------------------------------ 状态保持

    /** 恢复上次离开时的选中类型、搜索词与滚动位置。 */
    private void restoreState() {
        UiState state = UiState.get();
        selectedTypeId = state.selectedTypeId;
        typeSearch.setQuery(state.typeQuery);
        recipeSearch.setQuery(state.recipeQuery);

        refreshTypes();
        refreshRecipes();

        typeList.setScrollYOffset(state.typeScroll);
        recipeList.setScrollYOffset(state.recipeScroll);
    }

    /** 把当前界面状态记下来，供返回时恢复。 */
    private void saveState() {
        UiState state = UiState.get();
        state.selectedTypeId = selectedTypeId;
        state.typeQuery = typeSearch.query();
        state.recipeQuery = recipeSearch.query();
        state.typeScroll = typeList.getScrollYOffset();
        state.recipeScroll = recipeList.getScrollYOffset();
    }

    // ------------------------------------------------------------------ 列表

    private void selectType(TypeInfo type) {
        selectedTypeId = type.id;
        saveState();
        refreshTypes();
        // 换类型时配方列表要回到顶部：沿用上一个类型的滚动位置会让人以为列表是空的。
        recipeList.resetScroll();
        refreshRecipes();
        saveState();
    }

    private void refreshTypes() {
        String q = typeSearch.query();
        List<ScrollListWidget.RowSpec<TypeInfo>> specs = new ArrayList<>();
        for (TypeInfo t : index.types()) {
            String name = displayNameOf(t);
            String hay = t.id + " " + t.group + " " + name + " " + groupName(t.group);
            if (!PinyinSearch.matches(hay, q)) continue;
            String label = (t.id.equals(selectedTypeId) ? "§b▶ §r" : "  ")
                    + name + " §8[" + t.recipeCount + "]"
                    + (t.multiblock ? " §5多方块" : "");
            specs.add(new ScrollListWidget.RowSpec<>(t, label, 0xFFFFFF)
                    .selected(t.id.equals(selectedTypeId))
                    .icon(RewIcons.machineOf(t.id))
                    .tooltip("§f" + name + "\n§8" + t.id
                            + "\n§7分组：" + groupName(t.group)
                            + "\n§7物品 输入/输出：" + t.maxItemInputs + "/" + t.maxItemOutputs
                            + "\n§7流体 输入/输出：" + t.maxFluidInputs + "/" + t.maxFluidOutputs
                            + "\n§8右键：新建配方"));
        }
        typeList.setRows(specs);
    }

    private void refreshRecipes() {
        if (selectedTypeId == null) {
            recipeList.setRows(List.of());
            return;
        }
        String q = recipeSearch.query();
        List<ScrollListWidget.RowSpec<RecipeSnapshot>> specs = new ArrayList<>();
        for (RecipeSnapshot r : index.recipesOf(selectedTypeId)) {
            if (!PinyinSearch.matches(index.blobOf(r) + " " + displayNameOf(r), q)) continue;
            String mark = r.userAdded ? "§a+ " : r.modified ? "§e* " : r.disabled ? "§c× " : "  ";
            ScrollListWidget.RowSpec<RecipeSnapshot> spec =
                    new ScrollListWidget.RowSpec<>(r, mark + summarize(r), 0xFFFFFF)
                            .disabledLook(r.disabled)
                            .tooltip(tooltipOf(r));
            // 行首图标取产出列表第一位：物品画物品贴图，流体画流体自己的贴图。
            applyOutputIcon(spec, r);
            specs.add(spec);
        }
        recipeList.setRows(specs);
    }

    private void openRecipe(RecipeSnapshot snap) {
        saveState();
        UiOpenHelper.openFullScreen(new RecipeEditorScreen(snap.copy(), index.type(snap.typeId)),
                "编辑 " + snap.id);
    }

    // ------------------------------------------------------------------ 右键菜单

    /** 右键配方类型：弹出该类型的可用动作。 */
    private void openTypeMenu(TypeInfo type) {
        // 右键同时把该类型选中，符合「右键 = 对这条操作」的直觉。
        selectedTypeId = type.id;
        saveState();
        refreshTypes();
        refreshRecipes();

        List<PopupMenuWidget.Item> items = new ArrayList<>();
        items.add(new PopupMenuWidget.Item("§a新建配方", () -> createRecipe(type)));

        var mc = net.minecraft.client.Minecraft.getInstance();
        // 菜单弹在鼠标位置；PopupMenuWidget 内部会把它夹在屏幕内。
        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        popupMenu.show((int) mouseX, (int) mouseY, items,
                getSize().width, getSize().height);
    }

    /** 新建一条空配方并直接进入编辑器。 */
    private void createRecipe(TypeInfo type) {
        // ID 由索引分配，保证不与已有配方或草稿撞名。
        RecipeSnapshot draft = RecipeSnapshot.newRecipe(index.nextDraftId(type.id), type);
        // 立刻登记进内存，列表里就能看到它（带 §a+ 标记），不会因为忘记保存而丢失。
        index.putDraft(draft);
        saveState();
        UiOpenHelper.openFullScreen(new RecipeEditorScreen(draft, type), "新建配方");
    }

    // ------------------------------------------------------------------ 显示

    /** 配方类型的中文名：读 GT 自己的语言键，没有条目就退回路径名。 */
    private static String displayNameOf(TypeInfo t) {
        if (t.displayName != null && !t.displayName.isEmpty()) return t.displayName;
        t.displayName = RewLang.typeName(t.id);
        return t.displayName;
    }

    /** 配方行的补充搜索文本：把输入输出的中文名也算进去。 */
    private static String displayNameOf(RecipeSnapshot r) {
        return "";
    }

    /** GT 分组的中文名，仅用于显示与搜索。 */
    private static String groupName(String group) {
        if (group == null) return "";
        return switch (group.toLowerCase()) {
            case "multiblock" -> "多方块";
            case "electric" -> "电力";
            case "generator" -> "发电机";
            case "steam" -> "蒸汽";
            case "dummy" -> "占位";
            default -> group;
        };
    }

    private static String summarize(RecipeSnapshot r) {
        StringBuilder sb = new StringBuilder();
        sb.append(shortId(r.id)).append("  §7");
        appendBrief(sb, r.itemInputs);
        appendBrief(sb, r.fluidInputs);
        if (!r.itemOutputs.isEmpty() || !r.fluidOutputs.isEmpty()) sb.append("§8→ §7");
        appendBrief(sb, r.itemOutputs);
        appendBrief(sb, r.fluidOutputs);
        sb.append("§8");
        if (r.inputEUt > 0) sb.append(r.inputEUt).append("EU ");
        sb.append(r.duration).append("刻");
        return sb.toString();
    }

    /** 把产出列表第一位设成行首图标。 */
    private static void applyOutputIcon(ScrollListWidget.RowSpec<?> spec, RecipeSnapshot r) {
        ContentRef out = RewIcons.primaryOutput(r);
        if (out == null) return;
        if ("fluid".equals(out.capability)) spec.fluidIcon(RewIcons.fluidOf(out.id));
        else spec.icon(out.iconItem());
    }

    /** 配方行里只露前两个条目的中文名，剩下的用数量带过，避免一行挤爆。 */
    private static void appendBrief(StringBuilder sb, List<ContentRef> contents) {
        int shown = 0;
        for (var c : contents) {
            if (shown == 2) break;
            if (shown > 0) sb.append("、");
            sb.append(c.localizedName());
            if (c.amount > 1L) sb.append("×").append(c.amount);
            shown++;
        }
        if (contents.size() > shown) sb.append(" §8+").append(contents.size() - shown);
        if (shown > 0) sb.append(' ');
    }

    private static String shortId(String id) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String tooltipOf(RecipeSnapshot r) {
        StringBuilder sb = new StringBuilder();
        sb.append("§f").append(r.id).append('\n');
        sb.append("§7类型：").append(RewLang.typeName(r.typeId)).append('\n');
        if (r.disabled) sb.append("§c已被禁用（未加载进游戏，仍可编辑/恢复）\n");
        if (r.userAdded) sb.append("§a由本工具新建\n");
        if (r.modified) sb.append("§e已被本工具修改\n");
        if (r.inputEUt > 0) sb.append("§7输入：").append(r.inputEUt).append(" EU/t");
        if (r.inputAmperage > 1) sb.append(" × ").append(r.inputAmperage).append("A");
        if (r.inputEUt > 0 || r.outputEUt > 0) sb.append('\n');
        if (r.outputEUt > 0) sb.append("§7输出：").append(r.outputEUt).append(" EU/t\n");
        sb.append("§7耗时：").append(r.duration).append(" 刻\n");
        for (var c : r.itemInputs) sb.append("§8输入  ").append(c.display()).append('\n');
        for (var c : r.fluidInputs) sb.append("§8流体  ").append(c.display()).append('\n');
        for (var c : r.itemOutputs) sb.append("§8产出  ").append(c.display()).append('\n');
        for (var c : r.fluidOutputs) sb.append("§8流体产出  ").append(c.display()).append('\n');
        for (var c : r.conditions) sb.append("§d条件  ").append(c.display()).append('\n');
        return sb.toString();
    }
}
