package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import com.rew.data.ContentRef;
import com.rew.i18n.RewIcons;
import com.rew.i18n.RewLang;
import com.rew.search.PinyinSearch;
import com.rew.ui.widget.ScrollListWidget;
import com.rew.ui.widget.SearchBarWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 物品 / 流体选择器 —— 内置搜索面板。
 *
 * <p>三条填充路径（按用户确认）：
 * <ol>
 * <li><b>本面板搜索</b>：按名称（含拼音）、ID、模组名过滤全部已注册物品/流体；</li>
 * <li><b>JEI 拖入</b>：编辑器里的槽位是 LDLib 的 phantom 槽，LDLib 自己注册了 JEI 的
 * ghost ingredient handler，从 JEI 拖到槽位上即可（依赖 JEI 已安装）；</li>
 * <li><b>背包拖入</b>：同上，由 LDLib 的原生槽位交互覆盖。</li>
 * </ol>
 *
 * <p>本面板解决的是「知道要什么但手上没有」的场景 —— 这是三者中最常用的一条。
 */
public class IngredientPickerScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int HEADER_H = 14;
    private static final int FIELD_H = 14;

    private final boolean fluid;
    private final ContentRef existing;
    private final Consumer<ContentRef> onPicked;
    private final Runnable onCancel;

    private final SearchBarWidget search;
    private final ScrollListWidget<String> list;
    private final TextFieldWidget amountField;
    private final TextFieldWidget chanceField;

    private final List<String> allIds = new ArrayList<>();

    public IngredientPickerScreen(boolean fluid, ContentRef existing,
                                  Consumer<ContentRef> onPicked, Runnable onCancel) {
        super(0, 0, 0, 0);
        setBackground(RewTextures.panel());

        this.fluid = fluid;
        this.existing = existing;
        this.onPicked = onPicked;
        this.onCancel = onCancel;

        // 自适应：与其他界面一致，按当前 GUI 缩放下的真实屏幕尺寸布局。
        int w = UiOpenHelper.screenWidth();
        int h = UiOpenHelper.screenHeight();
        setSize(w, h);

        addWidget(new LabelWidget(MARGIN, MARGIN,
                "§b选择" + (fluid ? "流体" : "物品") + " §8（支持拼音搜索）"));

        int top = MARGIN + HEADER_H;
        search = new SearchBarWidget(MARGIN, top, w - MARGIN * 2, FIELD_H,
                fluid ? "搜索流体" : "搜索物品", q -> refreshList());
        addWidget(search);

        list = new ScrollListWidget<>(MARGIN, top + FIELD_H + 2, w - MARGIN * 2,
                h - top - FIELD_H - 2 - 44);
        list.setOnClick(this::pick);
        addWidget(list);

        int fy = h - 40;
        addWidget(new LabelWidget(MARGIN, fy + 3, "§7数量"));
        amountField = new TextFieldWidget(MARGIN + 34, fy, 60, FIELD_H,
                () -> String.valueOf(existing == null ? 1L : existing.amount),
                v -> {});
        amountField.setClientSideWidget();
        amountField.setNumbersOnly(1, Integer.MAX_VALUE);
        addWidget(amountField);

        addWidget(new LabelWidget(MARGIN + 100, fy + 3, "§7概率%"));
        chanceField = new TextFieldWidget(MARGIN + 140, fy, 50, FIELD_H,
                () -> existing == null || existing.isCertain() ? "100"
                        : String.format("%.1f", existing.chance * 100.0 / existing.maxChance),
                v -> {});
        chanceField.setClientSideWidget();
        // GT 的语义：输入侧 chance==0 = 不消耗（催化剂），而不是「永远不产出」。
        // 不写清这一点，作者填 0 之后会以为这条输入被丢掉了。
        chanceField.setHoverTooltips("概率百分比；填 0 表示不消耗（催化剂，如编程电路、模具）");
        addWidget(chanceField);

        WidgetGroup cancel = new WidgetGroup(w - MARGIN - 50, fy, 50, FIELD_H);
        var cancelBtn = new com.lowdragmc.lowdraglib.gui.widget.ButtonWidget(0, 0, 50, FIELD_H,
                cd -> onCancel.run());
        cancelBtn.setButtonTexture(RewTextures.button());
        cancel.addWidget(cancelBtn);
        cancel.addWidget(new LabelWidget(6, 3, "§7取消"));
        addWidget(cancel);

        collectIds();
        refreshList();
    }

    /** 编程电路的物品 ID。它在编辑器里有专门的「电路」配置项，不再作为普通物品出现在这里。 */
    private static final String CIRCUIT_ITEM_ID = "gtceu:programmed_circuit";

    private void collectIds() {
        allIds.clear();
        if (fluid) {
            for (ResourceLocation rl : ForgeRegistries.FLUIDS.getKeys()) {
                allIds.add(rl.toString());
            }
        } else {
            for (ResourceLocation rl : ForgeRegistries.ITEMS.getKeys()) {
                // 编程电路剔除：33 种变体共用同一个 ID，放这里只能选出 0 号，
                // 作者要的是「几号电路」，那是编辑器的电路配置项负责的事。
                if (CIRCUIT_ITEM_ID.equals(rl.toString())) continue;
                allIds.add(rl.toString());
            }
        }
        allIds.sort(String::compareTo);
    }

    private void refreshList() {
        String q = search.query();
        List<ScrollListWidget.RowSpec<String>> specs = new ArrayList<>();
        int limit = 400;
        for (String id : allIds) {
            if (specs.size() >= limit) break;
            String hay = id + " " + displayNameOf(id);
            if (!PinyinSearch.matches(hay, q)) continue;
            ScrollListWidget.RowSpec<String> spec = new ScrollListWidget.RowSpec<>(id,
                    "§f" + displayNameOf(id) + " §8" + id, 0xFFFFFF)
                    .tooltip(displayNameOf(id) + "\n§7" + id);
            // 流体画流体自己的贴图，物品（含流体桶）画物品贴图。
            if (fluid) spec.fluidIcon(RewIcons.fluidOf(id));
            else spec.icon(RewIcons.itemOf(id));
            specs.add(spec);
        }
        list.setRows(specs);
    }

    /** 显示名走语言文件：有 zh_cn 条目就是中文，没有就退回物品自己的英文名。 */
    private String displayNameOf(String id) {
        String name = fluid ? RewLang.fluidName(id) : RewLang.itemName(id);
        return name.isEmpty() ? id : name;
    }

    private void pick(String id) {
        ContentRef ref = new ContentRef();
        ref.capability = fluid ? "fluid" : "item";
        ref.id = id;
        ref.amount = parseLong(amountField.getCurrentString(), 1L);
        ref.maxChance = 10000;
        ref.chance = toChance(chanceField.getCurrentString());
        // raw 留空 → 由 RecipeWriter 退化为「按 id + 数量重建」，这是新建内容的正路。
        ref.raw = "";
        onPicked.accept(ref);
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int toChance(String percentText) {
        try {
            double pct = Double.parseDouble(percentText.trim());
            int c = (int) Math.round(pct * 100.0);
            return Math.max(0, Math.min(10000, c));
        } catch (Exception e) {
            return 10000;
        }
    }
}
