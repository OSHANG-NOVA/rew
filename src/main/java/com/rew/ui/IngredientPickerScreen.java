package com.rew.ui;

import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.rew.data.ContentFactory;
import com.rew.data.ContentRef;
import com.rew.i18n.RewIcons;
import com.rew.i18n.RewLang;
import com.rew.search.PinyinSearch;
import com.rew.ui.widget.ScrollListWidget;
import com.rew.ui.widget.SearchBarWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 物品 / 流体选择器 —— 内置搜索面板。
 *
 * <p>填充路径（按用户确认）：
 * <ol>
 * <li><b>本面板搜索注册表</b>：按名称（含拼音）、ID、模组名过滤全部已注册物品/流体。
 * 只能按「物品 ID」选，表达不出 NBT；</li>
 * <li><b>从背包选</b>（仅物品）：列出玩家背包里的真实物品栈，带 NBT。
 * 附魔书、药水、指定耐久的工具、写了内容的成书这类东西，光有 ID 是选不出来的 ——
 * 同一个 {@code minecraft:enchanted_book} 配不同的 stored_enchantments 完全是两样东西；</li>
 * <li><b>JEI 拖入</b>：编辑器里的槽位是 LDLib 的 phantom 槽，LDLib 自己注册了 JEI 的
 * ghost ingredient handler，从 JEI 拖到槽位上即可（依赖 JEI 已安装）；</li>
 * <li><b>背包拖入</b>：同上，由 LDLib 的原生槽位交互覆盖。</li>
 * </ol>
 *
 * <p>第 1 条解决「知道要什么但手上没有」，第 2 条解决「手上就有、而且带 NBT」。
 */
public class IngredientPickerScreen extends WidgetGroup {

    private static final int MARGIN = 6;
    private static final int HEADER_H = 14;
    private static final int FIELD_H = 14;

    /** 编程电路的物品 ID。它在编辑器里有专门的「电路」配置项，不再作为普通物品出现在这里。 */
    private static final String CIRCUIT_ITEM_ID = "gtceu:programmed_circuit";

    /** 列表数据源。 */
    private enum Source {
        /** 已注册的全部物品 / 流体（按 ID 选，不含 NBT）。 */
        REGISTRY,
        /** 玩家背包里的真实物品栈（含 NBT）。仅物品可用。 */
        BACKPACK
    }

    /**
     * 一行候选项。
     *
     * <p>注册表模式下只有 {@link #id}；背包模式下 {@link #stack} 带着完整的物品栈
     * （NBT、真实数量），这是「按 ID 选」表达不出来的信息。
     */
    private static final class Entry {

        final String id;
        final ItemStack stack;

        Entry(String id, ItemStack stack) {
            this.id = id;
            this.stack = stack;
        }

        boolean hasStack() {
            return stack != null && !stack.isEmpty();
        }
    }

    private final boolean fluid;
    private final ContentRef existing;
    private final Consumer<ContentRef> onPicked;
    private final Runnable onCancel;

    private final SearchBarWidget search;
    private final ScrollListWidget<Entry> list;
    private final TextFieldWidget amountField;
    private final TextFieldWidget chanceField;

    /** 数据源切换按钮上的文字；切模式时只改它，不重建整个界面。 */
    private LabelWidget modeLabel;

    private final List<Entry> allEntries = new ArrayList<>();
    private Source source = Source.REGISTRY;

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

        // 「从背包选」只给物品加：流体的 NBT（GT 的 FluidIngredient 不支持 NBT 断言）
        // 没有对应概念，背包里也只有桶这种物品形态，加了反而误导。
        if (!fluid) {
            addWidget(makeSourceButton(w - MARGIN - 76, MARGIN - 1));
        }

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
        var cancelBtn = new ButtonWidget(0, 0, 50, FIELD_H, cd -> onCancel.run());
        cancelBtn.setButtonTexture(RewTextures.button());
        cancel.addWidget(cancelBtn);
        cancel.addWidget(new LabelWidget(6, 3, "§7取消"));
        addWidget(cancel);

