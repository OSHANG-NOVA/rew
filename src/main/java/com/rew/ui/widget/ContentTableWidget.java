package com.rew.ui.widget;

import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.rew.data.ContentRef;
import com.rew.i18n.RewIcons;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 内容表控件 —— 「左输入表 / 右输出表」共用的那张表。
 *
 * <p>结构（按用户确认的形态）：
 * <pre>
 * ┌──────────────────────────┐
 * │ 物品                     │   ← 分段标题
 * │  ▸ 1x minecraft:iron_ingot  [概率 100%] [×] │
 * │  ▸ 2x #forge:ingots/iron    [概率  50%] [×] │
 * │ [+ 添加物品]             │
 * │ 流体                     │
 * │  ▸ 1000 mB minecraft:water  [概率 100%] [×] │
 * │ [+ 添加流体]             │
 * └──────────────────────────┘
 * </pre>
 *
 * <p>每张表内部把物品与流体分开两段，而不是混排成一张带「类型列」的表 ——
 * 因为 GT 配方的 item 与 fluid 是两类独立 capability，混排会让导出与校验都产生歧义。
 */
public class ContentTableWidget extends WidgetGroup {

    /** 行高要容纳 16px 的物品/流体贴图。 */
    private static final int ROW_H = 18;
    private static final int HEADER_H = 12;
    private static final int ADD_BTN_H = 14;

    private final String title;
    private final boolean inputSide;
    private final List<ContentRef> itemContents = new ArrayList<>();
    private final List<ContentRef> fluidContents = new ArrayList<>();

    /** 点击某行内容 → 请求外部打开选择器替换它。 */
    private Consumer<ContentRef> onEditContent = null;
    /** 点击「+」 → 请求外部打开选择器新增。参数 true=物品，false=流体。 */
    private Consumer<Boolean> onAddContent = null;

    /** 每行的控件句柄，用于原地刷新而不整表重建。 */
    private final List<WidgetGroup> rowWidgets = new ArrayList<>();

    public ContentTableWidget(int x, int y, int width, int height,
                              String title, boolean inputSide,
                              List<ContentRef> initialItems, List<ContentRef> initialFluids) {
        super(x, y, width, height);
        this.title = title;
        this.inputSide = inputSide;
        if (initialItems != null) itemContents.addAll(initialItems);
        if (initialFluids != null) fluidContents.addAll(initialFluids);
    }

    public ContentTableWidget setOnEditContent(Consumer<ContentRef> handler) {
        this.onEditContent = handler;
        return this;
    }

    public ContentTableWidget setOnAddContent(Consumer<Boolean> handler) {
        this.onAddContent = handler;
        return this;
    }

    public List<ContentRef> items() {
        return itemContents;
    }

    public List<ContentRef> fluids() {
        return fluidContents;
    }

    public boolean isInputSide() {
        return inputSide;
    }

    public String tableTitle() {
        return title;
    }

    public void addItem(ContentRef ref) {
        itemContents.add(ref);
        rebuild();
    }

    public void addFluid(ContentRef ref) {
        fluidContents.add(ref);
        rebuild();
    }

    public void removeContent(ContentRef ref) {
        if (!itemContents.remove(ref)) {
            fluidContents.remove(ref);
        }
        rebuild();
    }

    /**
     * 设置本表的编程电路配置号；传 null 表示这张表不需要电路。
     *
     * <p>为什么电路不走「物品表的一行」：GT 的 33 种电路变体共用同一个物品 ID，
     * 只有 NBT 里的配置号不同。放进物品表里，33 种会长得一模一样、也无法一眼看出是几号。
     * 因此把它抽成编辑器顶部的一个数字输入项，这里只负责在底层列表里增删那一条。
     */
    public void setCircuit(Integer config) {
        itemContents.removeIf(r -> r.circuit);
        if (config != null) {
            itemContents.add(ContentRef.ofCircuit(config));
        }
        rebuild();
    }

    /** 本表当前的电路配置号；没有电路时返回 null。 */
    public Integer circuit() {
        for (ContentRef r : itemContents) {
            if (r.circuit) return r.circuitConfig;
        }
        return null;
    }

    public void replaceContent(ContentRef oldRef, ContentRef newRef) {
        int idx = itemContents.indexOf(oldRef);
        if (idx >= 0) {
            itemContents.set(idx, newRef);
        } else {
            idx = fluidContents.indexOf(oldRef);
            if (idx >= 0) fluidContents.set(idx, newRef);
        }
        rebuild();
    }

