package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import java.util.function.Consumer;

/**
 * 搜索栏：一个输入框 + 即时过滤回调。
 *
 * <p>过滤逻辑走 {@link com.rew.search.PinyinSearch}，因此装了 JustEnoughCharacters 时
 * 支持拼音/首字母搜索（「zhongxinji」能命中「离心机」），没装则退化为普通子串匹配。
 */
public class SearchBarWidget extends WidgetGroup {

    private final TextFieldWidget field;
    private String lastQuery = "";

    public SearchBarWidget(int x, int y, int width, int height,
                           String placeholder, Consumer<String> onChanged) {
        super(x, y, width, height);
        this.field = new TextFieldWidget(0, 0, width, height,
                () -> lastQuery,
                value -> {
                    lastQuery = value == null ? "" : value;
                    if (onChanged != null) onChanged.accept(lastQuery);
                });
        this.field.setClientSideWidget();
        this.field.setCurrentString("");
        this.field.setHoverTooltips(placeholder);
        addWidget(this.field);
    }

    public String query() {
        return lastQuery;
    }

    /**
     * 回填搜索词。
     *
     * <p>用于「从配方编辑器返回主界面」：新界面实例要把上次的搜索词恢复出来，
     * 否则作者每次进出都要重打一遍。
     */
    public void setQuery(String q) {
        String value = q == null ? "" : q;
        lastQuery = value;
        field.setCurrentString(value);
    }

    public void clear() {
        lastQuery = "";
        field.setCurrentString("");
    }

    /** 供调用方主动触发一次过滤（打开界面时让初始列表就位）。 */
    public TextFieldWidget field() {
        return field;
    }
}
