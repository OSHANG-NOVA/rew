package com.rew.data;

import com.rew.RewMod;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 被禁用配方的持久化（{@code config/rew/disabled.json}）。
 *
 * <p>语义与用户要求一致：**不删除，只移出加载**。ID 写进这个文件后，
 * {@code RewAddon#removeRecipes} 会在每次数据包重载时把它们喂给 GT 的
 * {@code RECIPE_FILTERS}，于是这些配方根本不会进入 MC 的配方表（机器与 JEI 都看不到），
 * 但本工具自己的快照仍然保留它们的完整内容，随时可以查看、编辑、重新启用。
 *
 * <p>关键约束：GT 每次重载都会 {@code RECIPE_FILTERS.clear()} 后重新收集，
 * 所以这个文件必须在**每一轮**重载时都被读取并重新投喂 —— {@code RewAddon} 正是这么做的。
 */
public final class DisabledStore {

    /** 落盘结构。 */
    public static final class File {
        public List<String> ids = new ArrayList<>();
    }

    private DisabledStore() {}

    private static Path path() {
        return SnapshotStore.disabledFile();
    }

    /** 读取禁用集合；文件不存在或损坏时返回空集合（绝不抛异常，因为它在加载链上）。 */
    public static Set<String> loadIds() {
        Set<String> out = new LinkedHashSet<>();
        Path f = path();
        if (!Files.isRegularFile(f)) return out;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            File parsed = SnapshotStore.gson().fromJson(r, File.class);
            if (parsed != null && parsed.ids != null) {
                for (String id : parsed.ids) {
                    if (id != null && !id.isBlank()) out.add(id);
                }
            }
        } catch (Exception e) {
            RewMod.LOGGER.warn("[{}] disabled.json 读取失败，按「无禁用」处理: {}",
                    RewMod.MOD_ID, e.toString());
        }
        return out;
    }

    public static void save(Collection<String> ids) {
        SnapshotStore.ensureDirs();
        File file = new File();
        file.ids = new ArrayList<>(ids);
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            SnapshotStore.gson().toJson(file, w);
        } catch (Exception e) {
            RewMod.LOGGER.error("[{}] disabled.json 写入失败", RewMod.MOD_ID, e);
        }
    }
}
