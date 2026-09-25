package com.rew.data;

import com.rew.RewMod;

import net.minecraft.data.recipes.FinishedRecipe;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 编辑器产出的「草稿配方」持久化（{@code config/rew/drafts/*.json}，一条配方一个文件）。
 *
 * <p>为什么一条一个文件：作者可以单条删除、单条比对、单条提交进版本库，
 * 出问题时也只会影响一条配方，而不是整个大文件解析失败。
 *
 * <p>注入路径：{@link #injectAll} 在 {@code RewAddon#addRecipes} 里被调用，
 * 走 GT 官方 {@code IGTAddon#addRecipes} → {@code GTDynamicDataPack::addRecipe}，
 * 与 GT 自己生成配方完全同一条链路，随资源重载生效。
 */
public final class DraftStore {

    private DraftStore() {}

    /** 配方 ID → 文件名（把 {@code :} 与 {@code /} 换成安全字符）。 */
    public static String fileNameOf(String recipeId) {
        return recipeId.replace(':', '_').replace('/', '_').replace('\\', '_') + ".json";
    }

    public static Path fileOf(String recipeId) {
        return SnapshotStore.draftsDir().resolve(fileNameOf(recipeId));
    }

    /** 读取全部草稿；任何单个文件损坏都只跳过该文件。 */
    public static Map<String, RecipeSnapshot> loadAll() {
        Map<String, RecipeSnapshot> out = new LinkedHashMap<>();
        Path dir = SnapshotStore.draftsDir();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
            files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            for (Path f : files) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    RecipeSnapshot snap = SnapshotStore.gson().fromJson(r, RecipeSnapshot.class);
                    if (snap != null && snap.id != null && !snap.id.isBlank()) {
                        out.put(snap.id, snap);
                    }
                } catch (Exception e) {
                    RewMod.LOGGER.warn("[{}] 草稿 {} 解析失败，已跳过: {}",
                            RewMod.MOD_ID, f.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            RewMod.LOGGER.error("[{}] drafts 目录读取失败", RewMod.MOD_ID, e);
        }
        return out;
    }

    public static void save(RecipeSnapshot snap) {
        SnapshotStore.ensureDirs();
        try (Writer w = Files.newBufferedWriter(fileOf(snap.id), StandardCharsets.UTF_8)) {
            SnapshotStore.gson().toJson(snap, w);
        } catch (Exception e) {
            RewMod.LOGGER.error("[{}] 草稿 {} 写入失败", RewMod.MOD_ID, snap.id, e);
        }
    }

    public static void delete(String recipeId) {
        try {
            Files.deleteIfExists(fileOf(recipeId));
        } catch (Exception e) {
            RewMod.LOGGER.warn("[{}] 草稿 {} 删除失败: {}", RewMod.MOD_ID, recipeId, e.toString());
        }
    }

    /**
     * 把全部草稿注入 GT 的配方生成链路。
     *
     * @return 成功注入的条数
     */
    public static int injectAll(Consumer<FinishedRecipe> provider) {
        int ok = 0;
        for (RecipeSnapshot snap : loadAll().values()) {
            try {
                FinishedRecipe finished = RecipeWriter.toFinished(snap);
                if (finished != null) {
                    provider.accept(finished);
                    ok++;
                }
            } catch (Throwable t) {
                RewMod.LOGGER.error("[{}] 草稿 {} 注入失败，已跳过", RewMod.MOD_ID, snap.id, t);
            }
        }
        return ok;
    }
}
