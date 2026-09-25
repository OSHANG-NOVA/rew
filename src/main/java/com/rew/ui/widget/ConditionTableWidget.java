package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.rew.data.ConditionRef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 条件表控件 —— 编辑器下方那张表。
 *
 * <p>设计取巧之处：本工具**不枚举条件种类**。GT 的条件体系是开放注册的
 * （{@code GTRegistries.RECIPE_CONDITIONS}），任何模组都能加自己的条件类型；
 * 因此这里只做「列出 / 删除 / 编辑 JSON」三件事，重建时把 JSON 原样交给
 * {@code RecipeCondition#deserialize}，凡是注入 GT 的条件都自动支持，
 * 不需要为新条件类型改一行代码。
 *
 * <p>「+ 添加条件」的做法：列出注册表里全部条件类型，选中后创建一个空模板
 * （{@code RecipeConditionType#factory.createDefault()}），再让作者编辑其 JSON。
 */
public class ConditionTableWidget extends WidgetGroup {

    private static final int ROW_H = 18;
    private static final int HEADER_H = 12;
    private static final int ADD_BTN_H = 14;

    private final List<ConditionRef> conditions = new ArrayList<>();

    private Consumer<ConditionRef> onEditCondition = null;
    private Runnable onAddCondition = null;

    public ConditionTableWidget(int x, int y, int width, int height,
                                List<ConditionRef> initial) {
        super(x, y, width, height);
        if (initial != null) conditions.addAll(initial);
    }

    public ConditionTableWidget setOnEditCondition(Consumer<ConditionRef> handler) {
        this.onEditCondition = handler;
        return this;
    }

    public ConditionTableWidget setOnAddCondition(Runnable handler) {
        this.onAddCondition = handler;
        return this;
    }

    public List<ConditionRef> conditions() {
        return conditions;
    }

    public void addCondition(ConditionRef ref) {
        conditions.add(ref);
        rebuild();
    }

    public void removeCondition(ConditionRef ref) {
        conditions.remove(ref);
        rebuild();
    }

    public void replaceCondition(ConditionRef oldRef, ConditionRef newRef) {
        int idx = conditions.indexOf(oldRef);
        if (idx >= 0) {
            conditions.set(idx, newRef);
        }
        rebuild();
    }

    public void rebuild() {
        clearAllWidgets();

        int y = 0;
        addWidget(new LabelWidget(0, y, "§f条件 §8(" + conditions.size() + ")"));
        y += HEADER_H;

        if (conditions.isEmpty()) {
            addWidget(new LabelWidget(6, y, "§8（无附加条件）"));
            y += ROW_H;
        }

        for (ConditionRef ref : conditions) {
            WidgetGroup row = new WidgetGroup(0, y, getSize().width, ROW_H);
            row.addWidget(new LabelWidget(6, 3, "§f" + ref.display()));

            ButtonWidget editBtn = new ButtonWidget(2, 1, getSize().width - 26, ROW_H - 2,
                    cd -> {
                        if (onEditCondition != null) onEditCondition.accept(ref);
                    });
            editBtn.setButtonTexture(com.rew.ui.RewTextures.transparent());
            editBtn.setHoverTooltips("点击编辑该条件的 JSON");
            row.addWidget(editBtn);

            ButtonWidget delBtn = new ButtonWidget(getSize().width - 18, 1, 16, ROW_H - 2,
                    cd -> removeCondition(ref));
            delBtn.setButtonTexture(com.rew.ui.RewTextures.button());
            delBtn.setHoverTooltips("§c删除该条件");
            row.addWidget(delBtn);
            row.addWidget(new LabelWidget(getSize().width - 15, 3, "§c×"));

            addWidget(row);
            y += ROW_H;
        }

        ButtonWidget addBtn = new ButtonWidget(2, y, getSize().width - 4, ADD_BTN_H,
                cd -> {
                    if (onAddCondition != null) onAddCondition.run();
                });
        addBtn.setButtonTexture(com.rew.ui.RewTextures.button());
        addBtn.setHoverTooltips("添加条件（从 GT 已注册的条件类型里选）");
        addWidget(addBtn);
        addWidget(new LabelWidget(6, y + 3, "§a+ 添加条件"));
    }
}
