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
 * /rew reload       重扫配方快照（丢弃内存态，按当前 GT 配方库重新拍）
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

    public static void setOpenEditor(Supplier<Boolean> supplier) {
        openEditor = supplier;
    }

    public static void setOpenGtmBrowser(Supplier<Boolean> supplier) {
        openGtmBrowser = supplier;
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

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        RecipeIndex index = RecipeIndex.get();
        msg(ctx, "正在重扫 GT 配方库…", ChatFormatting.GRAY);
        index.load(true);
        applyOverlaysToUi(index);
        msg(ctx, "重扫完成：" + index.totalTypes() + " 个类型 / " + index.totalRecipes()
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
