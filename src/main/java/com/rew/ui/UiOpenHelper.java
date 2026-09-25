package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import com.rew.RewMod;

/**
 * LDLib 界面的打开辅助。
 *
 * <p>本工具的全部界面都是**纯客户端**的：没有方块实体、没有真实背包槽位，
 * 因此统一用 {@link IUIHolder#EMPTY} 作为 holder，直接在客户端 setScreen。
 * 这与 LDLib 自带测试指令（{@code ClientCommands}）的做法一致，是与框架作者同源的用法。
 *
 * <p>注意两件事：
 * <ol>
 * <li>界面类自己是 {@link WidgetGroup}（LDLib 的控件树），**不是** {@code Screen}。
 * 真正能 setScreen 的是 {@link ModularUIGuiContainer}，由它把控件树包成屏幕 ——
 * 所有打开动作都必须经过本类，不要直接 new 界面去 setScreen。</li>
 * <li>刻意不注册 {@code UIFactory}。UIFactory 是「服务端发起、封包通知客户端打开」的通路，
 * 会引入服务端对 UI 类的依赖；而本编辑器不需要服务端参与。</li>
 * </ol>
 */
public final class UiOpenHelper {

    private UiOpenHelper() {}

    /** 用给定根 WidgetGroup 打开一个全屏 LDLib 界面。 */
    public static void openFullScreen(WidgetGroup root, String title) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            RewMod.LOGGER.warn("[{}] 玩家为空，无法打开界面 {}", RewMod.MOD_ID, title);
            return;
        }
        try {
            // ModularUI(holder, player) 这个重载即全屏模式（fullScreen = true）。
            ModularUI ui = new ModularUI(IUIHolder.EMPTY, player);

            // 全屏模式下 mainGroup 的尺寸会在 initWidgets 里被拉到屏幕大小；
            // 先把根控件也撑满，避免子控件按 (0,0) 尺寸布局而全部叠在左上角。
            root.setSelfPosition(0, 0);
            root.setSize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            ui.mainGroup.addWidget(root);

            ui.initWidgets();

            ModularUIGuiContainer container = new ModularUIGuiContainer(ui, player.containerMenu.containerId);
            mc.setScreen(container);
            player.containerMenu = container.getMenu();
        } catch (Throwable t) {
            RewMod.LOGGER.error("[{}] 打开界面 {} 失败", RewMod.MOD_ID, title, t);
        }
    }

    /** 语义别名：从列表进详情、从详情返回，都是「换一整屏」。 */
    public static void openFullScreenReplacing(WidgetGroup root, String title) {
        openFullScreen(root, title);
    }
}
