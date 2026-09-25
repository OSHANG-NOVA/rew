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
import com.rew.ui.widget.ScrollListWidget;
import com.rew.ui.widget.SearchBarWidget;
import com.rew.ui.widget.SeparatorWidget;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GT 配方总览 —— 多选 + Del 键禁用（对应 {@code /rew gtm}）。
 *
 * <p>交互按用户描述实现：
 * <ol>
 * <li>左侧选配方类型（可搜索）；</li>
 * <li>右侧列出该类型全部配方；</li>
 * <li>左键点一条 = 选中，再点其他 = 多选叠加；</li>
 * <li>按 <b>Del</b> 键 = 把选中的配方「移到缓存位置」——即写进
 * {@code disabled.json}，它们不再加载进 MC，但本工具仍能读到并随时恢复；</li>
 * <li>按 <b>Ins</b> 键 = 反向操作，把选中的配方恢复加载。</li>
 * </ol>
 *
 * <p>三个搜索框分别对应：配方类型、输入物品、输出物品；条件筛选用一个文本框做子串匹配。
 */
public class GtmBrowserScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int HEADER_H = 14;
    private static final int FIELD_H = 14;

    private final RecipeIndex index = RecipeIndex.get();

    private final SearchBarWidget typeSearch;
    private final SearchBarWidget inSearch;
    private final SearchBarWidget outSearch;
    private final SearchBarWidget condSearch;

    private final ScrollListWidget<TypeInfo> typeList;
    private final ScrollListWidget<RecipeSnapshot> recipeList;

    private final Set<String> selection = new LinkedHashSet<>();
    private String selectedTypeId = null;

    private final LabelWidget statusLabel;

    public GtmBrowserScreen() {
        super(0, 0, 0, 0);
        setSize(480, 270);
        setBackground(RewTextures.panel());

        int w = 480;
        int h = 270;

        addWidget(new LabelWidget(MARGIN, MARGIN, "§b配方总览 §7/rew gtm"));
        addWidget(new LabelWidget(MARGIN + 150, MARGIN,
                "§7多选后按 §cDel §7禁用 / §aIns §7恢复"));

        int top = MARGIN + HEADER_H;

        addWidget(SeparatorWidget.horizontal(MARGIN, top - 2, w - MARGIN * 2));
        addWidget(SeparatorWidget.vertical(MARGIN + 160 + MARGIN / 2, top, h - top - 22));
        addWidget(SeparatorWidget.horizontal(MARGIN, h - 22, w - MARGIN * 2));

        typeSearch = new SearchBarWidget(MARGIN, top, 160, FIELD_H,
                "搜索配方类型（中文名 / 拼音）", q -> refreshTypes());
        addWidget(typeSearch);

        typeList = new ScrollListWidget<>(MARGIN, top + FIELD_H + 2, 160, h - top - FIELD_H - 26);
        typeList.setOnClick(this::selectType);
        addWidget(typeList);

        int rx = MARGIN + 160 + MARGIN;
        int rw = w - rx - MARGIN;

        inSearch = new SearchBarWidget(rx, top, rw / 3 - 2, FIELD_H,
                "搜索输入（中文名 / 拼音）", q -> refreshRecipes());
        addWidget(inSearch);
        outSearch = new SearchBarWidget(rx + rw / 3, top, rw / 3 - 2, FIELD_H,
                "搜索产出（中文名 / 拼音）", q -> refreshRecipes());
        addWidget(outSearch);
        condSearch = new SearchBarWidget(rx + 2 * (rw / 3), top, rw / 3 - 2, FIELD_H,
                "搜索条件（中文名）", q -> refreshRecipes());
        addWidget(condSearch);

        recipeList = new ScrollListWidget<>(rx, top + FIELD_H + 2, rw, h - top - FIELD_H - 26);
        recipeList.setOnClick(this::toggleSelect);
        recipeList.setOnRightClick(this::quickToggleDisable);
        addWidget(recipeList);

        statusLabel = new LabelWidget(MARGIN, h - 18,
                "§7已选 0 条 §8| 左键多选 · Del 禁用 · Ins 恢复 · 右键单条切换");
        addWidget(statusLabel);

        refreshTypes();
        refreshRecipes();
    }

    // ------------------------------------------------------------------ 列表

    private void selectType(TypeInfo t) {
        selectedTypeId = t.id;
        selection.clear();
        refreshTypes();
        refreshRecipes();
    }

    private void refreshTypes() {
        String q = typeSearch.query();
        List<ScrollListWidget.RowSpec<TypeInfo>> specs = new ArrayList<>();
        for (TypeInfo t : index.types()) {
            String name = typeNameOf(t);
            if (!PinyinSearch.matches(t.id + " " + t.group + " " + name, q)) continue;
            int disabledCount = countDisabledOf(t.id);
            specs.add(new ScrollListWidget.RowSpec<>(t,
                    "  " + name + " §8[" + t.recipeCount
                            + (disabledCount > 0 ? " §c-" + disabledCount + "§8" : "") + "]",
                    0xFFFFFF)
                    .selected(t.id.equals(selectedTypeId))
                    .icon(RewIcons.machineOf(t.id))
                    .tooltip("§f" + name + "\n§8" + t.id));
        }
        typeList.setRows(specs);
    }

    private int countDisabledOf(String typeId) {
        int n = 0;
        for (RecipeSnapshot r : index.recipesOf(typeId)) {
            if (r.disabled) n++;
        }
        return n;
    }

    private void refreshRecipes() {
        if (selectedTypeId == null) {
            recipeList.setRows(List.of());
            return;
        }
        String inQ = inSearch.query();
        String outQ = outSearch.query();
        String condQ = condSearch.query();

        List<ScrollListWidget.RowSpec<RecipeSnapshot>> specs = new ArrayList<>();
        for (RecipeSnapshot r : index.recipesOf(selectedTypeId)) {
            if (!matchesInputs(r, inQ)) continue;
            if (!matchesOutputs(r, outQ)) continue;
            if (!matchesConditions(r, condQ)) continue;

            String mark = r.disabled ? "§c× " : selection.contains(r.id) ? "§a✔ " : "  ";
            ScrollListWidget.RowSpec<RecipeSnapshot> spec =
                    new ScrollListWidget.RowSpec<>(r, mark + recipeLine(r), 0xFFFFFF)
                            .selected(selection.contains(r.id))
                            .disabledLook(r.disabled)
                            .tooltip(tooltipOf(r));
            applyOutputIcon(spec, r);
            specs.add(spec);
        }
        recipeList.setRows(specs);
        updateStatus();
    }

    private static boolean matchesInputs(RecipeSnapshot r, String q) {
        if (q == null || q.isEmpty()) return true;
        for (var c : r.itemInputs) if (PinyinSearch.matches(c.id + " " + c.localizedName(), q)) return true;
        for (var c : r.fluidInputs) if (PinyinSearch.matches(c.id + " " + c.localizedName(), q)) return true;
        return false;
    }

    private static boolean matchesOutputs(RecipeSnapshot r, String q) {
        if (q == null || q.isEmpty()) return true;
        for (var c : r.itemOutputs) if (PinyinSearch.matches(c.id + " " + c.localizedName(), q)) return true;
        for (var c : r.fluidOutputs) if (PinyinSearch.matches(c.id + " " + c.localizedName(), q)) return true;
        return false;
    }

    private static boolean matchesConditions(RecipeSnapshot r, String q) {
        if (q == null || q.isEmpty()) return true;
        for (var c : r.conditions) if (PinyinSearch.matches(c.type + " " + c.display(), q)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 选择

    private void toggleSelect(RecipeSnapshot r) {
        if (!selection.remove(r.id)) {
            selection.add(r.id);
        }
        recipeList.refreshSelection(s -> selection.contains(s.id));
        updateStatus();
    }

    private void quickToggleDisable(RecipeSnapshot r) {
        index.setDisabled(r.id, !r.disabled);
        refreshRecipes();
    }

    /** Del：把选中项全部禁用（移出加载）。 */
    public void disableSelection() {
        if (selection.isEmpty()) {
            setStatus("§e没有选中任何配方");
            return;
        }
        index.setDisabledBulk(new ArrayList<>(selection), true);
        int n = selection.size();
        selection.clear();
        refreshRecipes();
        setStatus("§c已禁用 " + n + " 条 —— 执行 /rew save 后 /reload 生效");
    }

    /** Ins：把选中项全部恢复加载。 */
    public void enableSelection() {
        if (selection.isEmpty()) {
            setStatus("§e没有选中任何配方");
            return;
        }
        index.setDisabledBulk(new ArrayList<>(selection), false);
        int n = selection.size();
        selection.clear();
        refreshRecipes();
        setStatus("§a已恢复 " + n + " 条 —— 执行 /rew save 后 /reload 生效");
    }

    private void updateStatus() {
        if (statusLabel != null) {
            statusLabel.setText("§7已选 " + selection.size()
                    + " 条 §8| 左键多选 · Del 禁用 · Ins 恢复 · 右键单条切换"
                    + " §8| 库内禁用总数 " + index.disabledIds().size());
        }
    }

    private void setStatus(String s) {
        if (statusLabel != null) statusLabel.setText(s);
    }

    // ------------------------------------------------------------------ 键盘

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // GLFW_KEY_DELETE = 261, GLFW_KEY_INSERT = 260
        if (keyCode == 261) {
            disableSelection();
            return true;
        }
        if (keyCode == 260) {
            enableSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 行首图标取产出列表第一位。 */
    private static void applyOutputIcon(ScrollListWidget.RowSpec<?> spec, RecipeSnapshot r) {
        ContentRef out = RewIcons.primaryOutput(r);
        if (out == null) return;
        if ("fluid".equals(out.capability)) spec.fluidIcon(RewIcons.fluidOf(out.id));
        else spec.icon(out.iconItem());
    }

    /** 配方类型的中文名，结果记在 TypeInfo 上避免重复查语言文件。 */
    private static String typeNameOf(TypeInfo t) {
        if (t.displayName != null && !t.displayName.isEmpty()) return t.displayName;
        t.displayName = RewLang.typeName(t.id);
        return t.displayName;
    }

    /** 配方行：ID 末段后面跟上首个输入与首个产出的中文名，方便辨认。 */
    private static String recipeLine(RecipeSnapshot r) {
        StringBuilder sb = new StringBuilder(shortId(r.id));
        String in = firstName(r.itemInputs, r.fluidInputs);
        String out = firstName(r.itemOutputs, r.fluidOutputs);
        if (!in.isEmpty() || !out.isEmpty()) {
            sb.append("  §7").append(in);
            if (!out.isEmpty()) sb.append(" §8→ §7").append(out);
        }
        return sb.toString();
    }

    private static String firstName(java.util.List<com.rew.data.ContentRef> a,
                                    java.util.List<com.rew.data.ContentRef> b) {
        if (!a.isEmpty()) return a.get(0).localizedName();
        if (!b.isEmpty()) return b.get(0).localizedName();
        return "";
    }

    private static String shortId(String id) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String tooltipOf(RecipeSnapshot r) {
        StringBuilder sb = new StringBuilder();
        sb.append("§f").append(r.id).append('\n');
        if (r.disabled) sb.append("§c已禁用\n");
        for (var c : r.itemInputs) sb.append("§8输入  ").append(c.display()).append('\n');
        for (var c : r.fluidInputs) sb.append("§8流体  ").append(c.display()).append('\n');
        for (var c : r.itemOutputs) sb.append("§8产出  ").append(c.display()).append('\n');
        for (var c : r.fluidOutputs) sb.append("§8流体产出  ").append(c.display()).append('\n');
        for (var c : r.conditions) sb.append("§d条件  ").append(c.display()).append('\n');
        sb.append("§7").append(r.duration).append("刻");
        if (r.inputEUt > 0) sb.append(" / ").append(r.inputEUt).append("EU");
        return sb.toString();
    }
}
