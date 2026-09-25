package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.rew.ui.RewTextures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 通用滚动列表：三张表与两个浏览列表共用同一套「重建 + 滚动 + 选中」逻辑。
 *
 * <p>刻意采用「全量重建」而不是增量更新：单表最多几十行、配方列表最多几千行，
 * 重建成本远低于维护增量 diff 的复杂度与出错风险。真正的大列表（配方浏览）
 * 由调用方先做搜索过滤，落到这里的行数已被压到可接受范围。
 */
public class ScrollListWidget<T> extends WidgetGroup {

    /** 滚动条宽度；不需要滚动时置 0，滚动条就不画。 */
    private static final int BAR_W = 4;

    private final DraggableScrollableWidgetGroup viewport;
    private final List<RowHandle<T>> rows = new ArrayList<>();
    private Consumer<T> onClick = null;
    private Consumer<T> onRightClick = null;

    /** 当前内容的实际总高度，用于把滚动位置夹在合法范围内。 */
    private int contentHeight = 0;

    /** 每行数据 + 其控件，便于做选中态刷新。 */
    public static final class RowHandle<T> {

        public final T data;
        public final ListRowWidget widget;

        RowHandle(T data, ListRowWidget widget) {
            this.data = data;
            this.widget = widget;
        }
    }

    public ScrollListWidget(int x, int y, int width, int height) {
        super(x, y, width, height);
        this.viewport = new DraggableScrollableWidgetGroup(0, 0, width, height);
        // 给列表配上可拖的滚动条：内容超出一屏时右侧出现，不超出时自动隐藏。
        // 宽度必须显式设置，否则 LDLib 默认 0，滚动条不会绘制。
        viewport.setYScrollBarWidth(BAR_W);
        viewport.setYBarStyle(RewTextures.scrollTrack(), RewTextures.scrollBar());
        addWidget(this.viewport);
    }

    /** 当前滚动位置（像素）。 */
    public int getScrollYOffset() {
        return viewport.getScrollYOffset();
    }

    /** 恢复到指定滚动位置，超出内容范围时自动夹回。 */
    public void setScrollYOffset(int offset) {
        int max = Math.max(0, contentHeight - getSize().height);
        viewport.setScrollYOffset(Math.max(0, Math.min(offset, max)));
    }

    /** 回到顶部。切换配方类型时用，避免沿用上一个类型的滚动位置。 */
    public void resetScroll() {
        viewport.setScrollYOffset(0);
    }

    public ScrollListWidget<T> setOnClick(Consumer<T> handler) {
        this.onClick = handler;
        return this;
    }

    public ScrollListWidget<T> setOnRightClick(Consumer<T> handler) {
        this.onRightClick = handler;
        return this;
    }

    public void setRows(List<RowSpec<T>> specs) {
        // 重建前先记下滚动位置：clearAllWidgets 会把它归零，不记就会「每次操作跳回顶部」。
        int keepScroll = viewport.getScrollYOffset();
        viewport.clearAllWidgets();
        rows.clear();
        int y = 0;
        for (RowSpec<T> spec : specs) {
            ListRowWidget row = new ListRowWidget(
                    0, y, getSize().width - 8, spec.text, spec.color, null, null);
            row.setSelected(spec.selected);
            row.setDisabledLook(spec.disabledLook);
            if (spec.tooltip != null) row.setTooltip(spec.tooltip);
            if (spec.fluidIcon != null && !spec.fluidIcon.isEmpty()) row.setFluidIcon(spec.fluidIcon);
            else if (spec.icon != null && !spec.icon.isEmpty()) row.setIcon(spec.icon);

            final T data = spec.data;
            row.setOnLeftClick(() -> {
                if (onClick != null) onClick.accept(data);
            });
            row.setOnRightClick(() -> {
                if (onRightClick != null) onRightClick.accept(data);
            });

            viewport.addWidget(row);
            rows.add(new RowHandle<>(data, row));
            y += ListRowWidget.ROW_HEIGHT + 1;
        }
        contentHeight = y;
        // 内容变短时夹回合法范围，否则保持原位置 —— 这样「选中一项后列表不跳」。
        setScrollYOffset(keepScroll);
    }

    /** 行描述：把「显示什么」与「数据是什么」分开，避免控件反向依赖数据类。 */
    public static final class RowSpec<T> {

        public final T data;
        public final String text;
        public final int color;
        public boolean selected = false;
        public boolean disabledLook = false;
        public String tooltip = null;
        /** 物品图标（物品、流体桶）。 */
        public ItemStack icon = ItemStack.EMPTY;
        /** 流体图标，画流体自己的贴图，优先于 {@link #icon}。 */
        public FluidStack fluidIcon = FluidStack.EMPTY;

        public RowSpec(T data, String text, int color) {
            this.data = data;
            this.text = text;
            this.color = color;
        }

        public RowSpec<T> selected(boolean v) {
            this.selected = v;
            return this;
        }

        public RowSpec<T> disabledLook(boolean v) {
            this.disabledLook = v;
            return this;
        }

        public RowSpec<T> tooltip(String v) {
            this.tooltip = v;
            return this;
        }

        /** 行首的物品图标（物品、流体桶），空栈表示不显示。 */
        public RowSpec<T> icon(ItemStack v) {
            this.icon = v == null ? ItemStack.EMPTY : v;
            return this;
        }

        /** 行首的流体图标，画流体自己的贴图。 */
        public RowSpec<T> fluidIcon(FluidStack v) {
            this.fluidIcon = v == null ? FluidStack.EMPTY : v;
            return this;
        }
    }

    public List<RowHandle<T>> rows() {
        return rows;
    }

    public DraggableScrollableWidgetGroup viewport() {
        return viewport;
    }

    /** 只刷新选中态，不重建控件（多选时高频调用，省开销）。 */
    public void refreshSelection(java.util.function.Predicate<T> isSelected) {
        for (RowHandle<T> h : rows) {
            h.widget.setSelected(isSelected.test(h.data));
        }
    }
}
