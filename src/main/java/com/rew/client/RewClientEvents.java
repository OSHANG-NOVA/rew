package com.rew.client;

import com.rew.RewMod;
import com.rew.command.RewCommands;
import com.rew.data.GtRecipeInjector;
import com.rew.data.RecipeIndex;
import com.rew.i18n.RewLang;
import com.rew.ui.GtmBrowserScreen;
import com.rew.ui.RewEditorScreen;
import com.rew.ui.UiOpenHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
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

    /**
     * {@code /rew reload} 触发了服务端重载后置位；等客户端真正收到新配方包
     * （{@link RecipesUpdatedEvent}）时再做一次全量重扫。
     *
     * <p>为什么不在指令里直接重扫：服务端重载是异步的，指令返回时新配方往往还没同步到
     * 客户端，立刻重扫只会扫到旧数据。用这个标记把重扫推迟到「配方真的到了」那一刻。
     */
    private static volatile boolean rescanOnRecipesUpdated = false;

    static {
        RewCommands.setOpenEditor(RewClientEvents::openEditor);
        RewCommands.setOpenGtmBrowser(RewClientEvents::openGtmBrowser);
        RewCommands.setServerReload(RewClientEvents::reloadServer);
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

    /**
     * 重建 GT 动态配方包并让集成服务器重载资源 —— {@code /rew reload} 的实体。
     *
     * <p>两步都在**服务端线程**上做，顺序不能颠倒：
     * <ol>
     * <li>{@code GtRecipeInjector.regenerate()}：刷新 {@code GTRecipes.RECIPE_FILTERS} 与
     * {@code GTDynamicDataPack.CONTENTS}。</li>
     * <li>{@code reloadResources}：构造 {@code MultiPackResourceManager} 时会**急切**读一次
     * 数据包的 filter 段（见其构造函数的 {@code getPackFilterSection}），
     * 所以第 1 步必须先做完，新禁用的配方才会被真正拦掉。</li>
     * </ol>
     *
     * <p>只对集成服务器（单人 / 局域网）有效：专用服务器的 {@code CommandSourceStack}
     * 在客户端是取不到服务端实例的（{@code ClientCommandSourceStack#getServer} 直接抛异常）。
     *
     * <p>内容没变时**整轮跳过**（连服务端重载都不发）：重跑 GT 配方生成实测约 15.6 s，
     * 作者连按两次 {@code /rew reload} 不该白等两次。指纹由 {@link GtRecipeInjector} 维护。
     *
     * @return {@link RewCommands#RELOAD_TRIGGERED} / {@link RewCommands#RELOAD_UNCHANGED}
     *         / {@link RewCommands#RELOAD_UNAVAILABLE}
     */
    public static int reloadServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return RewCommands.RELOAD_UNAVAILABLE;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return RewCommands.RELOAD_UNAVAILABLE;

        // 指纹比对放在客户端线程：读几个小文件，代价可忽略，
        // 而且能立刻告诉作者「没变化」，不必等服务端线程跑完再说。
        if (!GtRecipeInjector.hasChanged()) return RewCommands.RELOAD_UNCHANGED;

        rescanOnRecipesUpdated = true;
        // 交给服务端线程执行：GT 的配方生成链（recipeAddition）本来就在服务端线程上跑，
        // 从渲染线程直接调用会和机器 tick / 配方查询抢静态状态。
        server.execute(() -> {
            try {
                // 服务端线程上再判一次：客户端线程判断与此处之间作者可能又改了文件。
                if (!GtRecipeInjector.regenerateIfChanged()) {
                    rescanOnRecipesUpdated = false;
                    return;
                }
                server.reloadResources(server.getPackRepository().getSelectedIds());
            } catch (Throwable t) {
                rescanOnRecipesUpdated = false;
                RewMod.LOGGER.error("[{}] 重建 GT 配方包 / 触发重载失败", RewMod.MOD_ID, t);
            }
        });
        return RewCommands.RELOAD_TRIGGERED;
    }

    /**
     * 客户端收到服务端同步来的新配方表 —— 此时重扫才是「看得见新配方」的时机。
     *
     * <p>只在 {@code /rew reload} 主动触发过之后才重扫：这个事件在每次进服/切维度时都会响，
     * 无条件全量重扫会让进游戏变得很慢。
     */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        if (!rescanOnRecipesUpdated) return;
        rescanOnRecipesUpdated = false;

        RecipeIndex index = RecipeIndex.get();
        index.load(true);
        RewMod.LOGGER.info("[{}] 服务端配方已同步，客户端快照已刷新：{} 个类型 / {} 条配方",
                RewMod.MOD_ID, index.totalTypes(), index.totalRecipes());

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                            "[rew] 配方已更新：" + index.totalRecipes() + " 条（重扫 "
                                    + index.lastScanMillis() + " ms）")
                    .withStyle(ChatFormatting.GREEN));
            mc.player.sendSystemMessage(Component.literal(
                            "[rew] 执行 /rew 打开编辑器查看")
                    .withStyle(ChatFormatting.GRAY));
        }
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
