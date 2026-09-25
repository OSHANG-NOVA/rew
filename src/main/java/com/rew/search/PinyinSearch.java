package com.rew.search;

import com.rew.RewMod;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * 通用拼音搜索接入（JustEnoughCharacters）。
 *
 * <p>为什么用反射而不是编译期依赖：JEC 是**可选**的客户端模组，整合包里不一定装。
 * 硬依赖会让没装 JEC 的包直接崩，而 JEC 的公开入口
 * {@code me.towdium.jecharacters.utils.Match#contains(String, CharSequence)} 是个纯静态方法，
 * 反射调用足够稳定，装了就自动生效，没装就静默退化成普通 {@code contains}。
 *
 * <p>顺带处理一个小坑：{@code Match.contains} 的参数顺序是 (查询串, 被查文本)，与直觉相反，
 * 传反了会永远返回 false —— 这里固定封装好，调用方不用再记。
 */
public final class PinyinSearch {

    private static final String JEC_MOD_ID = "jecharacters";
    private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";

    private static boolean resolved = false;
    private static Method containsMethod = null;

    private PinyinSearch() {}

    /** 是否成功挂上 JEC（仅用于日志与 UI 提示）。 */
    public static synchronized boolean available() {
        resolve();
        return containsMethod != null;
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            if (!ModList.get().isLoaded(JEC_MOD_ID)) {
                RewMod.LOGGER.info("[{}] 未检测到 JustEnoughCharacters，搜索将退化为普通子串匹配",
                        RewMod.MOD_ID);
                return;
            }
            Class<?> matchClass = Class.forName(MATCH_CLASS);
            // 精确取 (String, CharSequence) 这个重载，避免将来 JEC 增加同名方法时取错。
            Method m = matchClass.getMethod("contains", String.class, CharSequence.class);
            containsMethod = m;
            RewMod.LOGGER.info("[{}] 已接入 JustEnoughCharacters 拼音搜索", RewMod.MOD_ID);
        } catch (Throwable t) {
            containsMethod = null;
            RewMod.LOGGER.warn("[{}] JEC 接入失败，搜索退化为普通子串匹配: {}",
                    RewMod.MOD_ID, t.toString());
        }
    }

    /**
     * 判断 {@code haystack}（被搜索的文本）是否匹配 {@code query}（用户输入）。
     *
     * <p>策略：先做廉价的小写子串判断，未命中再交给 JEC 做拼音匹配。
     * 这样纯英文/数字查询完全不进 JEC，避免把最常用的路径拖慢。
     */
    public static boolean matches(String haystack, String query) {
        if (query == null || query.isEmpty()) return true;
        if (haystack == null) return false;

        String h = haystack.toLowerCase();
        String q = query.toLowerCase();
        if (h.contains(q)) return true;

        resolve();
        if (containsMethod == null) return false;
        try {
            // JEC 的签名是 contains(查询串, 被查文本)，顺序不能反。
            Object r = containsMethod.invoke(null, q, h);
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