    /** 全量重建控件树。行数很小（GT 单类型上限通常 < 20），重建比增量 diff 更可靠。 */
    public void rebuild() {
        clearAllWidgets();

        int y = 0;
        addWidget(new LabelWidget(0, y, "§f" + title));
        y += HEADER_H;

        y = buildSection(y, "物品", itemContents, true);

        // 物品段与流体段之间的横线。
        addWidget(SeparatorWidget.horizontal(2, y, getSize().width - 4));
        y += 3;

        y = buildSection(y, "流体", fluidContents, false);
    }

    private int buildSection(int startY, String sectionName, List<ContentRef> contents, boolean isItem) {
        int y = startY;

        // 编程电路不在表里占一行：它由编辑器顶部的「电路」配置项统一管理。
        // 33 种变体共用一个物品 ID，混进物品表里只能显示成同一个样子，没有可读性；
        // 数据仍留在底层列表里，保存时照常写回，只是不在这里渲染。
        List<ContentRef> visible = new ArrayList<>(contents.size());
        int circuits = 0;
        for (ContentRef ref : contents) {
            if (ref.circuit) {
                circuits++;
                continue;
            }
            visible.add(ref);
        }
        String countText = circuits > 0
                ? visible.size() + " + 电路×" + circuits
                : String.valueOf(visible.size());
        addWidget(new LabelWidget(2, y, "§7" + sectionName + " §8(" + countText + ")"));
        y += HEADER_H;

        for (ContentRef ref : visible) {
            WidgetGroup row = buildRow(y, ref, isItem);
            addWidget(row);
            rowWidgets.add(row);
            y += ROW_H;
        }

        // 「+ 添加」按钮：把该分段的能力类型告诉外部，由外部决定开物品还是流体选择器。
        ButtonWidget addBtn = new ButtonWidget(2, y, getSize().width - 4, ADD_BTN_H,
                cd -> {
                    if (onAddContent != null) onAddContent.accept(isItem);
                });
        addBtn.setButtonTexture(com.rew.ui.RewTextures.button());
        addBtn.setHoverTooltips("添加" + sectionName + "（点击后选择" + sectionName + "）");
        addWidget(addBtn);
        addWidget(new LabelWidget(6, y + 3, "§a+ 添加" + sectionName));
        y += ADD_BTN_H + 4;
        return y;
    }

    private WidgetGroup buildRow(int y, ContentRef ref, boolean isItem) {
        WidgetGroup row = new WidgetGroup(0, y, getSize().width, ROW_H);

        // 行首贴图：物品画物品，流体画流体自己的贴图。
        row.addWidget(new ContentIcon(1, (ROW_H - 16) / 2, ref, isItem));

        // 行主体：显示内容摘要（数量 + 中文名 + 概率），文字让出图标的位置。
        String summary = ref.display();
        if (ref.tag) summary = "§b[标签] §r" + summary;
        row.addWidget(new LabelWidget(20, 4, "§f" + summary));

        // 编辑按钮（点行本身也能编辑，这里额外给个明确的按钮）
        ButtonWidget editBtn = new ButtonWidget(2, 1, getSize().width - 26, ROW_H - 2,
                cd -> {
                    if (onEditContent != null) onEditContent.accept(ref);
                });
        editBtn.setButtonTexture(com.rew.ui.RewTextures.transparent());
        editBtn.setHoverTooltips("点击编辑该" + (isItem ? "物品" : "流体") + "（数量 / 概率 / 替换）");
        row.addWidget(editBtn);

        // 删除按钮
        ButtonWidget delBtn = new ButtonWidget(getSize().width - 18, 1, 16, ROW_H - 2,
                cd -> removeContent(ref));
        delBtn.setButtonTexture(com.rew.ui.RewTextures.button());
        delBtn.setHoverTooltips("§c删除这一行");
        row.addWidget(delBtn);
        row.addWidget(new LabelWidget(getSize().width - 15, 3, "§c×"));

        return row;
    }

    /**
     * 内容行的图标。
     *
     * <p>物品取物品贴图；流体走 LDLib 的流体绘制（和 GT 流体槽同一条路径），不画成桶。
     */
    private static final class ContentIcon extends WidgetGroup {

        private final ContentRef ref;
        private final boolean item;

        ContentIcon(int x, int y, ContentRef ref, boolean item) {
            super(x, y, 16, 16);
            this.ref = ref;
            this.item = item;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = getPosition().x;
            int y = getPosition().y;
            if (item) {
                ItemStack stack = ref.iconItem();
                if (!stack.isEmpty()) {
                    DrawerHelper.drawItemStack(graphics, stack, x, y, -1, null);
                }
            } else {
                FluidStack stack = RewIcons.fluidOf(ref.id);
                if (!stack.isEmpty()) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(0, 0, 100);
                    DrawerHelper.drawFluidForGui(graphics,
                            com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                                    stack.getFluid(), stack.getAmount()),
                            x, y, 16, 16);
                    graphics.pose().popPose();
                }
            }
        }
    }
}
