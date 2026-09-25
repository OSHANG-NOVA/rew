package com.rew.data;

import com.rew.i18n.RewLang;

/**
 * 一条配方条件的可序列化表示。
 *
 * <p>{@link #json} 是 GT 自己 {@code RecipeCondition#serialize()} 的原样输出，
 * 因此重建时直接走 {@code RecipeCondition#deserialize} 即可无损还原 —— 本工具
 * 不枚举条件种类，凡注入进 GT 的条件类型都自动支持。
 */
public final class ConditionRef {

    /** 条件类型 ID，如 {@code gtceu:cleanroom} / {@code gtceu:dimension}。 */
    public String type = "";

    /** GT 序列化后的完整 JSON。 */
    public String json = "";

    /** 是否取反（GT 的 reverse 标记），便于列表里直观显示。 */
    public boolean reverse = false;

    /** 运行时填充的本地化描述，不写入缓存。 */
    public transient String tooltip = "";

    /**
     * 列表里显示的一行文本。
     *
     * <p>GT 自带条件显示中文名加关键参数；其他模组注入的条件保持注册名原样。
     * 结果缓存在 {@link #tooltip} 上，避免列表滚动时反复解析 JSON。
     */
    public String display() {
        if (tooltip == null || tooltip.isEmpty()) {
            tooltip = RewLang.conditionText(this);
        }
        return tooltip;
    }

    /** JSON 或取反状态变了之后调用，让下次显示重新生成中文描述。 */
    public void invalidateText() {
        tooltip = "";
    }

    @Override
    public String toString() {
        return display();
    }
}
