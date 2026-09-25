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

    /**
     * 快照格式 / 扫描语义版本。
     *
     * <p>结构不兼容、或扫描器写入的字段语义变了（例如开始识别编程电路）时自增，
     * 旧缓存就不再被复用，会自动重扫一遍。
     *
     * <p>注意：版本不符时 {@link #load()} **仍然返回**那个旧文件，只是不把它当作可用缓存。
     * 这是必需的 —— 被禁用的配方在 GT 加载阶段就被剔除了，新扫描扫不到它们，
     * 只能从旧快照里回填（见 {@code RecipeIndex#load} 的 {@code mergeDisabled}）。
     * 若在这里直接返回 null，作者就再也看不到、也无法恢复自己禁用过的配方了。
     */
    public static final int FORMAT_VERSION = 2;

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

    /**
     * 读快照缓存；文件不存在或解析失败时返回 null。
     *
     * <p>版本不符时**不**返回 null，而是照常返回那个旧文件 —— 调用方
     * （{@code RecipeIndex#load}）用 {@code sf.version == FORMAT_VERSION} 自行判断能否直接复用。
     * 这样即使要重扫，也还留着旧快照用于回填被禁用的配方。
     */
    public static SnapshotFile load() {
        Path f = snapshotFile();
        if (!Files.isRegularFile(f)) return null;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            SnapshotFile sf = GSON.fromJson(r, SnapshotFile.class);
            if (sf == null) return null;
            if (sf.version != FORMAT_VERSION) {
                RewMod.LOGGER.info("[{}] 快照缓存版本 {} != {}，将重新扫描（旧快照仍用于回填禁用项）",
                        RewMod.MOD_ID, sf.version, FORMAT_VERSION);
            }
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
