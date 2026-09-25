package com.rew.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.rew.RewConfig;
import com.rew.RewMod;
import com.rew.data.RecipeIndex;
import com.rew.data.RecipeSnapshot;
import com.rew.export.KubeJsExporter;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /rew} 指令族。
 *
 * <p>全部在**客户端**注册（{@code RegisterClientCommandsEvent}）—— 因为本工具的编辑动作是
 * 纯客户端行为：扫描读客户端已同步的 GT 配方库，编辑产物写本地 {@code config/rew/}，
 * 再由 GT 官方的 {@code IGTAddon} 在服务端重载时读取生效。
 * 这样专用服务器即使没装本模组，单人/联机下的编辑器也照常可用。
 *
 * <p>指令表：
 * <pre>
 * /rew              打开配方编辑器主界面
 * /rew gtm          打开 GT 配方总览（多选 + Del 禁用）
 * /rew reload       重建 GT 配方包 → 触发服务端重载 → 客户端收到新配方后自动重扫
 * /rew save         把内存中的禁用表与草稿落盘
 * /rew kjsexpt      导出全部草稿与已改动配方为 KubeJS 脚本
 * /rew info         打印当前索引状态
 * </pre>
 */
public final class RewCommands {

    private RewCommands() {}

    /** UI 打开回调 —— 由客户端包注入，避免指令层直接依赖客户端类（服务端安全）。 */
    private static Supplier<Boolean> openEditor = () -> false;
    private static Supplier<Boolean> openGtmBrowser = () -> false;
    /** 服务端重载的三种结果（见 {@link #reload}）。 */
    public static final int RELOAD_UNAVAILABLE = 0;
    public static final int RELOAD_TRIGGERED = 1;
    public static final int RELOAD_UNCHANGED = 2;

    /** 「重建 GT 配方包 + 触发服务端重载」回调，同样由客户端包注入。 */
    private static Supplier<Integer> serverReload = () -> RELOAD_UNAVAILABLE;

    public static void setOpenEditor(Supplier<Boolean> supplier) {
        openEditor = supplier;
    }

    public static void setOpenGtmBrowser(Supplier<Boolean> supplier) {
        openGtmBrowser = supplier;
    }

    public static void setServerReload(Supplier<Integer> supplier) {
        serverReload = supplier;
    }

