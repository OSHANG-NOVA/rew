package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.rew.data.ConditionRef;
import com.rew.ui.RewTextures;

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
 *
 * <p>与输入/输出表一致，条件行也放在带裁剪的滚动视口里：条件多的配方
 * （多方块 + 超净间 + 研究 + 维度限制）会把行画到框外，看不到也点不到。
 */
public class ConditionTableWidget extends WidgetGroup {

    private static final int ROW_H = 18;
    private static final int HEADER_H = 12;
    private static final int ADD_BTN_H = 14;
    /** 滚动条宽度；LDLib 默认 0 表示不绘制，必须显式设置。 */
    private static final int BAR_W = 4;

    private final List<ConditionRef> conditions = new ArrayList<>();

    /** 承载全部条件行的滚动视口；表头常驻在视口之外。 */
    private final DraggableScrollableWidgetGroup viewport;

    private Consumer<ConditionRef> onEditCondition = null;
    private Runnable onAddCondition = null;

    public ConditionTableWidget(int x, int y, int width, int height,
                                List<ConditionRef> initial) {
        super(x, y, width, height);
        if (initial != null) conditions.addAll(initial);

        addWidget(new LabelWidget(2, 1, "§f条件"));

        int viewH = Math.max(0, height - HEADER_H);
        this.viewport = new DraggableScrollableWidgetGroup(0, HEADER_H, width, viewH);
        this.viewport.setYScrollBarWidth(BAR_W);
        this.viewport.setYBarStyle(RewTextures.scrollTrack(), RewTextures.scrollBar());
        addWidget(this.viewport);
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

    /** 可供行使用的净宽：让出滚动条，避免删除按钮被压在滚动条下面。 */
    private int contentWidth() {
        return Math.max(40, getSize().width - BAR_W - 2);
    }

    /**
     * 全量重建视口内容。
     *
     * <p>只清视口、不动表头；重建前记住滚动位置，避免每次增删都跳回顶部。
     */
    public void rebuild() {
        int keepScroll = viewport.getScrollYOffset();
        viewport.clearAllWidgets();

        int y = 0;
        viewport.addWidget(new LabelWidget(2, y, "§7共 " + conditions.size() + " 条"));

        if (conditions.isEmpty()) {
            viewport.addWidget(new LabelWidget(6, y + HEADER_H, "§8（无附加条件）"));
            y += HEADER_H + ROW_H;
        } else {
            y += HEADER_H;
        }

        int rowW = contentWidth();
        for (ConditionRef ref : conditions) {
            WidgetGroup row = new WidgetGroup(0, y, rowW, ROW_H);
            row.addWidget(new LabelWidget(6, 4, "§f" + ref.display()));

            ButtonWidget editBtn = new ButtonWidget(2, 1, rowW - 26, ROW_H - 2,
                    cd -> {
                        if (onEditCondition != null) onEditCondition.accept(ref);
                    });
            editBtn.setButtonTexture(RewTextures.transparent());
            editBtn.setHoverTooltips("点击编辑该条件的 JSON");
            row.addWidget(editBtn);

            ButtonWidget delBtn = new ButtonWidget(rowW - 18, 1, 16, ROW_H - 2,
                    cd -> removeCondition(ref));
            delBtn.setButtonTexture(RewTextures.button());
            delBtn.setHoverTooltips("§c删除该条件");
            row.addWidget(delBtn);
            row.addWidget(new LabelWidget(rowW - 15, 3, "§c×"));

            viewport.addWidget(row);
            y += ROW_H;
        }

        ButtonWidget addBtn = new ButtonWidget(2, y, rowW - 4, ADD_BTN_H,
                cd -> {
                    if (onAddCondition != null) onAddCondition.run();
                });
        addBtn.setButtonTexture(RewTextures.button());
        addBtn.setHoverTooltips("添加条件（从 GT 已注册的条件类型里选）");
        viewport.addWidget(addBtn);
        viewport.addWidget(new LabelWidget(6, y + 3, "§a+ 添加条件"));

        int bottom = viewport.getWidgetBottomHeight();
        int maxScroll = Math.max(0, bottom - viewport.getSize().height);
        viewport.setScrollYOffset(Math.max(0, Math.min(keepScroll, maxScroll)));
    }
}
