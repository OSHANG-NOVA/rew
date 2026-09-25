package com.rew;

import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.rew.data.GtRecipeInjector;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Recipe Editor Workshop —— 面向整合包作者的可视化 GTCEu 配方编辑器。
 *
 * <p>本模组刻意不携带任何 Mixin。GT 为附属模组提供了两个官方扩展点，正好覆盖本工具的全部写入需求：
 * <ul>
 * <li>禁用配方：{@code IGTAddon#removeRecipes} —— 被禁用的 ID 在 {@code GTRecipes.recipeAddition}
 * 阶段被丢弃，配方根本不会进入 MC 的配方表，但本工具自己的快照仍能读到它。</li>
 * <li>新增/修改配方：{@code IGTAddon#addRecipes} —— 走 GT 原生动态数据包通路，随资源重载生效。</li>
 * </ul>
 * 两条通路都不触碰混淆名，因此本模组不需要 MixinGradle / refmap。
 */
@Mod(RewMod.MOD_ID)
public class RewMod {

    public static final String MOD_ID = "rew";
    public static final String NAME = "Recipe Editor Workshop";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    /**
     * IGTAddon 要求每个附属模组暴露自己的 GTRegistrate。本工具不注册任何方块/物品，
     * 但仍需实例化以满足接口契约（GT 在 {@code AddonFinder} 中按 {@code @GTAddon} 反射查找）。
     */
    public static final GTRegistrate REGISTRATE = GTRegistrate.create(MOD_ID);

    public RewMod() {
        // 配置走 nova_mainlib 的统一接入层（dev.toma.configuration，YAML + 游戏内热重载）。
        try {
            RewConfig.init();
        } catch (Throwable t) {
            LOGGER.error("[{}] 配置注册失败，将使用默认值", NAME, t);
        }
        // 记下磁盘基线指纹：这样首次 AddReloadListenerEvent 不会误判「内容变了」
        // 而白跑一遍十几秒的 GT 全量配方重建。详见 GtRecipeInjector#init。
        try {
            GtRecipeInjector.init();
        } catch (Throwable t) {
            LOGGER.warn("[{}] 配方指纹基线记录失败，首次重载可能多花一次重建时间", NAME, t);
        }
        LOGGER.info("[{}] initialized (v{})", NAME, modVersion());
    }

    /** 本模组版本；拿不到时返回 dev，绝不抛异常（多处日志会调用它）。 */
    public static String modVersion() {
        try {
            return ModList.get().getModContainerById(MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("dev");
        } catch (Throwable t) {
            return "dev";
        }
    }

    /** GTM 版本；用于判断磁盘快照缓存是否仍然有效。 */
    public static String gtVersion() {
        try {
            return ModList.get().getModContainerById("gtceu")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
