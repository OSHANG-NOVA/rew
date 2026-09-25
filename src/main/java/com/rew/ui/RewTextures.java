package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;

/**
 * 界面贴图集中定义。
 *
 * <p>本工具刻意不引入任何 PNG 资源：所有视觉元素都用 LDLib 的纯色/描边纹理拼出来。
 * 好处是模组体积为零、不挑资源包、也不会因为玩家装了材质包而变形；
 * 对一个「作者用的开发工具」来说，功能可读性远比皮肤重要。
 */
public final class RewTextures {

    private RewTextures() {}

    /** 面板底色（深灰半透明）。 */
    public static IGuiTexture panel() {
        return new ColorRectTexture(0xCC1A1A1A);
    }

    /** 次级面板底色。 */
    public static IGuiTexture subPanel() {
        return new ColorRectTexture(0xAA101010);
    }

    /** 按钮底色（浅灰，悬停变亮由 LDLib 的 ButtonWidget 自行处理）。 */
    public static IGuiTexture button() {
        return new ColorRectTexture(0x66FFFFFF);
    }

    /** 完全透明的贴图：用于需要点击区域但不想显示底色的按钮。 */
    public static IGuiTexture transparent() {
        return new ColorRectTexture(0x00000000);
    }

    /** 分隔线。 */
    public static IGuiTexture divider() {
        return new ColorRectTexture(0x44FFFFFF);
    }

    /** 滚动条轨道（比背景略深，让滚动条有落点）。 */
    public static IGuiTexture scrollTrack() {
        return new ColorRectTexture(0x33000000);
    }

    /** 滚动条滑块（亮灰，和按钮同色系，一眼能看出可拖）。 */
    public static IGuiTexture scrollBar() {
        return new ColorRectTexture(0x99AAAAAA);
    }

    /** 右键菜单底板：比面板更实，浮在内容之上要能看清。 */
    public static IGuiTexture menuBackground() {
        return new ColorBorderTexture(1, 0xFF888888);
    }

    /** 右键菜单项悬停底色。 */
    public static IGuiTexture menuHover() {
        return new ColorRectTexture(0x66FFFFFF);
    }

    /** 主面板描边。 */
    public static IGuiTexture panelBorder() {
        return new ColorBorderTexture(1, 0xFF555555);
    }
}
