package com.rew.data;

import com.gregtechceu.gtceu.common.data.GTRecipes;
import com.gregtechceu.gtceu.data.pack.GTDynamicDataPack;
import com.rew.RewMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 主动重建 GT 动态配方包 —— 修复「新建配方保存后 /reload 不出现」的核心。
 *
 * <h2>问题</h2>
 * GT 只在 {@code AddPackFindersEvent}（世界加载 / 创建世界）里做一次：
 * <pre>
 * GTDynamicDataPack.clearServer();
 * GTRecipes.recipeRemoval();
 * GTRecipes.recipeAddition(GTDynamicDataPack::addRecipe);   // ← 本模组的 addRecipes 在这里被回调
 * </pre>
 * 而 {@code AddPackFindersEvent} 由 {@code ServerPacksSource} 在世界加载时触发，
 * 原版 {@code /reload} 走的是 {@code MinecraftServer#reloadResources} → 直接构造
 * {@code MultiPackResourceManager}，**不会**重新触发这个事件。
 *
 * <p>于是 {@code GTDynamicDataPack.CONTENTS}（内存里的动态数据包内容）停留在世界加载那一刻：
 * 作者之后新建并保存的草稿，从来没有机会被写进去。{@code /reload} 读到的还是旧内容，
 * 所以「保存了但游戏里看不到」。
 *
 * <h2>修法</h2>
 * {@code ReloadableServerResources#loadResources} 的顺序是：
 * <pre>
 * listeners = [tagManager, lootData, recipes, functionLibrary, advancements]
 * listeners.addAll(ForgeEventFactory.onResourceReload(...))   // ← AddReloadListenerEvent 在这里同步触发
 * SimpleReloadInstance.create(resourceManager, listeners, ...) // ← 之后才轮到 RecipeManager 读资源
 * </pre>
 * 也就是说 {@code AddReloadListenerEvent} 在**任何**监听器（含 RecipeManager）开始读资源之前触发。
 * 在这里重建 CONTENTS，{@code RecipeManager} 随后就能读到新配方。见 {@code RewReloadEvents}。
 *
 * <h2>为什么用指纹门控</h2>
 * 重跑 {@code recipeAddition} 会重新生成 GT 的全部配方（实测约 15.6 s），代价很高。
 * 因此只在「禁用表或草稿真的变了」时才重建 —— 正常 {@code /reload} 是零开销的。
 *
 * <h2>为什么必须整包重建，而不是只补草稿</h2>
 * 只补草稿（{@code addRecipe} 按位置覆盖）能修好「新建/修改」，
 * 但**修不了「新禁用」**：GT 的配方过滤器（{@code RECIPE_FILTERS}）作用在
 * {@code recipeAddition} 的写入阶段，已经写进 CONTENTS 的配方不会因为它被摘掉；
 * 而 GT 动态包自己的 filter 段只用于拦截**其它**数据包（见 {@code FallbackResourceManager#getResource}
 * 里「包自己有就直接返回」的短路）。所以禁用要在写入时生效，就必须清空重写。
 */
public final class GtRecipeInjector {

    /** 上一次成功重建时的内容指纹。空串表示「还没有记录」。 */
    private static volatile String lastSignature = "";

    private GtRecipeInjector() {}

    /**
     * 在**模组构造期**（世界加载之前）记录一次磁盘基线。
     *
     * <p>没有这一步会有一个真实的浪费：GT 自己的世界加载流程（{@code AddPackFindersEvent}）
     * 与本模组监听的 {@code AddReloadListenerEvent} 谁先谁后并无保证。
     * 若后者先到，{@code lastSignature} 还是空串，指纹必然不匹配，
     * 于是每次进世界都要白跑一遍十几秒的全量重建。
     *
     * <p>模组构造一定早于任何世界加载，此刻磁盘上的内容也正是 GT 世界加载时会读到的内容，
     * 所以在这里打基线，两种情况都不会误触发：
     * <ul>
     * <li>{@code AddReloadListenerEvent} 先到 → 指纹相等 → 直接跳过；</li>
     * <li>{@code AddPackFindersEvent} 先到 → GT 注入后由 {@code RewAddon} 再打一次基线 → 同样跳过。</li>
     * </ul>
     * 之后作者在编辑器里保存、磁盘内容变化，才会真正触发重建。
     */
    public static synchronized void init() {
        lastSignature = signature();
    }

    /**
     * 禁用表 + 全部草稿的内容指纹（SHA-256）。
     *
     * <p>用内容而不是文件 mtime：作者可能用编辑器原地改回原样，
     * 也可能把整个 config/rew 拷来拷去，mtime 会骗人，内容不会。
     */
    public static String signature() {
        StringBuilder sb = new StringBuilder(4096);

        Path disabled = SnapshotStore.disabledFile();
        if (Files.isRegularFile(disabled)) {
            try {
                sb.append(Files.readString(disabled, StandardCharsets.UTF_8));
            } catch (Exception e) {
                sb.append('E').append(e.getClass().getName());
            }
        }
        sb.append('\u0000');

        Path dir = SnapshotStore.draftsDir();
        if (Files.isDirectory(dir)) {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
            } catch (Exception ignored) {
                // 目录读不到就当作没有草稿：宁可漏判（不重建）也不要抛异常打断重载。
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path f : files) {
                sb.append(f.getFileName()).append('=');
                try {
                    sb.append(Files.readString(f, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    sb.append('?');
                }
                sb.append('\u0001');
            }
        }
        return sha256(sb.toString());
    }

    /**
     * 磁盘内容是否与「上次已写进 CONTENTS 的内容」不同。
     *
     * <p>给 {@code /rew reload} 用：作者连按两次时，第二次能立刻得到「无变化」的回复，
     * 不必先等一轮服务端重载才发现什么都没变。
     */
    public static synchronized boolean hasChanged() {
        return !signature().equals(lastSignature);
    }

    /**
     * 内容变了才重建；没变直接返回 false。
     *
     * <p>这个方法是 {@code AddReloadListenerEvent} 的入口，会被每一轮资源重载调用，
     * 所以「没变就立刻返回」是它的主要职责。
     */
    public static synchronized boolean regenerateIfChanged() {
        String sig = signature();
        if (sig.equals(lastSignature)) return false;
        regenerate();
        return true;
    }

    /**
     * 无条件整包重建：与 GT 在世界加载时做的三步完全一致。
     *
     * <p>刻意**不**重跑 {@code GTCraftingComponents.init()}：它会给 KubeJS / Forge
     * 再发一次「合成组件被修改」事件，重复发会让附属模组的改动被叠加两次。
     * 合成组件在一局游戏里不会变，没必要重建。
     */
    public static synchronized void regenerate() {
        long t0 = System.currentTimeMillis();
        try {
            GTDynamicDataPack.clearServer();
            GTRecipes.recipeRemoval();
            // 这一步内部会回调本模组的 RewAddon#addRecipes，把最新草稿一起写进去。
            GTRecipes.recipeAddition(GTDynamicDataPack::addRecipe);
        } finally {
            // 无论成败都记下指纹：失败时若反复重试，每轮重载都要卡十几秒，体验更糟。
            lastSignature = signature();
        }
        RewMod.LOGGER.info("[{}] 已重建 GT 动态配方包，耗时 {} ms",
                RewMod.MOD_ID, System.currentTimeMillis() - t0);
    }

    /**
     * 记录「当前磁盘内容已经被写进 CONTENTS 了」。
     *
     * <p>由 {@code RewAddon#addRecipes} 在 GT 自己的世界加载流程里调用 ——
     * 那一轮已经把草稿注入过了，随后的第一次 {@code AddReloadListenerEvent}
     * 不该再白跑一次 15.6 s。
     */
    public static synchronized void markRegenerated() {
        lastSignature = signature();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不会发生；退化用长度+哈希码，仍能区分绝大多数改动。
            return "len" + text.length() + "-" + text.hashCode();
        }
    }
}
