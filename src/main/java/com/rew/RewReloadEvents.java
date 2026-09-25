package com.rew;

import com.rew.data.GtRecipeInjector;

import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把「重建 GT 动态配方包」接进原版 {@code /reload}。
 *
 * <p>时机说明（这是整个修复的关键）：Forge 在
 * {@code ReloadableServerResources#loadResources} 里先组装原版监听器列表
 * （{@code tagManager, lootData, recipes, functionLibrary, advancements}），
 * 再调 {@code ForgeEventFactory.onResourceReload(...)} —— 本事件就在这里**同步**触发，
 * 最后才 {@code SimpleReloadInstance.create(...)} 让各监听器开始读资源。
 *
 * <p>因此在这个事件里改 {@code GTDynamicDataPack.CONTENTS}，
 * {@code RecipeManager} 随后读到的就是新内容；不需要把本模组做成一个 ReloadListener
 * （Forge 监听器是**追加在原版之后**的，做成监听器就来不及了）。
 *
 * <p>本类刻意放在 common 包、只依赖服务端可见的类：专用服务器同样受益。
 */
@Mod.EventBusSubscriber(modid = RewMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RewReloadEvents {

    private RewReloadEvents() {}

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        try {
            if (GtRecipeInjector.regenerateIfChanged()) {
                RewMod.LOGGER.info("[{}] 检测到禁用表/草稿变化，已在资源重载前重建 GT 配方包", RewMod.MOD_ID);
            }
        } catch (Throwable t) {
            // 绝不让本模组的失败拖垮整轮资源重载 —— 那样连原版 /reload 都用不了了。
            RewMod.LOGGER.error("[{}] 重建 GT 动态配方包失败，本轮沿用旧配方", RewMod.MOD_ID, t);
        }
    }
}
