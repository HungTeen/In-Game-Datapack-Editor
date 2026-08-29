package hungteen.ide.client.gui.editor;

import com.google.gson.JsonParser;
import hungteen.ide.common.network.IDENetworkHandler;
import hungteen.ide.common.network.RequestSchemaPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Codec 字段查看器（只读）。
 *
 * <p>顶部：数据包类型下拉框（支持<b>搜索</b>与<b>滚轮滚动</b>，点击空白处关闭）。
 * 主体：滚动字段树；UNION（dispatch）节点提供类型循环按钮，选中 type 后展开对应分支字段。</p>
 *
 * <p>字段树由 {@link ViewerNode} 在 {@link #rebuild()} 中渲染（折叠按钮、参考线、
 * 字段行都在此一次性构建）；{@link #render} 负责绘制 + 滚动条 + 悬浮提示。
 * 布局/配色/行为参数集中在 {@link ViewerStyle}。</p>
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 16:40
 **/
public class CodecViewerScreen extends CodecScreen {

    /** 打开查看器（由 OpenCodecPacket 调用）。 */
    public static void open(List<ResourceLocation> registryNames, String preselect) {
        Minecraft.getInstance().setScreen(new CodecViewerScreen(registryNames, preselect));
    }

    /** 字段树上的按钮（折叠），滚动/折叠时单独重建。 */
    private final List<Button> treeButtons = new ArrayList<>();

    // UNION 类型下拉
    private final NodeDropdown<ViewerNode> unionDropdown;

    private ViewerNode root;
    private int scroll = 0;

    private final LabelQueue labelQueue;
    private final List<GuideLine> guides = new ArrayList<>();
    private final List<TooltipRow> tooltipRows = new ArrayList<>();

    public CodecViewerScreen(List<ResourceLocation> registryNames, String preselect) {
        super(Component.translatable("ide.screen.viewer.title"), registryNames);
        this.selected = preselect == null ? "" : preselect;
        this.labelQueue = new LabelQueue();
        this.unionDropdown = new NodeDropdown<>(this, this::addRenderableWidget, this::removeWidget, this::refreshTree);
    }

    /** 顶部类型补全输入框（候选为全部注册表名）。 */
    @Override
    protected TypeSelector createTypeSelector() {
        List<String> names = registryNames.stream().map(ResourceLocation::toString).toList();
        return new TypeSelector(this, this::addRenderableWidget,
            ViewerStyle.LEFT_PADDING, ViewerStyle.TYPE_DROPDOWN_Y, ViewerStyle.TYPE_DROPDOWN_WIDTH,
            13, ViewerStyle.TOP_OFFSET, names, this::select);
    }

    /** 预选了注册表：直接请求 schema。 */
    @Override
    protected void onInit() {
        if (!selected.isEmpty()) {
            IDENetworkHandler.sendToServer(new RequestSchemaPacket(selected));
            setStatus(I18n.get("ide.screen.request_schema", selected));
        }
    }

    /** 重建界面。 */
    @Override
    protected void rebuild() {
        clearWidgets();
        labelQueue.clear();
        guides.clear();
        tooltipRows.clear();
        treeButtons.clear();

        // 顶部：数据包类型输入框（自动补全）
        typeSelector.setValue(selected);
        typeSelector.addToScreen(this.font);

        refreshTree();
    }

    /**
     * 只重建字段树（滚动/折叠时调用）。
     *
     * <p>顶部工具条与下拉框不受滚动影响，无需重建，避免每滚一格重建整屏控件。</p>
     */
    public void refreshTree() {
        for (Button b : treeButtons) {
            removeWidget(b);
        }
        treeButtons.clear();
        unionDropdown.removeAll();
        labelQueue.clear();
        guides.clear();
        tooltipRows.clear();

        if (root != null) {
            int yPos = ViewerStyle.TOP_OFFSET - scroll;
            root.render(this, ViewerStyle.LEFT_PADDING, yPos,
                this.width - ViewerStyle.LEFT_PADDING - ViewerStyle.RIGHT_PADDING);
        }

        // UNION 类型下拉按钮最后加入渲染列表 → 盖在其他按钮之上
        unionDropdown.addDeferredToScreen();
    }

    public void select(String name) {
        this.selected = name;
        this.root = null;
        unionDropdown.close();
        IDENetworkHandler.sendToServer(new RequestSchemaPacket(name));
        setStatus(I18n.get("ide.screen.request_schema", name));
        rebuild();
    }

    @Override
    protected void applySchema(String schemaJson) {
        try {
            this.root = ViewerNode.root(JsonParser.parseString(schemaJson).getAsJsonObject());
            unionDropdown.close();
            setStatus(I18n.get("ide.screen.loaded_schema", selected));
            scroll = 0;
            rebuild();
        } catch (Exception e) {
            setStatus(I18n.get("ide.screen.schema_error", e.getMessage()));
            rebuild();
        }
    }

    // -------------------------------------------------
    // 供 ViewerNode 调用
    // -------------------------------------------------

    public void drawLabel(int x, int y, String text) {
        labelQueue.add(x, y, text);
    }

    /** 用指定颜色绘制标签。 */
    public void drawLabel(int x, int y, String text, int color) {
        labelQueue.add(x, y, text, color);
    }

    /** 文本像素宽度（供 ViewerNode 拼接彩色片段用）。 */
    public int fontWidth(String text) {
        return this.font.width(text);
    }

    /** 该行是否与可视区相交（部分可见也算，用于裁剪标签/参考线）。 */
    public boolean isRowInView(int rowY) {
        return rowY + ViewerStyle.ROW_HEIGHT >= ViewerStyle.TOP_OFFSET
            && rowY <= this.height - ViewerStyle.BOTTOM_PADDING;
    }

    /** 该行是否完全在可视区内（用于决定是否创建按钮/提示热区）。 */
    public boolean isRowFullyVisible(int rowY) {
        return rowY >= ViewerStyle.TOP_OFFSET
            && rowY + ViewerStyle.ROW_HEIGHT <= this.height - ViewerStyle.BOTTOM_PADDING;
    }

    /** 折叠/展开小按钮。 */
    public void addToggleButton(int x, int y, int width, String text, Runnable onToggle) {
        Button b = Button.builder(Component.literal(text), btn -> onToggle.run())
            .bounds(x, y, width, ViewerStyle.TOGGLE_BUTTON_HEIGHT).build();
        addRenderableWidget(b);
        treeButtons.add(b);
    }

    // -------------------------------------------------
    // UNION 类型下拉（复用 NodeDropdown）
    // -------------------------------------------------

    /** 展开/收起某 UNION 节点的类型下拉。 */
    public void toggleUnionDropdown(ViewerNode node) {
        unionDropdown.toggle(node);
        refreshTree();
    }

    /** 该 UNION 节点的类型下拉是否展开。 */
    public boolean isUnionDropdownOpen(ViewerNode node) {
        return unionDropdown.isOpen(node);
    }

    /** 类型选择按钮（点击展开下拉）。 */
    public void addTypeButton(ViewerNode node, int x, int y, int width, String text, Runnable onClick) {
        unionDropdown.addTrigger(node, x, y, width, text, onClick);
    }

    /** 添加 UNION 类型下拉列表按钮（滚动取可见切片，延迟到最后加入渲染）。 */
    public void addUnionDropdownButtons(int x, int y, int width, List<String> names, Consumer<Integer> onSelect) {
        unionDropdown.addItems(x, y, width, names, onSelect::accept);
    }

    /** 记录一条垂直参考线（层级连接）。 */
    public void addGuideLine(int x, int y1, int y2) {
        if (y2 - y1 > 2) {
            guides.add(new GuideLine(x, y1, y2));
        }
    }

    /** 记录一个悬浮提示热区。 */
    public void addTooltipRow(int x, int y, int width, List<Component> tooltip) {
        if (tooltip != null && !tooltip.isEmpty()) {
            tooltipRows.add(new TooltipRow(x, y, width, tooltip));
        }
    }

    private record GuideLine(int x, int y1, int y2) {
    }

    private record TooltipRow(int x, int y, int width, List<Component> tooltip) {
    }

    @Override
    protected void renderFormRegion(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 字段树/下拉面板区域裁剪在 TOP_OFFSET 之下：滚动时不会与顶部工具条重叠
        graphics.enableScissor(0, ViewerStyle.TOP_OFFSET, this.width, this.height);

        // 层级参考线
        for (GuideLine g : guides) {
            graphics.vLine(g.x, g.y1, g.y2, ViewerStyle.COLOR_GUIDE);
        }
        // 字段行（每帧重绘；内容在 rebuild 时填充）
        labelQueue.render(graphics, this.font);
        drawScrollbar(graphics);

        // UNION 类型下拉背景（覆盖其下方的字段行）
        unionDropdown.renderPanels(graphics);

        graphics.disableScissor();
    }

    @Override
    protected void renderOverlays(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (unionDropdown.hasOpen()) {
            unionDropdown.renderScrollbar(graphics);
        }

        // 悬浮提示（画在最上层，多行彩色）
        for (TooltipRow r : tooltipRows) {
            if (mouseX >= r.x && mouseX <= r.x + r.width && mouseY >= r.y && mouseY <= r.y + ViewerStyle.ROW_HEIGHT) {
                graphics.renderComponentTooltip(this.font, r.tooltip, (int) mouseX, (int) mouseY);
                break;
            }
        }
    }

    /** 补全面板之外：UNION 下拉滚动 / 字段树滚动。 */
    @Override
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        if (unionDropdown.hasOpen() && unionDropdown.mouseScrolled(delta)) {
            return true;
        }
        int content = root == null ? 0 : root.height();
        int visible = this.height - ViewerStyle.TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
        scroll = DropdownUtil.clampScroll(scroll - (int)delta * ViewerStyle.SCROLL_STEP, content, visible);
        refreshTree();
        return true;
    }

    /** 补全面板之外：点击 UNION 下拉外部关闭。 */
    @Override
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (unionDropdown.handleClickOutside(mouseX, mouseY)) {
            return true;
        }
        return false;
    }

    /** 右侧滚动条（仅内容超出可视区时显示）。 */
    private void drawScrollbar(GuiGraphics graphics) {
        if (root == null) {
            return;
        }
        int content = root.height();
        int visible = this.height - ViewerStyle.TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
        int maxScroll = Math.max(0, content - visible);
        if (maxScroll <= 0) {
            return;
        }
        int trackX = this.width - ViewerStyle.SCROLLBAR_WIDTH - ViewerStyle.SCROLLBAR_RIGHT_MARGIN;
        int trackY = ViewerStyle.TOP_OFFSET;
        graphics.fill(trackX - 1, trackY, trackX + ViewerStyle.SCROLLBAR_WIDTH + 1, trackY + visible,
            ViewerStyle.COLOR_SCROLL_TRACK);
        int thumbH = Math.max(ViewerStyle.ROW_HEIGHT, visible * visible / content);
        int thumbY = trackY + (int)((long)scroll * (visible - thumbH) / maxScroll);
        graphics.fill(trackX, thumbY, trackX + ViewerStyle.SCROLLBAR_WIDTH, thumbY + thumbH,
            ViewerStyle.COLOR_SCROLL_THUMB);
    }
}