        collectEntries();
        refreshList();
    }

    // ------------------------------------------------------------------ 数据源切换

    /** 「从背包选 / 从注册表选」切换按钮。 */
    private WidgetGroup makeSourceButton(int x, int y) {
        WidgetGroup g = new WidgetGroup(x, y, 76, FIELD_H);
        ButtonWidget btn = new ButtonWidget(0, 0, 76, FIELD_H, cd -> toggleSource());
        btn.setButtonTexture(RewTextures.button());
        btn.setHoverTooltips("在「已注册物品」与「背包内物品」之间切换\n"
                + "§7背包模式可以选到带 NBT 的物品（附魔书 / 药水 / 指定耐久等）");
        g.addWidget(btn);
        modeLabel = new LabelWidget(4, 3, sourceLabel());
        g.addWidget(modeLabel);
        return g;
    }

    private void toggleSource() {
        source = source == Source.REGISTRY ? Source.BACKPACK : Source.REGISTRY;
        if (modeLabel != null) modeLabel.setText(sourceLabel());
        // 切数据源等于换了一套候选，搜索词保留（作者常常先搜再切），但列表要重新收集。
        collectEntries();
        list.resetScroll();
        refreshList();
    }

    private String sourceLabel() {
        return source == Source.REGISTRY ? "§b从背包选" : "§b从注册表选";
    }

    // ------------------------------------------------------------------ 候选收集

    private void collectEntries() {
        allEntries.clear();
        if (source == Source.BACKPACK) {
            collectBackpack();
        } else {
            collectRegistry();
        }
    }

    private void collectRegistry() {
        if (fluid) {
            for (ResourceLocation rl : ForgeRegistries.FLUIDS.getKeys()) {
                allEntries.add(new Entry(rl.toString(), null));
            }
        } else {
            for (ResourceLocation rl : ForgeRegistries.ITEMS.getKeys()) {
                // 编程电路剔除：33 种变体共用同一个 ID，放这里只能选出 0 号，
                // 作者要的是「几号电路」，那是编辑器的电路配置项负责的事。
                if (CIRCUIT_ITEM_ID.equals(rl.toString())) continue;
                allEntries.add(new Entry(rl.toString(), null));
            }
        }
        allEntries.sort((a, b) -> a.id.compareTo(b.id));
    }

    /**
     * 收集背包里的物品栈。
     *
     * <p>同一物品的多个槽位会合并计数（按「ID + NBT」判同一性），否则背包里
     * 三组铁锭会显示成三行一模一样的东西。
     */
    private void collectBackpack() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        Map<String, ItemStack> merged = new LinkedHashMap<>();
        List<ItemStack> slots = new ArrayList<>();
        slots.addAll(player.getInventory().items);
        slots.addAll(player.getInventory().offhand);
        slots.addAll(player.getInventory().armor);

        for (ItemStack s : slots) {
            if (s == null || s.isEmpty()) continue;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (key == null) continue;
            // 编程电路同样剔除，理由见 collectRegistry。
            if (CIRCUIT_ITEM_ID.equals(key.toString())) continue;

            // NBT 参与判同一性：附魔不同的两本书不是同一样东西。
            String nbt = s.getTag() == null ? "" : s.getTag().toString();
            String mergeKey = key + "|" + nbt;

            ItemStack prev = merged.get(mergeKey);
            if (prev == null) {
                merged.put(mergeKey, s.copy());
            } else {
                prev.grow(s.getCount());
            }
        }

        for (ItemStack s : merged.values()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
            allEntries.add(new Entry(key == null ? "" : key.toString(), s));
        }
        allEntries.sort((a, b) -> displayNameOf(a).compareTo(displayNameOf(b)));
    }

    // ------------------------------------------------------------------ 列表

    private void refreshList() {
        String q = search.query();
        List<ScrollListWidget.RowSpec<Entry>> specs = new ArrayList<>();
        int limit = 400;
        for (Entry e : allEntries) {
            if (specs.size() >= limit) break;
            String name = displayNameOf(e);
            String hay = e.id + " " + name;
            if (!PinyinSearch.matches(hay, q)) continue;

            ScrollListWidget.RowSpec<Entry> spec =
                    new ScrollListWidget.RowSpec<>(e, labelOf(e, name), 0xFFFFFF)
                            .tooltip(tooltipOf(e, name));
            if (e.hasStack()) {
                // 直接画真实栈：附魔光效、药水颜色、耐久条都会跟着出来。
                spec.icon(e.stack);
            } else if (fluid) {
                spec.fluidIcon(RewIcons.fluidOf(e.id));
            } else {
                spec.icon(RewIcons.itemOf(e.id));
            }
            specs.add(spec);
        }
        list.setRows(specs);
    }

    /** 显示名走语言文件：有 zh_cn 条目就是中文，没有就退回物品自己的英文名。 */
    private String displayNameOf(Entry e) {
        // 背包栈用物品自己的名字：带 NBT 时它会给出「锋利 III」这种完整名，
        // 而语言文件只能给到「附魔书」。
        if (e.hasStack()) return e.stack.getHoverName().getString();
        String name = fluid ? RewLang.fluidName(e.id) : RewLang.itemName(e.id);
        return name.isEmpty() ? e.id : name;
    }

    private static String labelOf(Entry e, String name) {
        StringBuilder sb = new StringBuilder();
        if (e.hasStack() && e.stack.getCount() > 1) {
            sb.append("§f").append(e.stack.getCount()).append("x ");
        } else {
            sb.append("§f");
        }
        sb.append(name).append(" §8").append(e.id);
        // NBT 标记：让作者一眼看出这一条不是普通的同名物品。
        if (e.hasStack() && e.stack.hasTag()) sb.append(" §d[NBT]");
        return sb.toString();
    }

    private static String tooltipOf(Entry e, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("§f").append(name).append("\n§7").append(e.id);
        if (e.hasStack() && e.stack.hasTag()) {
            sb.append("\n§d含 NBT：§7").append(abbreviate(e.stack.getTag().toString(), 160));
        }
        if (e.hasStack()) {
            sb.append("\n§7背包中：").append(e.stack.getCount()).append(" 个");
        }
        sb.append("\n§8左键选取；数量与概率取下方输入框");
        return sb.toString();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ 选取

    private void pick(Entry e) {
        ContentRef ref = new ContentRef();
        ref.capability = fluid ? "fluid" : "item";
        ref.id = e.id;
        ref.amount = parseLong(amountField.getCurrentString(), 1L);
        ref.maxChance = 10000;
        ref.chance = toChance(chanceField.getCurrentString());
        // raw 留空 → 由 RecipeWriter 退化为「按 id + 数量重建」，这是新建内容的正路。
        ref.raw = "";

        if (e.hasStack()) {
            // 背包来源：留一份原栈给列表画图标 / 显示名，并让 GT 自己的序列化器
            // 生成带 NBT 的 raw（同时把 SNBT 存进持久化字段），重建时才能一个字节不丢。
            ContentFactory.applyItem(ref, e.stack, ref.amount, ref.chance, ref.maxChance);
        }
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
