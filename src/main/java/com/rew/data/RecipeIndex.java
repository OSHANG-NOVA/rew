package com.rew.data;

import com.rew.RewConfig;
import com.rew.RewMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编辑器运行时的唯一数据源（内存态）。
 *
 * <p>职责：
 * <ul>
 * <li>持有全量快照（类型 + 配方），按类型分桶以便列表快速取用；</li>
 * <li>维护内存中的草稿、禁用集合，并把它们叠加到快照视图上（禁用/修改/新建三种标记）；</li>
 * <li>统一落盘入口（{@link #persistAll}）。</li>
 * </ul>
 *
 * <p>性能取向：分桶 + 预建小写搜索串只在加载时做一次；之后列表翻页、搜索都是纯内存遍历，
 * 不重复拼字符串。
 */
public final class RecipeIndex {

    private static RecipeIndex INSTANCE;

    private SnapshotStore.SnapshotFile snapshot;
    private final Map<String, TypeInfo> typesById = new LinkedHashMap<>();
    private final Map<String, List<RecipeSnapshot>> byType = new LinkedHashMap<>();
    private final Map<String, RecipeSnapshot> byId = new LinkedHashMap<>();

    /** 内存草稿（配方 ID → 快照）。 */
    private final Map<String, RecipeSnapshot> drafts = new LinkedHashMap<>();
    /** 内存禁用集合。 */
    private final Set<String> disabled = new HashSet<>();

    /** 搜索加速：配方 ID → 预拼小写串。 */
    private final Map<String, String> searchBlobs = new HashMap<>();

    private long lastScanMillis = 0L;

    private RecipeIndex() {}

    public static synchronized RecipeIndex get() {
        if (INSTANCE == null) INSTANCE = new RecipeIndex();
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 装载

    /**
     * 载入：优先读磁盘缓存，缓存与当前 GT 版本不符或作者强制重扫时走全量扫描。
     *
     * @param forceRescan 忽略缓存
     */
    public synchronized void load(boolean forceRescan) {
        SnapshotStore.ensureDirs();
        this.disabled.clear();
        this.disabled.addAll(DisabledStore.loadIds());
        this.drafts.clear();
        this.drafts.putAll(DraftStore.loadAll());

        SnapshotStore.SnapshotFile cached = RewConfig.get().useSnapshotCache ? SnapshotStore.load() : null;
        boolean cacheUsable = cached != null
                && !forceRescan
                && !RewConfig.get().alwaysRescan
                && RewMod.gtVersion().equals(cached.gtVersion);

        SnapshotStore.SnapshotFile previous = cached;

        if (cacheUsable) {
            this.snapshot = cached;
            RewMod.LOGGER.info("[{}] 复用配方快照缓存：{} 个类型 / {} 条配方",
                    RewMod.MOD_ID, cached.types.size(), cached.recipes.size());
        } else {
            long t0 = System.currentTimeMillis();
            this.snapshot = RecipeScanner.scan();
            this.lastScanMillis = System.currentTimeMillis() - t0;
            // 被禁用的配方此刻已不在 GT 的配方库里，从旧缓存回填，否则作者再也看不到它们。
            RecipeScanner.mergeDisabled(this.snapshot, previous, this.disabled);
            SnapshotStore.save(this.snapshot);
        }

        reindex();
        applyOverlays();
    }

    /** 重建分桶与搜索串。 */
    private void reindex() {
        typesById.clear();
        byType.clear();
        byId.clear();
        searchBlobs.clear();

        if (snapshot == null) return;

        for (TypeInfo t : snapshot.types) {
            typesById.put(t.id, t);
            byType.put(t.id, new ArrayList<>());
        }
        for (RecipeSnapshot r : snapshot.recipes) {
            byId.put(r.id, r);
            byType.computeIfAbsent(r.typeId, k -> new ArrayList<>()).add(r);
            searchBlobs.put(r.id, r.searchBlob());
        }
        // 配方 ID 排序，保证列表顺序稳定可预期。
        for (List<RecipeSnapshot> list : byType.values()) {
            list.sort((a, b) -> a.id.compareTo(b.id));
        }
    }

    /** 把草稿与禁用标记叠加到快照视图（不改动磁盘缓存）。 */
    private void applyOverlays() {
        for (RecipeSnapshot r : byId.values()) {
            r.disabled = disabled.contains(r.id);
            r.modified = false;
            r.userAdded = false;
        }
        for (RecipeSnapshot draft : drafts.values()) {
            RecipeSnapshot existing = byId.get(draft.id);
            if (existing == null) {
                // 新建：补进快照视图
                RecipeSnapshot copy = draft.copy();
                copy.userAdded = true;
                copy.disabled = disabled.contains(copy.id);
                byId.put(copy.id, copy);
                byType.computeIfAbsent(copy.typeId, k -> new ArrayList<>()).add(copy);
                searchBlobs.put(copy.id, copy.searchBlob());
            } else {
                // 修改：用草稿内容覆盖显示，但保留原标记
                existing.modified = true;
            }
        }
        for (List<RecipeSnapshot> list : byType.values()) {
            list.sort((a, b) -> a.id.compareTo(b.id));
        }
    }

    // ------------------------------------------------------------------ 访问

    public List<TypeInfo> types() {
        return new ArrayList<>(typesById.values());
    }

    public TypeInfo type(String id) {
        return typesById.get(id);
    }

    public List<RecipeSnapshot> recipesOf(String typeId) {
        List<RecipeSnapshot> list = byType.get(typeId);
        return list == null ? Collections.emptyList() : list;
    }

    public RecipeSnapshot recipe(String id) {
        RecipeSnapshot draft = drafts.get(id);
        if (draft != null) return draft;
        return byId.get(id);
    }

    public int totalTypes() {
        return typesById.size();
    }

    public int totalRecipes() {
        return byId.size();
    }

    public long lastScanMillis() {
        return lastScanMillis;
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    // ------------------------------------------------------------------ 编辑

    public boolean isDisabled(String id) {
        return disabled.contains(id);
    }

    public void setDisabled(String id, boolean value) {
        if (value) disabled.add(id);
        else disabled.remove(id);
        RecipeSnapshot r = byId.get(id);
        if (r != null) r.disabled = value;
    }

    public void setDisabledBulk(Iterable<String> ids, boolean value) {
        for (String id : ids) setDisabled(id, value);
    }

    public void putDraft(RecipeSnapshot snap) {
        drafts.put(snap.id, snap);
        applyOverlays();
    }

    /**
     * 为一个配方类型分配一个还没被占用的新配方 ID，形如 {@code rew:macerator_1}。
     *
     * <p>放在 {@code rew} 命名空间下：新建的配方是本工具的产物，与 GT 自带配方
     * （{@code gtceu:...}）分开，日后要整体清理或迁移时一目了然。
     */
    public String nextDraftId(String typeId) {
        String path = typeId == null ? "recipe" : typeId;
        if (path.contains(":")) path = path.substring(path.indexOf(':') + 1);
        path = path.replace('/', '_').replace('\\', '_');
        int n = 1;
        String candidate;
        do {
            candidate = "rew:" + path + "_" + n;
            n++;
        } while (byId.containsKey(candidate) || drafts.containsKey(candidate));
        return candidate;
    }

    public void removeDraft(String id) {
        drafts.remove(id);
        byId.remove(id);
        for (List<RecipeSnapshot> list : byType.values()) {
            list.removeIf(r -> r.id.equals(id));
        }
        searchBlobs.remove(id);
    }

    public Map<String, RecipeSnapshot> drafts() {
        return drafts;
    }

    public Set<String> disabledIds() {
        return disabled;
    }

    /** 把内存态全部落盘（{@code /rew save}）。 */
    public synchronized void persistAll() {
        DisabledStore.save(disabled);
        for (RecipeSnapshot draft : drafts.values()) {
            DraftStore.save(draft);
        }
        if (snapshot != null) SnapshotStore.save(snapshot);
        RewMod.LOGGER.info("[{}] 已保存：{} 条禁用 / {} 条草稿",
                RewMod.MOD_ID, disabled.size(), drafts.size());
    }

    // ------------------------------------------------------------------ 搜索

    /** 预拼的搜索串；没有则回退到即时拼接。 */
    public String blobOf(RecipeSnapshot r) {
        String blob = searchBlobs.get(r.id);
        return blob != null ? blob : r.searchBlob();
    }
}
