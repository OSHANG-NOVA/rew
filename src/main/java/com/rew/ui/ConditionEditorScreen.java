package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.rew.data.ConditionRef;
import com.rew.i18n.RewLang;

import java.util.function.Consumer;

/**
 * 条件编辑器 —— 直接改条件的 JSON。
 *
 * <p>为什么给作者看 JSON 而不是做表单：GT 的条件体系是开放注册的，不同条件类型的字段
 * 差异极大（维度是资源 ID、research 是一串研究项、adjacentBlocks 是方块集合…）。
 * 逐个类型做表单需要跟着 GT 与所有附属模组升级，维护成本无限大。
 * 而条件 JSON 本身就是 GT 的公开序列化格式（{@code RecipeCondition#serialize}），
 * 提供「原样可编辑 + reverse 开关」已经覆盖全部用例，且永不失效。
 */
public class ConditionEditorScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int FIELD_H = 14;

    private final ConditionRef original;
    private final Consumer<ConditionRef> onSaved;
    private final Runnable onCancel;

    private final TextFieldWidget jsonField;
    private boolean reverse;

    public ConditionEditorScreen(ConditionRef ref, Consumer<ConditionRef> onSaved, Runnable onCancel) {
        super(0, 0, 0, 0);
        setSize(360, 200);
        setBackground(RewTextures.panel());

        this.original = ref;
        this.onSaved = onSaved;
        this.onCancel = onCancel;
        this.reverse = ref.reverse;

        int w = 360;
        int h = 200;

        String name = RewLang.conditionName(ref.type);
        String title = name.equals(ref.type) ? ref.type : name + " §8" + ref.type;
        addWidget(new LabelWidget(MARGIN, MARGIN, "§b编辑条件 §f" + title));

        int y = MARGIN + 16;
        addWidget(new LabelWidget(MARGIN, y, "§7JSON（GT 原生序列化格式）"));
        y += 12;

        jsonField = new TextFieldWidget(MARGIN, y, w - MARGIN * 2, h - y - 44,
                () -> ref.json == null ? "{}" : ref.json,
                v -> ref.json = v);
        jsonField.setClientSideWidget();
        jsonField.setHoverTooltips("直接编辑条件的 JSON；改坏了还原时会跳过该条件并打日志");
        addWidget(jsonField);

        // reverse 开关：GT 的「条件取反」，语义重要但不好在 JSON 里直观发现，单列一个按钮。
        int by = h - 38;
        WidgetGroup revGroup = new WidgetGroup(MARGIN, by, 120, FIELD_H);
        var revBtn = new com.lowdragmc.lowdraglib.gui.widget.ButtonWidget(0, 0, 120, FIELD_H,
                cd -> {
                    reverse = !reverse;
                    original.reverse = reverse;
                });
        revBtn.setButtonTexture(RewTextures.button());
        revGroup.addWidget(revBtn);
        revGroup.addWidget(new LabelWidget(6, 3, "§7切换取反"));
        addWidget(revGroup);

        WidgetGroup saveGroup = new WidgetGroup(w - MARGIN - 50, by, 50, FIELD_H);
        var saveBtn = new com.lowdragmc.lowdraglib.gui.widget.ButtonWidget(0, 0, 50, FIELD_H,
                cd -> save());
        saveBtn.setButtonTexture(RewTextures.button());
        saveGroup.addWidget(saveBtn);
        saveGroup.addWidget(new LabelWidget(6, 3, "§a保存"));
        addWidget(saveGroup);

        WidgetGroup cancelGroup = new WidgetGroup(w - MARGIN - 50 - 54, by, 50, FIELD_H);
        var cancelBtn = new com.lowdragmc.lowdraglib.gui.widget.ButtonWidget(0, 0, 50, FIELD_H,
                cd -> onCancel.run());
        cancelBtn.setButtonTexture(RewTextures.button());
        cancelGroup.addWidget(cancelBtn);
        cancelGroup.addWidget(new LabelWidget(6, 3, "§7取消"));
        addWidget(cancelGroup);
    }

    private void save() {
        String text = jsonField.getCurrentString();
        if (text == null || text.isBlank()) text = "{}";
        original.json = text;
        original.reverse = reverse;
        // 描述是按 JSON 生成的，改完必须清掉缓存，否则列表还显示旧的中文。
        original.invalidateText();
        onSaved.accept(original);
    }
}
