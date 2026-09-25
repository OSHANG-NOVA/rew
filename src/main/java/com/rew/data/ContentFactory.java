package com.rew.data;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 由「玩家手上的真实物品栈」构造 {@link ContentRef}。
 *
 * <p>存在的理由：内置的物品选择器只能按 <em>物品 ID</em> 选东西，而带 NBT 的物品
 * （附魔书、药水、指定耐久的工具、写了内容的成书……）光有 ID 是表达不出来的 ——
 * 同一个 {@code minecraft:enchanted_book} 配不同的 stored_enchantments 是完全不同的东西。
 * 从背包取则天然拿得到完整栈。
 *
 * <p>关键点：{@code raw} 不是手拼的，而是交给 GT 自己的序列化器生成：
 * <pre>
 * SizedIngredient.create(stack)   // 带 NBT 时内部会包成 StrictNBTIngredient
 *   → new Content(ing, chance, maxChance, 0)
 *   → ItemRecipeCapability.CAP.serializer.toJsonContent(content)
 * </pre>
 * 这与扫描器给已有配方生成 {@code raw} 走的是同一条路径，因此
 * {@link RecipeWriter} 那边的 {@code fromJsonContent} 能原样还原，NBT 一个字节都不丢。
 * 反过来，如果这里手写 {@code {"item":"..."}} 这种朴素 JSON，NBT 就会在重建时被丢掉。
 */
public final class ContentFactory {

    private ContentFactory() {}

    /**
     * 生成物品条目的无损 {@code raw} JSON。
     *
     * @param stack     真实物品栈（含 NBT 时会被包成 StrictNBTIngredient）
     * @param amount    数量；&lt;=0 时按 1 处理
     * @param chance    概率，10000 = 100%；0 = 不消耗（催化剂）
     * @param maxChance 概率上限，通常 10000
     * @return GT 的 {@code toJsonContent} 输出；失败时返回空串（调用方会退回按 ID 重建）
     */
    /**
     * 把真实物品栈的 NBT 与无损 {@code raw} 一起写进 {@link ContentRef}。
     *
     * <p>为什么 NBT 要单独存一份（而不是只依赖 {@code raw}）：{@code iconStack} 是
     * {@code transient} 的，草稿存盘再读回后只剩 ID，列表就会把附魔书画成一本普通书。
     * {@link ContentRef#nbt} 是持久化字段，重新载入后仍能把带 NBT 的栈重建出来。
     */
    public static void applyItem(ContentRef ref, ItemStack stack, long amount, int chance, int maxChance) {
        if (ref == null || stack == null || stack.isEmpty()) return;
        ref.iconStack = stack.copy();
        if (stack.hasTag()) {
            ref.nbt = stack.getTag().toString();
        }
        ref.raw = itemRawJson(stack, amount, chance, maxChance);
    }

    public static String itemRawJson(ItemStack stack, long amount, int chance, int maxChance) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            // 数量由 SizedIngredient 自己携带（它的 toJson 里写 "count"），
            // 所以先把栈的 count 设成目标数量，再整体交给 GT 包装。
            ItemStack sized = stack.copy();
            sized.setCount((int) Math.max(1L, Math.min(Integer.MAX_VALUE, amount)));

            Ingredient ingredient = SizedIngredient.create(sized);
            Content content = new Content(ingredient, chance, maxChance, 0);
            var json = ItemRecipeCapability.CAP.serializer.toJsonContent(content);
            return json == null ? "" : json.toString();
        } catch (Throwable t) {
            // 生成失败不抛给 UI：退回按 ID 重建，作者至少还能看到这个物品（只是丢了 NBT）。
            return "";
        }
    }
}
