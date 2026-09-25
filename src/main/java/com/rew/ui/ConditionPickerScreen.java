package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import com.rew.data.ConditionRef;
import com.rew.i18n.RewLang;
import com.rew.search.PinyinSearch;
import com.rew.ui.widget.ScrollListWidget;
import com.rew.ui.widget.SearchBarWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 条件类型选择器 —— 从 GT 已注册的全部条件类型里挑一个。
 *
 * <p>刻意做成「运行时枚举 {@code GTRegistries.RECIPE_CONDITIONS}」而不是硬编码清单：
 * 凡是注入 GT 的条件（GT 自带的 cleanroom/dimension/biome/…，以及任何附属模组加的）
 * 都会自动出现在这里，工具不需要跟着 GT 或第三方模组更新。
 */
public class ConditionPickerScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int HEADER_H = 14;
    private static final int FIELD_H = 14;

    private final Consumer<ConditionRef> onPicked;
    private final Runnable onCancel;

    private final SearchBarWidget search;
    private final ScrollListWidget<String> list;
    private final List<String> typeKeys = new ArrayList<>();

    public ConditionPickerScreen(Consumer<ConditionRef> onPicked, Runnable onCancel) {
        super(0, 0, 0, 0);
        setSize(320, 220);
        setBackground(RewTextures.panel());

        this.onPicked = onPicked;
        this.onCancel = onCancel;

        int w = 320;
        int h = 220;

        addWidget(new LabelWidget(MARGIN, MARGIN, "§b选择条件类型 §8（GT 已注册的全部条件）"));

        int top = MARGIN + HEADER_H;
        search = new SearchBarWidget(MARGIN, top, w - MARGIN * 2, FIELD_H,
                "搜索条件（中文名 / ID）", q -> refreshList());
        addWidget(search);

        list = new ScrollListWidget<>(MARGIN, top + FIELD_H + 2, w - MARGIN * 2, h - top - FIELD_H - 26);
        list.setOnClick(this::pick);
        addWidget(list);

        WidgetGroup cancel = new WidgetGroup(w - MARGIN - 50, h - 20, 50, FIELD_H);
        var cancelBtn = new com.lowdragmc.lowdraglib.gui.widget.ButtonWidget(0, 0, 50, FIELD_H,
                cd -> onCancel.run());
        cancelBtn.setButtonTexture(RewTextures.button());
        cancel.addWidget(cancelBtn);
        cancel.addWidget(new LabelWidget(6, 3, "§7取消"));
        addWidget(cancel);

        collectTypes();
        refreshList();
    }

    private void collectTypes() {
        typeKeys.clear();
        try {
            for (RecipeConditionType<?> t : GTRegistries.RECIPE_CONDITIONS) {
                String key = GTRegistries.RECIPE_CONDITIONS.getKey(t);
                if (key != null && !key.isBlank()) typeKeys.add(key);
            }
        } catch (Throwable ignored) {
            // 注册表尚未冻结等边界情况：列表留空，UI 会显示「无匹配」
        }
        typeKeys.sort(String::compareTo);
    }

    private void refreshList() {
        String q = search.query();
        List<ScrollListWidget.RowSpec<String>> specs = new ArrayList<>();
        for (String key : typeKeys) {
            String name = RewLang.conditionName(key);
            // GT 自带条件显示中文名，其他模组加的条件保持注册名，两者都可被搜索命中。
            String label = name.equals(key) ? key : name + "  §8" + key;
            if (!PinyinSearch.matches(key + " " + name, q)) continue;
            specs.add(new ScrollListWidget.RowSpec<>(key, "§f" + label, 0xFFFFFF)
                    .tooltip("点击添加该条件\n§7" + key));
        }
        list.setRows(specs);
    }

    private void pick(String key) {
        ConditionRef ref = new ConditionRef();
        ref.type = key;
        ref.reverse = false;
        ref.json = defaultJsonOf(key);
        onPicked.accept(ref);
    }

    /**
     * 生成该条件类型的默认 JSON。
     *
     * <p>做法是拿注册表里的 factory 造一个默认实例，再让它自己 serialize ——
     * 这样产出的 JSON 一定带齐该类型的所有必需字段，作者只需改值，不会因为
     * 漏字段而在还原时抛异常。
     */
    private String defaultJsonOf(String key) {
        try {
            RecipeConditionType<?> type = GTRegistries.RECIPE_CONDITIONS.get(key);
            if (type == null) return "{\"type\":\"" + key + "\"}";
            RecipeCondition<?> template = createDefault(type);
            if (template == null) return "{\"type\":\"" + key + "\"}";
            return template.serialize().toString();
        } catch (Throwable t) {
            return "{\"type\":\"" + key + "\"}";
        }
    }

    private static RecipeCondition<?> createDefault(RecipeConditionType<?> type) {
        try {
            RecipeCondition<?> c = type.factory.createDefault();
            return c == null ? null : c.createTemplate();
        } catch (Throwable t) {
            try {
                return type.factory.createDefault();
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
