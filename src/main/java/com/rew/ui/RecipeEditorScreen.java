package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.rew.data.ConditionRef;
import com.rew.data.ContentRef;
import com.rew.data.DraftStore;
import com.rew.data.RecipeIndex;
import com.rew.data.RecipeSnapshot;
import com.rew.data.TypeInfo;
import com.rew.i18n.RewLang;
import com.rew.ui.widget.ConditionTableWidget;
import com.rew.ui.widget.ContentTableWidget;
import com.rew.ui.widget.SeparatorWidget;

/**
 * 统一配方编辑器 —— 所有配方类型（单方块 / 多方块 / 蒸汽 / 发电机）共用同一套布局。
 *
 * <p>布局（按用户确认的形态，单方块不另做 GUI 复刻）：
 * <pre>
 * ┌────────────────────────────────────────────────────────┐
 * │ gtceu:macerator/iron_ingot          [保存] [禁用] [返回]│
 * │ 耗时 [200] t   输入 [30] EU/t × [1] A                   │
 * │ ┌───────────┐ ┌───────────┐                             │
 * │ │ 输入表     │ │ 输出表     │   ← 左输入 / 右输出          │
 * │ │ 物品/流体  │ │ 物品/流体  │                             │
 * │ └───────────┘ └───────────┘                             │
 * │ ┌────────────────────────────────────────────────────┐ │
 * │ │ 条件表（下方）                                      │ │
 * │ └────────────────────────────────────────────────────┘ │
 * └────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RecipeEditorScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int HEADER_H = 16;
    private static final int TOPFIELD_H = 16;

    private final RecipeSnapshot draft;
    private final TypeInfo type;
    private final RecipeIndex index = RecipeIndex.get();

    private final ContentTableWidget inputTable;
    private final ContentTableWidget outputTable;
    private final ConditionTableWidget conditionTable;

    private TextFieldWidget durationField;
    private TextFieldWidget euField;
    private TextFieldWidget ampField;
    /** 编程电路配置号；留空表示这条配方不用电路。 */
    private TextFieldWidget circuitField;

    private LabelWidget statusLabel;

    public RecipeEditorScreen(RecipeSnapshot draft, TypeInfo type) {
        super(0, 0, 0, 0);
        this.draft = draft;
        this.type = type;
        setSize(480, 270);
        setBackground(RewTextures.panel());

        int w = 480;
        int h = 270;

        // ---- 顶栏 ----
        addWidget(new LabelWidget(MARGIN, MARGIN, "§b" + draft.id));
        addWidget(new LabelWidget(MARGIN, MARGIN + 10, "§8类型 " + RewLang.typeName(draft.typeId)
                + (type != null && type.multiblock ? " §5[多方块]" : " §7[单方块]")));

        addWidget(makeButton(w - MARGIN - 58, MARGIN, 58, 14, "§a保存", this::save));
        addWidget(makeButton(w - MARGIN - 58 - 62, MARGIN, 58, 14, "§e返回", this::goBack));
        addWidget(makeButton(w - MARGIN - 58 - 62 - 62, MARGIN, 58, 14,
                draft.disabled ? "§a恢复启用" : "§c禁用", this::toggleDisabled));

        statusLabel = new LabelWidget(MARGIN, h - 18, "§7就绪");
        addWidget(statusLabel);

        // ---- 数值行 ----
        int y = MARGIN + HEADER_H + 6;
        addWidget(new LabelWidget(MARGIN, y + 3, "§7耗时"));
        durationField = new TextFieldWidget(MARGIN + 34, y, 46, TOPFIELD_H,
                () -> String.valueOf(draft.duration),
                value -> {
                    Integer parsed = parseInt(value);
                    if (parsed != null) draft.duration = Math.max(0, parsed);
                });
        durationField.setClientSideWidget();
        durationField.setNumbersOnly(0, Integer.MAX_VALUE);
        addWidget(durationField);
        addWidget(new LabelWidget(MARGIN + 82, y + 3, "§7刻"));

        addWidget(new LabelWidget(MARGIN + 110, y + 3, "§7输入"));
        euField = new TextFieldWidget(MARGIN + 140, y, 60, TOPFIELD_H,
                () -> String.valueOf(draft.inputEUt),
                value -> {
                    Long parsed = parseLong(value);
                    if (parsed != null) draft.inputEUt = Math.max(0L, parsed);
                });
        euField.setClientSideWidget();
        euField.setNumbersOnly(0, Integer.MAX_VALUE);
        addWidget(euField);
        addWidget(new LabelWidget(MARGIN + 202, y + 3, "§7EU/t ×"));

        ampField = new TextFieldWidget(MARGIN + 240, y, 30, TOPFIELD_H,
                () -> String.valueOf(draft.inputAmperage),
                value -> {
                    Long parsed = parseLong(value);
                    if (parsed != null) draft.inputAmperage = Math.max(1L, parsed);
                });
        ampField.setClientSideWidget();
        ampField.setNumbersOnly(1, Integer.MAX_VALUE);
        addWidget(ampField);
        addWidget(new LabelWidget(MARGIN + 272, y + 3, "§7A"));

        // 数值行与表格之间的横线。
        addWidget(SeparatorWidget.horizontal(MARGIN, y + TOPFIELD_H + 3, w - MARGIN * 2));

        // ---- 三张表 ----
        int tablesTop = y + TOPFIELD_H + 6;
        int halfW = (w - MARGIN * 3) / 2;
        int tablesH = (h - tablesTop - 40) * 2 / 3;

        inputTable = new ContentTableWidget(MARGIN, tablesTop, halfW, tablesH,
                "输入", true, draft.itemInputs, draft.fluidInputs);
        inputTable.setOnAddContent(isItem -> openPicker(true, isItem, null));
        inputTable.setOnEditContent(ref -> openPicker(true, ref.capability.equals("fluid"), ref));
        inputTable.rebuild();
        addWidget(inputTable);

        // ---- 编程电路 ----
        // GT 的 33 种电路变体共用同一个物品 ID，只有 NBT 里的配置号不同，放进物品表里
        // 会长得一模一样、也看不出是几号；所以单列一个 0~32 的数字项，留空表示不用电路。
        // 它写进的是输入表底层那条电路数据，与普通物品行并存互不干扰。
        addWidget(new LabelWidget(MARGIN + 300, y + 3, "§7电路"));
        circuitField = new TextFieldWidget(MARGIN + 330, y, 36, TOPFIELD_H,
                () -> {
                    Integer c = inputTable.circuit();
                    return c == null ? "" : String.valueOf(c);
                },
                value -> {
                    String t = value == null ? "" : value.trim();
                    if (t.isEmpty()) {
                        inputTable.setCircuit(null);
                        return;
                    }
                    Integer parsed = parseInt(t);
                    if (parsed == null) return;
                    inputTable.setCircuit(Math.max(0, Math.min(32, parsed)));
                });
        circuitField.setClientSideWidget();
        circuitField.setHoverTooltips("编程电路配置号 0~32；留空表示这条配方不使用电路");
        addWidget(circuitField);
        addWidget(new LabelWidget(MARGIN + 370, y + 3, "§8(0-32，留空=无)"));

        outputTable = new ContentTableWidget(MARGIN * 2 + halfW, tablesTop, halfW, tablesH,
                "输出", false, draft.itemOutputs, draft.fluidOutputs);
        outputTable.setOnAddContent(isItem -> openPicker(false, isItem, null));
        outputTable.setOnEditContent(ref -> openPicker(false, ref.capability.equals("fluid"), ref));
        outputTable.rebuild();
        addWidget(outputTable);

        // 输入表与输出表之间的竖线。
        addWidget(SeparatorWidget.vertical(MARGIN + halfW + MARGIN / 2, tablesTop, tablesH));

        int condTop = tablesTop + tablesH + 6;

        // 表格与条件表之间的横线。
        addWidget(SeparatorWidget.horizontal(MARGIN, condTop - 3, w - MARGIN * 2));
        int condH = h - condTop - 24;
        conditionTable = new ConditionTableWidget(MARGIN, condTop, w - MARGIN * 2, condH,
                draft.conditions);
        conditionTable.setOnAddCondition(this::openConditionPicker);
        conditionTable.setOnEditCondition(this::openConditionEditor);
        conditionTable.rebuild();
        addWidget(conditionTable);
    }

    // ------------------------------------------------------------------ 动作

    private void save() {
        // 把三张表的当前内容写回草稿
        draft.itemInputs.clear();
        draft.itemInputs.addAll(inputTable.items());
        draft.fluidInputs.clear();
        draft.fluidInputs.addAll(inputTable.fluids());
        draft.itemOutputs.clear();
        draft.itemOutputs.addAll(outputTable.items());
        draft.fluidOutputs.clear();
        draft.fluidOutputs.addAll(outputTable.fluids());
        draft.conditions.clear();
        draft.conditions.addAll(conditionTable.conditions());

        DraftStore.save(draft);
        index.putDraft(draft.copy());
        setStatus("§a已保存到 config/rew/drafts/ —— 执行 /rew save 后 /reload 生效");
    }

    private void toggleDisabled() {
        boolean now = !index.isDisabled(draft.id);
        index.setDisabled(draft.id, now);
        draft.disabled = now;
        setStatus(now
                ? "§c已禁用（移出加载，未删除）—— /rew save 后 /reload 生效"
                : "§a已恢复加载 —— /rew save 后 /reload 生效");
    }

    private void goBack() {
        UiOpenHelper.openFullScreen(new RewEditorScreen(), "配方编辑器");
    }

    private void openPicker(boolean inputSide, boolean fluid, ContentRef existing) {
        ContentTableWidget table = inputSide ? inputTable : outputTable;
        UiOpenHelper.openFullScreen(
                new IngredientPickerScreen(fluid, existing, picked -> {
                    if (existing != null) table.replaceContent(existing, picked);
                    else if (fluid) table.addFluid(picked);
                    else table.addItem(picked);
                    table.rebuild();
                    UiOpenHelper.openFullScreen(this, "编辑 " + draft.id);
                }, () -> UiOpenHelper.openFullScreen(this, "编辑 " + draft.id)),
                "选择" + (fluid ? "流体" : "物品"));
    }

    private void openConditionPicker() {
        UiOpenHelper.openFullScreen(
                new ConditionPickerScreen(picked -> {
                    conditionTable.addCondition(picked);
                    UiOpenHelper.openFullScreen(this, "编辑 " + draft.id);
                }, () -> UiOpenHelper.openFullScreen(this, "编辑 " + draft.id)),
                "选择条件类型");
    }

    private void openConditionEditor(ConditionRef ref) {
        UiOpenHelper.openFullScreen(
                new ConditionEditorScreen(ref, edited -> {
                    conditionTable.replaceCondition(ref, edited);
                    backToEditor();
                }, this::backToEditor),
                "编辑条件");
    }

    private void backToEditor() {
        UiOpenHelper.openFullScreen(this, "编辑 " + draft.id);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ------------------------------------------------------------------ 小工具

    private WidgetGroup makeButton(int x, int y, int w, int h, String label, Runnable onClick) {
        WidgetGroup g = new WidgetGroup(x, y, w, h);
        ButtonWidget btn = new ButtonWidget(0, 0, w, h, cd -> onClick.run());
        btn.setButtonTexture(RewTextures.button());
        g.addWidget(btn);
        g.addWidget(new LabelWidget(4, 4, label));
        return g;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
