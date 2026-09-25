package com.rew.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rew.RewMod;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/rew/} 下的全部持久化读写。
 *
 * <p>目录布局（整合包全局，客户端与服务端共用同一份）：
 * <pre>
 * config/rew/
 *   snapshot.json    配方全量快照缓存（首次扫描后生成）
 *   disabled.json    被禁用的配方 ID 列表（服务端据此从加载中剔除）
 *   drafts/          编辑器保存的草稿（新建 / 修改后的配方）
 *   export/          导出产物（KubeJS 脚本等）
 * </pre>
 */
public final class SnapshotStore {

    /** 快照格式版本；结构不兼容时自增即可让旧缓存自动失效。 */
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private SnapshotStore() {}

    public static Gson gson() {
        return GSON;
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(RewMod.MOD_ID);
    }

    public static Path snapshotFile() {
        return dir().resolve("snapshot.json");
    }

    public static Path disabledFile() {
        return dir().resolve("disabled.json");
    }

    public static Path draftsDir() {
        return dir().resolve("drafts");
    }

    public static Path exportDir() {
        return dir().resolve("export");
    }

    public static void ensureDirs() {
        try {
            Files.createDirectories(dir());
            Files.createDirectories(draftsDir());
            Files.createDirectories(exportDir());
        } catch (Exception e) {
            RewMod.LOGGER.error("无法创建 config/rew 目录", e);
        }
    }

    /** 读快照缓存；不存在或版本不符时返回 null（调用方应重新扫描）。 */
    public static SnapshotFile load() {
        Path f = snapshotFile();
        if (!Files.isRegularFile(f)) return null;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            SnapshotFile sf = GSON.fromJson(r, SnapshotFile.class);
            if (sf == null || sf.version != FORMAT_VERSION) return null;
            return sf;
        } catch (Exception e) {
            RewMod.LOGGER.warn("[{}] 快照缓存读取失败，将重新扫描: {}", RewMod.MOD_ID, e.toString());
            return null;
        }
    }

    public static void save(SnapshotFile file) {
        ensureDirs();
        file.version = FORMAT_VERSION;
        file.timestamp = System.currentTimeMillis();
        try (Writer w = Files.newBufferedWriter(snapshotFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(file, w);
        } catch (Exception e) {
            RewMod.LOGGER.error("[{}] 快照缓存写入失败", RewMod.MOD_ID, e);
        }
    }

    /** 快照文件根结构。 */
    public static final class SnapshotFile {

        public int version = FORMAT_VERSION;
        public String modVersion = "";
        public String gtVersion = "";
        public String mcVersion = "";
        public long timestamp = 0L;

        public List<TypeInfo> types = new ArrayList<>();
        public List<RecipeSnapshot> recipes = new ArrayList<>();

        public int totalRecipes() {
            return recipes.size();
        }
    }
}
