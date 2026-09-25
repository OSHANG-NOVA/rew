package com.rew.client;

import com.rew.RewMod;
import com.rew.command.RewCommands;
import com.rew.data.RecipeIndex;
import com.rew.i18n.RewLang;
import com.rew.ui.GtmBrowserScreen;
import com.rew.ui.RewEditorScreen;
import com.rew.ui.UiOpenHelper;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端入口：注册 {@code /rew} 指令族，并把「打开界面」这个动作接进指令层。
 *
 * <p>为什么不直接在指令里 new 界面：{@code RewCommands} 位于 common 包，
 * 服务端也会加载它。把客户端类隔在这里，服务端就永远不会碰 UI 类，避免专用服务器
 * 因缺客户端类而抛 {@code NoClassDefFoundError}。
 */
@Mod.EventBusSubscriber(modid = RewMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RewClientEvents {

    private RewClientEvents() {}

    static {
        RewCommands.setOpenEditor(RewClientEvents::openEditor);
        RewCommands.setOpenGtmBrowser(RewClientEvents::openGtmBrowser);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        RewCommands.register(event.getDispatcher());
    }

    /**
     * 打开主编辑器。
     *
     * <p>首次打开时若还没有索引数据，先做一次装载（优先用磁盘缓存，因此通常很快）；
     * 这一步放在 UI 打开之前，避免界面里出现半空列表。
     */
    public static boolean openEditor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        ensureLoaded();
        // 界面类是 WidgetGroup（控件树），必须经 UiOpenHelper 用 ModularUIGuiContainer 包成屏幕。
        UiOpenHelper.openFullScreen(new RewEditorScreen(), "配方编辑器");
        return true;
    }

    /** 打开 GT 配方总览（多选 + Del 禁用）。 */
    public static boolean openGtmBrowser() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        ensureLoaded();
        UiOpenHelper.openFullScreen(new GtmBrowserScreen(), "GT 配方总览");
        return true;
    }

    private static void ensureLoaded() {
        RecipeIndex index = RecipeIndex.get();
        if (!index.hasSnapshot()) {
            index.load(false);
            // 对照表只在首次装载时对一遍：GT 升级改了条件注册名时，日志里能直接看到。
            var missing = RewLang.auditGtConditions();
            if (!missing.isEmpty()) {
                RewMod.LOGGER.warn("[{}] 以下 GT 条件没有中文对照，将显示注册名：{}",
                        RewMod.MOD_ID, missing);
            }
        }
    }
}
