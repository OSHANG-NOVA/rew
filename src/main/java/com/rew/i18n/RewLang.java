package com.rew.i18n;

import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.rew.data.ConditionRef;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 显示名解析：只读游戏已经加载的语言文件，不做机翻。
 *
 * <p>三条来源，按优先级：
 * <ol>
 * <li>物品 / 流体 —— 直接问注册表里的对象要显示名。语言文件里有 {@code zh_cn}
 * 条目时，{@code ItemStack#getHoverName} / {@code FluidStack#getDisplayName} 返回的就是中文；
 * 没有条目时原样返回英文名，这是游戏自己的行为，这里不另做处理。</li>
 * <li>配方类型 —— 用 GT 自己的语言键（{@code ResourceLocation#toLanguageKey()}，
 * 即 {@code gtceu:macerator → gtceu.macerator}）。语言文件里有就用，没有就退回路径名。</li>
 * <li>条件 —— 仅 GT 自带的条件类型走内置中文对照表；其他模组注入的条件保持注册名原样，
 * 不猜、不翻。</li>
 * </ol>
 *
 * <p>全部结果按 ID 缓存。语言文件在一次游戏会话内不会变，缓存可以一直用到退出。
 */
public final class RewLang {

    private RewLang() {}

    private static final Map<String, String> ITEM_NAMES = new HashMap<>();
    private static final Map<String, String> FLUID_NAMES = new HashMap<>();
    private static final Map<String, String> TYPE_NAMES = new HashMap<>();

    /**
     * GT 自带条件类型的中文名。
     *
     * <p>键是 {@code GTRegistries.RECIPE_CONDITIONS} 里的注册名（不含命名空间）。
     * 只收录 {@code GTRecipeConditions} 注册的那些 —— 其他模组加的条件不在这里，
     * 显示时保持原样。
     */
    private static final Map<String, String> GT_CONDITIONS;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("biome", "生物群系");
        m.put("biome_tag", "生物群系标签");
        m.put("dimension", "维度");
        m.put("pos_y", "高度");
        m.put("rain", "降雨");
        m.put("adjacent_fluid", "相邻流体");
        m.put("adjacent_block", "相邻方块");
        m.put("thunder", "雷暴");
        m.put("steam_vent", "蒸汽排气");
        m.put("cleanroom", "超净间");
        m.put("eu_to_start", "启动耗能");
        m.put("research", "研究");
        m.put("environmental_hazard", "环境危害");
        m.put("daytime", "昼夜");
        m.put("ftb_quest", "FTB 任务");
        m.put("game_stage", "游戏阶段");
        m.put("heracles_quest", "Heracles 任务");
        GT_CONDITIONS = Collections.unmodifiableMap(m);
    }

    /** GT 自带条件的注册名集合，供调用方判断「要不要汉化」。 */
    public static Set<String> gtConditionKeys() {
        return GT_CONDITIONS.keySet();
    }

    // ------------------------------------------------------------------ 物品 / 流体

    /** 物品的本地化显示名；语言文件没有条目时返回物品自己的英文名，查不到返回空串。 */
    public static String itemName(String id) {
        if (id == null || id.isEmpty()) return "";
        String cached = ITEM_NAMES.get(id);
        if (cached != null) return cached;
        String resolved = resolveItem(id);
        ITEM_NAMES.put(id, resolved);
        return resolved;
    }

    /** 流体的本地化显示名；规则同 {@link #itemName}。 */
    public static String fluidName(String id) {
        if (id == null || id.isEmpty()) return "";
        String cached = FLUID_NAMES.get(id);
        if (cached != null) return cached;
        String resolved = resolveFluid(id);
        FLUID_NAMES.put(id, resolved);
        return resolved;
    }

    private static String resolveItem(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return "";
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) return "";
            String name = new ItemStack(item).getHoverName().getString();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String resolveFluid(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return "";
            var fluid = ForgeRegistries.FLUIDS.getValue(rl);
            if (fluid == null) return "";
            String name = new FluidStack(fluid, 1).getDisplayName().getString();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 配方类型

    /**
     * 配方类型的本地化名。
     *
     * <p>{@code id} 形如 {@code gtceu:macerator}。先查 GT 自己的语言键
     * （{@code gtceu.macerator}），命中就返回中文；没命中退回路径名 {@code macerator}。
     */
    public static String typeName(String id) {
        if (id == null || id.isEmpty()) return "";
        String cached = TYPE_NAMES.get(id);
        if (cached != null) return cached;
        String resolved = resolveType(id);
        TYPE_NAMES.put(id, resolved);
        return resolved;
    }

    private static String resolveType(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            String key = rl != null ? rl.toLanguageKey() : id.replace(':', '.');
            String localized = Component.translatable(key).getString();
            if (localized != null && !localized.isEmpty() && !localized.equals(key)) {
                return localized;
            }
        } catch (Throwable ignored) {
            // 落到路径名
        }
        return path;
    }

    // ------------------------------------------------------------------ 条件

    /**
     * 条件类型的显示名。
     *
     * <p>只汉化 GT 自带的条件；{@code type} 形如 {@code gtceu:cleanroom} 或 {@code cleanroom}。
     * 其他模组注入的条件原样返回，不做猜测。
     */
    public static String conditionName(String type) {
        String path = pathOf(type);
        String zh = GT_CONDITIONS.get(path);
        return zh != null ? zh : type;
    }

    /** 该条件类型是不是 GT 自带的（决定要不要汉化）。 */
    public static boolean isGtCondition(String type) {
        return GT_CONDITIONS.containsKey(pathOf(type));
    }

    /**
     * 一条条件在列表里的完整描述：中文名 + 关键参数。
     *
     * <p>参数从条件自己的 JSON 里抽，不重新翻译数值。取反时前面加「非」。
     * 非 GT 条件只返回注册名。
     */
    public static String conditionText(ConditionRef ref) {
        if (ref == null) return "";
        if (!isGtCondition(ref.type)) {
            return ref.reverse ? "NOT " + ref.type : ref.type;
        }
        String name = conditionName(ref.type);
        String detail = conditionDetail(pathOf(ref.type), ref.json);
        String text = detail.isEmpty() ? name : name + " " + detail;
        return ref.reverse ? "非 " + text : text;
    }

    /** 从条件 JSON 里抽出对作者有用的那一两个字段，拼成短文本。抽不到返回空串。 */
    private static String conditionDetail(String path, String json) {
        if (json == null || json.isEmpty()) return "";
        return switch (path) {
            case "biome", "biome_tag" -> firstOf(json, "biome");
            case "dimension" -> firstOf(json, "dimension");
            case "pos_y" -> range(json, "min", "max");
            case "rain", "thunder" -> firstOf(json, "level");
            case "cleanroom" -> cleanroomOf(json);
            case "eu_to_start" -> withUnit(firstOf(json, "eu"), " EU");
            case "daytime" -> daytimeOf(json);
            case "environmental_hazard" -> firstOf(json, "condition");
            case "game_stage" -> firstOf(json, "stageName", "stage");
            case "ftb_quest", "heracles_quest" -> firstOf(json, "questId", "quest");
            case "research" -> firstOf(json, "researchId");
            default -> "";
        };
    }

    /** 超净间只有「普通 / 无菌」两档，直接给中文。 */
    private static String cleanroomOf(String json) {
        String raw = firstOf(json, "cleanroom");
        if (raw.isEmpty()) return "";
        if (raw.contains("sterile")) return "无菌";
        if (raw.contains("cleanroom")) return "普通";
        return raw;
    }

    /** 昼夜条件的布尔值换成「白天 / 夜晚」。 */
    private static String daytimeOf(String json) {
        String raw = firstOf(json, "isDaytime", "daytime");
        if ("true".equals(raw)) return "白天";
        if ("false".equals(raw)) return "夜晚";
        return raw;
    }

    private static String withUnit(String value, String unit) {
        return value.isEmpty() ? "" : value + unit;
    }

    private static String range(String json, String minKey, String maxKey) {
        String min = firstOf(json, minKey);
        String max = firstOf(json, maxKey);
        if (min.isEmpty() && max.isEmpty()) return "";
        return min + " ~ " + max;
    }

    /**
     * 从 JSON 文本里取第一个命中的字段值。
     *
     * <p>不引入 JSON 解析器：条件 JSON 是 GT 自己序列化的扁平对象，字段值要么是
     * 带引号的字符串、要么是数字或布尔，用定位截取就够，也避免在列表渲染时反复建解析器。
     */
    private static String firstOf(String json, String... keys) {
        for (String key : keys) {
            String v = fieldOf(json, key);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String fieldOf(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        char c = json.charAt(start);
        if (c == '"') {
            int end = json.indexOf('"', start + 1);
            return end < 0 ? "" : json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) break;
            end++;
        }
        return json.substring(start, end);
    }

    private static String pathOf(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ------------------------------------------------------------------ 运行时校验

    /**
     * 启动时把内置对照表和 GT 实际注册的条件对一遍，把对不上的记到日志。
     *
     * <p>只做告警，不改行为：GT 升级后如果改了注册名，这里能一眼看出来，
     * 而不是让界面悄悄退回英文。
     *
     * @return GT 自带、但对照表里没有的条件注册名
     */
    public static Set<String> auditGtConditions() {
        Set<String> missing = new HashSet<>();
        try {
            for (RecipeConditionType<?> t : GTRegistries.RECIPE_CONDITIONS) {
                String key = GTRegistries.RECIPE_CONDITIONS.getKey(t);
                if (key == null) continue;
                String path = pathOf(key);
                String ns = key.contains(":") ? key.substring(0, key.indexOf(':')) : "gtceu";
                if ("gtceu".equals(ns) && !GT_CONDITIONS.containsKey(path)) {
                    missing.add(key);
                }
            }
        } catch (Throwable ignored) {
            // 注册表还没冻结时跳过，不影响功能。
        }
        return missing;
    }

    /** 把一条条件还原成 GT 对象再取它自己的 tooltip；失败返回空串。仅供需要完整句子时使用。 */
    public static String conditionTooltip(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            RecipeCondition<?> condition = RecipeCondition.deserialize(obj);
            if (condition == null) return "";
            Component tip = condition.getTooltips();
            return tip == null ? "" : tip.getString();
        } catch (Throwable t) {
            return "";
        }
    }
}
