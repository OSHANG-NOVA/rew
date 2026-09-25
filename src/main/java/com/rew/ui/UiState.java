package com.rew.ui;

/**
 * 主界面的「上次看到哪儿」记忆。
 *
 * <p>为什么需要它：进入配方编辑器再返回时，会 new 一个全新的 {@link RewEditorScreen}，
 * 上一个实例连同它内部列表的滚动位置、搜索词、选中的类型全部被丢弃 ——
 * 表现就是作者抱怨的「每次选完一个项后都回到列表顶部，不保存进度」。
 *
 * <p>做法是把这些纯 UI 状态抽到一个静态单例里：界面构造时读回来、变化时写回去。
 * 刻意**不放进 {@link com.rew.data.RecipeIndex}** —— 那是配方数据层，
 * 而这里只是「窗口滚动到哪了」这类显示状态，两者生命周期与职责都不同。
 *
 * <p>之所以是跨实例的单例，是因为界面实例每次进出都被替换；这正是要跨实例保留的东西。
 */
public final class UiState {

    private static final UiState INSTANCE = new UiState();

    private UiState() {}

    public static UiState get() {
        return INSTANCE;
    }

    /** 当前选中的配方类型 ID；null 表示还没选。 */
    public String selectedTypeId = null;

    /** 左侧类型列表的搜索词。 */
    public String typeQuery = "";

    /** 右侧配方列表的搜索词。 */
    public String recipeQuery = "";

    /** 左侧类型列表的滚动位置（像素）。 */
    public int typeScroll = 0;

    /** 右侧配方列表的滚动位置（像素）。 */
    public int recipeScroll = 0;
}