    public static <S> void register(CommandDispatcher<S> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rew")
                .executes(ctx -> openMain(ctx))
                .then(Commands.literal("gtm").executes(ctx -> openGtm(ctx)))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx)))
                .then(Commands.literal("save").executes(ctx -> save(ctx)))
                .then(Commands.literal("kjsexpt").executes(ctx -> kjsExport(ctx)))
                .then(Commands.literal("kjsept").executes(ctx -> kjsExport(ctx)))
                .then(Commands.literal("info").executes(ctx -> info(ctx)));

        // 用户口述里出现过 /raw gtm，作为别名一并注册，避免记错命令名时找不到入口。
        LiteralArgumentBuilder<CommandSourceStack> alias = Commands.literal("raw")
                .then(Commands.literal("gtm").executes(ctx -> openGtm(ctx)));

        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> d = (CommandDispatcher<CommandSourceStack>) dispatcher;
        d.register(root);
        d.register(alias);
    }

    private static int openMain(CommandContext<CommandSourceStack> ctx) {
        if (!enabled(ctx)) return 0;
        if (openEditor.get()) return 1;
        msg(ctx, "无法打开编辑器界面（仅客户端可用）", ChatFormatting.RED);
        return 0;
    }

    private static int openGtm(CommandContext<CommandSourceStack> ctx) {
        if (!enabled(ctx)) return 0;
        if (openGtmBrowser.get()) return 1;
        msg(ctx, "无法打开 GT 配方总览（仅客户端可用）", ChatFormatting.RED);
        return 0;
    }

    private static boolean enabled(CommandContext<CommandSourceStack> ctx) {
        if (!RewConfig.get().enableEditor) {
            msg(ctx, "配方编辑器已在配置中关闭（config/rew.yaml → enableEditor）", ChatFormatting.YELLOW);
            return false;
        }
        return true;
    }

    /**
     * {@code /rew reload} —— 主动把改动推进游戏。
     *
     * <p>顺序很关键，必须是「先落盘 → 再触发服务端资源重载 → 最后重扫客户端快照」：
     * <ol>
     * <li>落盘：服务端的 GT 附属回调是从 {@code config/rew/} 读文件的，
     * 内存里的编辑不落盘它读不到。</li>
     * <li>触发重载：服务端 {@code reloadResources} 会重建动态数据包内容
     * （见 {@code GtRecipeInjector}），新配方这时才真正进入游戏。</li>
     * <li>重扫：客户端配方表要等服务端把新配方同步下来才会更新，放在最后才对得上。</li>
     * </ol>
     *
     * <p>服务端重载是**异步**的（{@code CompletableFuture}），所以这里不阻塞等待；
     * 同步完成后客户端会收到新的配方包，作者再执行一次 {@code /rew reload} 或重开界面
     * 就能看到最新结果。这样不会让客户端主线程卡住十几秒。
     */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        RecipeIndex index = RecipeIndex.get();
        index.persistAll();
        msg(ctx, "已保存 " + index.drafts().size() + " 条草稿 / "
                + index.disabledIds().size() + " 条禁用，正在重建 GT 配方包…", ChatFormatting.GRAY);

        int result = RELOAD_UNAVAILABLE;
        try {
            result = serverReload.get();
        } catch (Throwable t) {
            RewMod.LOGGER.error("[{}] 触发服务端重载失败", RewMod.MOD_ID, t);
        }
        if (result == RELOAD_UNCHANGED) {
            // 指纹没变说明这一轮没有新东西可注入：不重建、不重载，
            // 否则作者连按两次 /rew reload 就要白等两个十几秒。
            msg(ctx, "磁盘内容与当前配方包一致，无需重建（没有新的草稿/禁用改动）",
                    ChatFormatting.YELLOW);
            return 1;
        }
        if (result == RELOAD_TRIGGERED) {
            // 服务端重载是异步的，此刻新配方还没同步到客户端。这里刻意**不**重扫：
            // 现在扫只会得到旧数据，还会把那份旧快照写回 snapshot.json。
            // 真正的重扫交给 RecipesUpdatedEvent（见 RewClientEvents#onRecipesUpdated）。
            msg(ctx, "已在后台重建 GT 配方包并触发服务端重载，配方同步后会自动刷新快照",
                    ChatFormatting.GREEN);
            return 1;
        }

        // 取不到集成服务器（专用服务器 / 客户端指令拿不到 server 实例）时退回纯重扫：
        // 至少让编辑器与磁盘保持一致，并提示作者手动 /reload。
        msg(ctx, "无法触发服务端重载（仅单人/局域网可用）", ChatFormatting.YELLOW);
        msg(ctx, "请手动执行 /reload，效果相同", ChatFormatting.GRAY);
        index.load(true);
        applyOverlaysToUi(index);
        msg(ctx, "已重扫： " + index.totalTypes() + " 个类型 / " + index.totalRecipes()
                + " 条配方（" + index.lastScanMillis() + " ms）", ChatFormatting.GREEN);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        RecipeIndex.get().persistAll();
        RecipeIndex index = RecipeIndex.get();
        msg(ctx, "已保存：" + index.disabledIds().size() + " 条禁用 / "
                + index.drafts().size() + " 条草稿 → config/rew/", ChatFormatting.GREEN);
        msg(ctx, "提示：改动要进入游戏需执行 /reload 触发 GT 重新烘焙配方库", ChatFormatting.GRAY);
        return 1;
    }

    private static int kjsExport(CommandContext<CommandSourceStack> ctx) {
        RecipeIndex index = RecipeIndex.get();
        List<RecipeSnapshot> targets = new ArrayList<>(index.drafts().values());
        if (targets.isEmpty()) {
            msg(ctx, "没有可导出的配方：请先在编辑器里新建或修改配方并执行 /rew save",
                    ChatFormatting.YELLOW);
            return 0;
        }
        KubeJsExporter.Result result = KubeJsExporter.export(targets, "rew_export.js");
        msg(ctx, "已导出 " + result.exported + " 条配方 → " + result.file, ChatFormatting.GREEN);
        for (String w : result.warnings) {
            msg(ctx, "  · " + w, ChatFormatting.GOLD);
        }
        msg(ctx, "提示：KubeJS 脚本需执行 /reload 或 /kubejs reload server 生效", ChatFormatting.GRAY);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        RecipeIndex index = RecipeIndex.get();
        msg(ctx, "Recipe Editor Workshop v" + RewMod.modVersion(), ChatFormatting.AQUA);
        msg(ctx, "  类型 " + index.totalTypes() + " / 配方 " + index.totalRecipes()
                + " / 禁用 " + index.disabledIds().size() + " / 草稿 " + index.drafts().size(),
                ChatFormatting.GRAY);
        msg(ctx, "  上次扫描耗时 " + index.lastScanMillis() + " ms", ChatFormatting.GRAY);
        msg(ctx, "  数据目录 config/rew/", ChatFormatting.GRAY);
        return 1;
    }

    /** 重扫后把标记刷回 UI（UI 未打开时是空操作）。 */
    private static void applyOverlaysToUi(RecipeIndex index) {
        // 索引自身已重建；这里只是给未来 UI 挂钩留的位置。
    }

    private static void msg(CommandContext<CommandSourceStack> ctx, String text, ChatFormatting color) {
        ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }
}
