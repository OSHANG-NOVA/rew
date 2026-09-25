package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量右键弹出菜单。
 *
 * <p>LDLib 自带的 {@code MenuWidget} 是树形结构菜单（节点可展开），用于「一层层往下选」；
 * 而本工具只需要「点右键 → 弹出几个动作」这种最简单的列表菜单，自己画更直接：
 * 一个半透明底 + 若干行文字，鼠标移上去高亮，点空白处关闭。
 *
 * <p>用法上刻意做成**常驻实例 + 显示/隐藏**，而不是每次右键临时 new 一个塞进控件树：
 * 菜单是在鼠标点击的分发过程中被打开的，此时父容器正在遍历子控件列表，
 * 增删控件会破坏这次遍历。常驻一个隐藏实例，右键时只改内容与位置，就没有这个问题。
 *
 * <p>它必须被 add 到控件树的**末尾**：LDLib 从后往前分发点击，排在最后才能最先收到点击，
 * 从而盖住下层列表；点到菜单外时自己关闭并吞掉这次点击，避免误触下层行。
 */
public class PopupMenuWidget extends WidgetGroup {

    /** 菜单项行高。 */
    private static final int ITEM_H = 14;
    /** 左右内边距。 */
    private static final int PAD_X = 6;
    /** 上下内边距。 */
    private static final int PAD_Y = 3;

    /** 一个菜单项：文字 + 点击动作。 */
    public static final class Item {

        final String label;
        final Runnable action;

        public Item(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int hoveredIndex = -1;

    public PopupMenuWidget() {
        super(0, 0, 0, 0);
        setVisible(false);
    }

    /**
     * 在指定位置弹出菜单。
     *
     * @param screenW 所在界面的宽度，用于把菜单夹在屏幕内，避免贴边时溢出
     * @param screenH 所在界面的高度，同上
     */
    public void show(int x, int y, List<Item> newItems, int screenW, int screenH) {
        this.items.clear();
        this.items.addAll(newItems);
        this.hoveredIndex = -1;

        var font = Minecraft.getInstance().font;
        int textWidth = 0;
        for (Item item : items) {
            textWidth = Math.max(textWidth, font.width(plain(item.label)));
        }
        int w = textWidth + PAD_X * 2;
        int h = items.size() * ITEM_H + PAD_Y * 2;

        // 贴边时往回挪，保证整个菜单都在屏幕内。
        x = Math.max(0, Math.min(x, Math.max(0, screenW - w)));
        y = Math.max(0, Math.min(y, Math.max(0, screenH - h)));

        setSelfPosition(x, y);
        setSize(w, h);
        setVisible(true);
    }

    /** 收起菜单。 */
    public void hide() {
        setVisible(false);
        hoveredIndex = -1;
        items.clear();
    }

    public boolean isOpen() {
        return isVisible();
    }

    /** 去掉颜色控制符，用于量文字宽度（§ 后面的字符不占显示宽度）。 */
    private static String plain(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!isVisible()) return;

        int x = getPosition().x;
        int y = getPosition().y;
        int w = getSize().width;
        int h = getSize().height;

        // 比面板更实的底色，浮在内容之上要能看清。
        graphics.fill(x, y, x + w, y + h, 0xF0202020);
        new ColorBorderTexture(1, 0xFF888888).draw(graphics, mouseX, mouseY, x, y, w, h);

        var font = Minecraft.getInstance().font;
        for (int i = 0; i < items.size(); i++) {
            int iy = y + PAD_Y + i * ITEM_H;
            boolean hover = i == hoveredIndex;
            if (hover) {
                graphics.fill(x + 1, iy, x + w - 1, iy + ITEM_H, 0x5544AAFF);
            }
            graphics.drawString(font, items.get(i).label, x + PAD_X, iy + 3,
                    hover ? 0xFFFFFF : 0xDDDDDD, false);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!isVisible()) return;
        var mc = Minecraft.getInstance();
        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        hoveredIndex = indexAt(mouseX, mouseY);
    }

    /** 命中的菜单项下标；不在任何一项上返回 -1。 */
    private int indexAt(double mouseX, double mouseY) {
        if (!isMouseOverElement(mouseX, mouseY)) return -1;
        int rel = (int) (mouseY - getPosition().y) - PAD_Y;
        if (rel < 0) return -1;
        int index = rel / ITEM_H;
        return index >= 0 && index < items.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isVisible()) return false;

        int index = indexAt(mouseX, mouseY);
        if (index >= 0) {
            Runnable action = items.get(index).action;
            hide();
            if (action != null) action.run();
            return true;
        }
        // 点在菜单外：关掉自己并吞掉这次点击，避免顺手触发了下层的行。
        hide();
        return true;
    }
}
