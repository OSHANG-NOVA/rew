package com.rew;

import com.gregtechceu.gtceu.api.addon.GTAddon;
import com.gregtechceu.gtceu.api.addon.IGTAddon;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;

import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.resources.ResourceLocation;

import com.rew.data.DisabledStore;
import com.rew.data.DraftStore;
import com.rew.data.GtRecipeInjector;

import java.util.Set;
import java.util.function.Consumer;

/**
 * GT 官方附属扩展点 —— 本工具全部「写入游戏」的行为都发生在这里，没有任何 Mixin。
 *
 * <p>GT 在每次服务端数据包加载时（{@code CommonProxy#registerPackFinders}）按顺序调用：
 * <pre>
 * GTRecipes.recipeRemoval()                  → 清空 RECIPE_FILTERS，回调本类 removeRecipes()
 * GTRecipes.recipeAddition(dynamicDataPack)  → 产出配方时校验 RECIPE_FILTERS，回调本类 addRecipes()
 * </pre>
 * 也就是说这两个回调**每轮重载都会重新执行**，所以这里每次都从磁盘重读禁用表与草稿，
 * 而不是只在启动时读一次 —— 作者在游戏里改完文件后执行 /reload 就能看到效果。
 */
@GTAddon
public class RewAddon implements IGTAddon {

    @Override
    public GTRegistrate getRegistrate() {
        // 本工具不注册任何方块/物品，但接口要求返回一个实例。
        return RewMod.REGISTRATE;
    }

    @Override
    public String addonModId() {
        return RewMod.MOD_ID;
    }

    @Override
    public void initializeAddon() {
        // 无注册内容，无需初始化。
    }

    /**
     * 把被禁用的配方 ID 交给 GT 的过滤器。
     *
     * <p>语义：**移出加载，而非删除**。命中的 ID 在 {@code recipeAddition} 阶段被丢弃，
     * 因此机器与 JEI 都看不到它们，但 {@code config/rew/snapshot.json} 与草稿里仍保留完整内容。
     */
    @Override
    public void removeRecipes(Consumer<ResourceLocation> consumer) {
        Set<String> disabled = DisabledStore.loadIds();
        if (disabled.isEmpty()) return;
        int ok = 0;
        for (String id : disabled) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                RewMod.LOGGER.warn("[{}] disabled.json 中的 ID 非法，已忽略: {}", RewMod.MOD_ID, id);
                continue;
            }
            consumer.accept(rl);
            ok++;
        }
        RewMod.LOGGER.info("[{}] 已禁用 {} 条配方（移出加载，未删除）", RewMod.MOD_ID, ok);
    }

    /**
     * 注入编辑器产出的配方（新建 + 修改）。
     *
     * <p>走 GT 官方通路，与 GT 自己生成配方完全同链，因此随资源重载生效。
     */
    @Override
    public void addRecipes(Consumer<FinishedRecipe> provider) {
        int injected = DraftStore.injectAll(provider);
        if (injected > 0) {
            RewMod.LOGGER.info("[{}] 已注入 {} 条编辑器配方", RewMod.MOD_ID, injected);
        }
        // 这一轮已经把磁盘上的草稿写进 GT 动态包了，顺手记下指纹：
        // 随后的第一次 AddReloadListenerEvent 就不会再白跑一遍十几秒的全量重建。
        GtRecipeInjector.markRegenerated();
    }
}
