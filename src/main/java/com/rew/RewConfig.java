package com.rew;

import dev.toma.configuration.Configuration;
import dev.toma.configuration.config.Config;
import dev.toma.configuration.config.Configurable;
import dev.toma.configuration.config.format.ConfigFormats;

/**
 * 本模组配置（落 {@code config/rew.yaml}）。
 *
 * <p>刻意保持极简：编辑器本身不需要调参，这里只放「缓存策略」与「导出策略」两类开关，
 * 避免给整合包作者增加无谓的配置负担。
 */
@Config(id = RewMod.MOD_ID)
public class RewConfig {

    public static RewConfig INSTANCE;

    @Configurable
    @Configurable.Comment("Enable the /rew recipe editor.")
    public boolean enableEditor = true;

    @Configurable
    @Configurable.Comment("Reuse config/rew/snapshot.json when it matches the current GT/MC version.")
    public boolean useSnapshotCache = true;

    @Configurable
    @Configurable.Comment("Force a full rescan on every launch (ignores the cache).")
    public boolean alwaysRescan = false;

    @Configurable
    @Configurable.Comment("Directory (relative to the game root) that /rew kjsexpt writes scripts into.")
    public String kubejsExportDir = "kubejs/server_scripts/rew";

    public static void init() {
        // 直接走 Configuration 的 YAML 注册。这一行原本由 nova_mainlib 的 NovaConfig 转发，
        // 但那层包装内部就是同样的调用，去掉后本模组不再需要 nova_mainlib 前置。
        INSTANCE = Configuration.registerConfig(RewConfig.class, ConfigFormats.yaml()).getConfigInstance();
    }

    /** 配置可能因注册失败而为 null，所有读取点统一走这里兜底。 */
    public static RewConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new RewConfig();
        }
        return INSTANCE;
    }
}
