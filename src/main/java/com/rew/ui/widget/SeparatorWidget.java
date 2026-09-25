package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 分隔线：一条纯色细线，用来把界面的区域分开。
 *
 * <p>宽大于高时画横线，高大于宽时画竖线。颜色固定为半透明白，
 * 在深色面板上足够显眼，又不抢内容的视觉重心。
 */
public class SeparatorWidget extends WidgetGroup {

    private static final int COLOR = 0x99FFFFFF;

    public SeparatorWidget(int x, int y, int width, int height) {
        super(x, y, Math.max(1, width), Math.max(1, height));
    }

    /** 横线。 */
    public static SeparatorWidget horizontal(int x, int y, int length) {
        return new SeparatorWidget(x, y, length, 1);
    }

    /** 竖线。 */
    public static SeparatorWidget vertical(int x, int y, int length) {
        return new SeparatorWidget(x, y, 1, length);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = getPosition().x;
        int y = getPosition().y;
        graphics.fill(x, y, x + getSize().width, y + getSize().height, COLOR);
    }
}
