package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * 列表行控件：一排可点选的文字行，带悬停/选中高亮。
 *
 * <p>为什么自己写而不是用 LDLib 现成的：LDLib 只提供 {@code TreeListWidget}（树形），
 * 没有扁平的「多选列表」控件。本工具的三张表（输入/输出/条件）和两个浏览列表都需要
 * 「行级 hover + 选中态 + 多选」，所以抽出一个共享实现，避免四处重复。
 */
public class ListRowWidget extends WidgetGroup {

    /**
     * 行高。
     *
     * <p>带图标的行要容纳 16px 的物品贴图，所以统一抬到 18；纯文字行也跟着对齐，
     * 否则同一张列表里文字行和图标行会高低不齐。
     */
    public static final int ROW_HEIGHT = 18;

    /** 图标边长，与原版物品贴图一致。 */
    private static final int ICON = 16;

    private static final IGuiTexture BG_NORMAL = new ColorRectTexture(0x00000000);
    private static final IGuiTexture BG_HOVER = new ColorRectTexture(0x33FFFFFF);
    private static final IGuiTexture BG_SELECTED = new ColorRectTexture(0x5544AAFF);
    private static final IGuiTexture BG_DISABLED = new ColorRectTexture(0x33FF4444);

    private final String text;
    private final int textColor;
    /**
     * 行首图标。物品与流体桶走 {@link #icon}（画物品贴图），流体走 {@link #fluidIcon}
     * （画流体自己的静止贴图）。两者都空时退回纯文字布局。
     */
    private ItemStack icon = ItemStack.EMPTY;
    private FluidStack fluidIcon = FluidStack.EMPTY;
    private Runnable onLeftClick;
    private Runnable onRightClick;
    /** 需要知道点击坐标的右键回调（用于在鼠标处弹菜单）。 */
    private java.util.function.BiConsumer<Double, Double> onRightClickPos;

    private boolean selected = false;
    private boolean disabledLook = false;
    private boolean hovered = false;
    private String tooltip = null;

    public ListRowWidget(int x, int y, int width, String text, int textColor,
                         Runnable onLeftClick, Runnable onRightClick) {
        super(x, y, width, ROW_HEIGHT);
        this.text = text == null ? "" : text;
        this.textColor = textColor;
        this.onLeftClick = onLeftClick;
        this.onRightClick = onRightClick;
        setBackground(BG_NORMAL);
    }

    /** 设置左键回调。列表控件在构建完行之后才绑定回调，所以这里做成可变而非构造参数。 */
    public ListRowWidget setOnLeftClick(Runnable handler) {
        this.onLeftClick = handler;
        return this;
    }

    /** 设置右键回调。 */
    public ListRowWidget setOnRightClick(Runnable handler) {
        this.onRightClick = handler;
        return this;
    }

    public ListRowWidget setSelected(boolean value) {
        this.selected = value;
        refreshBackground();
        return this;
    }

    public boolean isSelectedRow() {
        return selected;
    }

    public ListRowWidget setDisabledLook(boolean value) {
        this.disabledLook = value;
        refreshBackground();
        return this;
    }

    public ListRowWidget setTooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** 设置行首的物品图标（物品、流体桶都走这里）。传空栈或 null 等于不显示。 */
    public ListRowWidget setIcon(ItemStack icon) {
        this.icon = icon == null ? ItemStack.EMPTY : icon;
        this.fluidIcon = FluidStack.EMPTY;
        return this;
    }

    /** 设置行首的流体图标，画的是流体自己的贴图而不是桶。 */
    public ListRowWidget setFluidIcon(FluidStack fluid) {
        this.fluidIcon = fluid == null ? FluidStack.EMPTY : fluid;
        this.icon = ItemStack.EMPTY;
        return this;
    }

    public String text() {
        return text;
    }

    private void refreshBackground() {
        if (selected) setBackground(BG_SELECTED);
        else if (disabledLook) setBackground(BG_DISABLED);
        else setBackground(BG_NORMAL);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = getPosition().x;
        int y = getPosition().y;
        int w = getSize().width;
        int h = getSize().height;

        graphics.fill(x, y, x + w, y + h, currentFill());

        // 图标占左侧 16px，文字让出位置；没有图标时文字仍从左边起。
        int textX = x + 3;
        boolean hasIcon = !icon.isEmpty() || !fluidIcon.isEmpty();
        if (hasIcon) {
            int iconX = x + 1;
            int iconY = y + (h - ICON) / 2;
            // 抬高 z，避免被行背景盖住。流体走 LDLib 的流体绘制（绑定方块图集、按 16px 平铺），
            // 和 GT 流体槽同一条路径；直接拿精灵画只会露出贴图的一角。
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 100);
            if (!fluidIcon.isEmpty()) {
                DrawerHelper.drawFluidForGui(graphics,
                        com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                                fluidIcon.getFluid(), fluidIcon.getAmount()),
                        iconX, iconY, ICON, ICON);
            } else {
                DrawerHelper.drawItemStack(graphics, icon, iconX, iconY, -1, null);
            }
            graphics.pose().popPose();
            textX = x + ICON + 3;
        }

        String shown = ellipsize(text, w - (textX - x) - 3);
        graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                shown, textX, y + (h - 8) / 2, textColor, false);
    }

    private int currentFill() {
        if (selected) return 0x5544AAFF;
        if (disabledLook) return 0x33FF4444;
        if (hovered) return 0x33FFFFFF;
        return 0x00000000;
    }

    /** 超宽文本截断，避免长 ID 溢出列表。 */
    private String ellipsize(String s, int maxWidth) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "…";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (char c : s.toCharArray()) {
            int cw = font.width(String.valueOf(c));
            if (w + cw + ellipsisWidth > maxWidth) break;
            sb.append(c);
            w += cw;
        }
        return sb + ellipsis;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        if (button == 0) {
            if (onLeftClick != null) onLeftClick.run();
            return true;
        }
        if (button == 1) {
            if (onRightClick != null) onRightClick.run();
            return true;
        }
        return false;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
    }

    /** LDLib 的 hover 事件在这条链上不一定触发，这里主动算一次，保证高亮实时。 */
    public void refreshHover(double mouseX, double mouseY) {
        boolean now = isMouseOverElement(mouseX, mouseY);
        if (now != hovered) {
            hovered = now;
        }
    }
}
