package hungteen.ide.client.gui.editor;

import com.google.gson.JsonParser;
import hungteen.ide.common.network.IDENetworkHandler;
import hungteen.ide.common.network.RequestSchemaPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public class CodecViewerScreen extends Screen {

    /** 打开查看器（由 OpenViewerPacket 调用）。 */
    public static void open(List<String> registryNames, String preselect) {
        Minecraft.getInstance().setScreen(new CodecViewerScreen(registryNames, preselect));
    }

    /** 收到 schema（由 SchemaResponsePacket 转发）。 */
    public static void onSchema(String registryName, String schemaJson) {
        if (Minecraft.getInstance().screen instanceof CodecViewerScreen viewer && viewer.selected.equals(
            registryName)) {
            viewer.applySchema(schemaJson);
        }
    }

    private final List<String> registryNames;
    private String selected;
    private boolean dropdownOpen = false;
    private String dropdownFilter = "";
    private int dropdownScroll = 0;
    private EditBox searchBox;
    private final List<Button> dropdownButtons = new ArrayList<>();

    private ViewerNode root;
    private int scroll = 0;
    private String status = "";
    /** 状态提示的过期时间戳（毫秒），过期后自动清空。 */
    private long statusUntil = Long.MAX_VALUE;

    private final List<Label> labels = new ArrayList<>();
    private final List<GuideLine> guides = new ArrayList<>();
    private final List<TooltipRow> tooltipRows = new ArrayList<>();

    public CodecViewerScreen(List<String> registryNames, String preselect) {
        super(Component.literal("Codec 字段查看器"));
        this.registryNames = new ArrayList<>(registryNames);
        this.selected = preselect == null ? "" : preselect;
    }

    @Override
    protected void init() {
        rebuild();
        // 预选了注册表：直接请求 schema。
        if (!selected.isEmpty()) {
            IDENetworkHandler.sendToServer(new RequestSchemaPacket(selected));
            setStatus("请求 " + selected + " schema...");
        }
    }

    /** 设置状态提示（短暂展示后自动消失）。 */
    private void setStatus(String text) {
        this.status = text;
        this.statusUntil = Util.getMillis() + ViewerStyle.STATUS_TIMEOUT_MS;
    }

    /** 重建界面。 */
    public void rebuild() {
        clearWidgets();
        labels.clear();
        guides.clear();
        tooltipRows.clear();

        // 顶部下拉框按钮
        Button typeButton = Button.builder(
            Component.literal("数据包类型: " + (selected.isEmpty() ? "(未选择)" : selected)), b -> {
                dropdownOpen = !dropdownOpen;
                if (!dropdownOpen) {
                    dropdownFilter = "";
                    dropdownScroll = 0;
                }
                rebuild();
            }).bounds(ViewerStyle.LEFT_PADDING, ViewerStyle.TYPE_DROPDOWN_Y,
                ViewerStyle.TYPE_DROPDOWN_WIDTH, ViewerStyle.TYPE_DROPDOWN_HEIGHT).build();
        addRenderableWidget(typeButton);

        if (dropdownOpen) {
            rebuildSearchBox();
            rebuildDropdownList();
            // 打开时自动聚焦搜索框
            if (searchBox != null) {
                setFocused(searchBox);
                searchBox.setFocused(true);
            }
        } else if (root != null) {
            int yPos = ViewerStyle.TOP_OFFSET - scroll;
            root.render(this, ViewerStyle.LEFT_PADDING, yPos,
                this.width - ViewerStyle.LEFT_PADDING - ViewerStyle.RIGHT_PADDING);
        }
    }

    /** 创建/复用搜索框（复用实例以保持输入焦点）。 */
    private void rebuildSearchBox() {
        if (searchBox == null) {
            searchBox = new EditBox(this.font, ViewerStyle.LEFT_PADDING, ViewerStyle.TOP_OFFSET,
                ViewerStyle.TYPE_DROPDOWN_WIDTH, ViewerStyle.DROPDOWN_SEARCH_HEIGHT, Component.literal("搜索"));
            searchBox.setMaxLength(64);
            searchBox.setResponder(text -> {
                dropdownFilter = text;
                dropdownScroll = 0;
                rebuildDropdownList();
            });
        }
        addRenderableWidget(searchBox);
        searchBox.setValue(dropdownFilter);
    }

    /** 只重建下拉列表按钮（不动搜索框，避免失焦）。 */
    private void rebuildDropdownList() {
        for (Button b : dropdownButtons) {
            removeWidget(b);
        }
        dropdownButtons.clear();

        List<String> filtered = filteredRegistryNames();
        int maxRows = dropdownMaxRows();
        dropdownScroll = Math.max(0, Math.min(dropdownScroll, Math.max(0, filtered.size() - maxRows)));

        int y = dropdownListTop();
        int end = Math.min(filtered.size(), dropdownScroll + maxRows);
        for (int i = dropdownScroll; i < end; i++) {
            final String name = filtered.get(i);
            Button item = Button.builder(Component.literal(name), btn -> select(name))
                .bounds(ViewerStyle.LEFT_PADDING, y, ViewerStyle.TYPE_DROPDOWN_WIDTH, ViewerStyle.FIELD_ROW).build();
            addRenderableWidget(item);
            dropdownButtons.add(item);
            y += ViewerStyle.FIELD_ROW + ViewerStyle.FIELD_GAP;
        }
    }

    /** 按过滤条件筛选后的注册表名列表。 */
    private List<String> filteredRegistryNames() {
        String filter = dropdownFilter == null ? "" : dropdownFilter.trim().toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) {
            return registryNames;
        }
        return registryNames.stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).contains(filter))
            .toList();
    }

    private void select(String name) {
        this.selected = name;
        this.root = null;
        dropdownOpen = false;
        dropdownFilter = "";
        dropdownScroll = 0;
        IDENetworkHandler.sendToServer(new RequestSchemaPacket(name));
        setStatus("请求 " + name + " schema...");
        rebuild();
    }

    private void applySchema(String schemaJson) {
        try {
            this.root = ViewerNode.root(JsonParser.parseString(schemaJson).getAsJsonObject());
            setStatus("已加载 " + selected);
            scroll = 0;
            rebuild();
        } catch (Exception e) {
            setStatus("schema 解析失败: " + e.getMessage());
            rebuild();
        }
    }

    // -------------------------------------------------
    // 下拉面板几何
    // -------------------------------------------------

    private int dropdownPanelLeft() {
        return ViewerStyle.LEFT_PADDING - ViewerStyle.DROPDOWN_PANEL_MARGIN;
    }

    private int dropdownPanelRight() {
        return ViewerStyle.LEFT_PADDING + ViewerStyle.TYPE_DROPDOWN_WIDTH + ViewerStyle.DROPDOWN_PANEL_MARGIN;
    }

    private int dropdownListTop() {
        return ViewerStyle.TOP_OFFSET + ViewerStyle.DROPDOWN_SEARCH_HEIGHT + ViewerStyle.DROPDOWN_SEARCH_GAP;
    }

    private int dropdownPanelBottom() {
        return this.height - ViewerStyle.BOTTOM_PADDING;
    }

    private int dropdownMaxRows() {
        return Math.max(0, (dropdownPanelBottom() - dropdownListTop())
            / (ViewerStyle.FIELD_ROW + ViewerStyle.FIELD_GAP));
    }

    // -------------------------------------------------
    // 供 ViewerNode 调用
    // -------------------------------------------------

    public void drawLabel(int x, int y, String text) {
        drawLabel(x, y, text, ViewerStyle.COLOR_TEXT);
    }

    /** 用指定颜色绘制标签。 */
    public void drawLabel(int x, int y, String text, int color) {
        labels.add(new Label(x, y, text, color));
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

    public void addCycleButton(int x, int y, int width, List<String> values, int index, Consumer<Integer> onSelect) {
        String current = index >= 0 && index < values.size() ? values.get(index) : "";
        Button b = Button.builder(Component.literal(current), btn -> {
            int next = (index + 1) % Math.max(1, values.size());
            onSelect.accept(next);
        }).bounds(x, y, width, ViewerStyle.CYCLE_BUTTON_HEIGHT).build();
        addRenderableWidget(b);
    }

    /** 折叠/展开小按钮。 */
    public void addToggleButton(int x, int y, int width, String text, Runnable onToggle) {
        Button b = Button.builder(Component.literal(text), btn -> onToggle.run())
            .bounds(x, y, width, ViewerStyle.TOGGLE_BUTTON_HEIGHT).build();
        addRenderableWidget(b);
    }

    /** 记录一条垂直参考线（层级连接）。 */
    public void addGuideLine(int x, int y1, int y2) {
        if (y2 - y1 > 2) {
            guides.add(new GuideLine(x, y1, y2));
        }
    }

    /** 记录一个悬浮提示热区。 */
    public void addTooltipRow(int x, int y, int width, String tooltip) {
        if (tooltip != null && !tooltip.isEmpty()) {
            tooltipRows.add(new TooltipRow(x, y, width, tooltip));
        }
    }

    private static class Label {
        final int x;
        final int y;
        final String text;
        final int color;

        Label(int x, int y, String text, int color) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
        }
    }

    private record GuideLine(int x, int y1, int y2) {
    }

    private record TooltipRow(int x, int y, int width, String tooltip) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // 字段树/下拉面板区域裁剪在 TOP_OFFSET 之下：滚动时不会与顶部工具条重叠
        graphics.enableScissor(0, ViewerStyle.TOP_OFFSET, this.width, this.height - ViewerStyle.TOP_OFFSET);

        if (dropdownOpen) {
            drawDropdownBackground(graphics);
        } else {
            // 层级参考线
            for (GuideLine g : guides) {
                graphics.vLine(g.x, g.y1, g.y2, ViewerStyle.COLOR_GUIDE);
            }
            // 字段行（每帧重绘；内容在 rebuild 时填充）
            for (Label l : labels) {
                graphics.drawString(this.font, l.text, l.x, l.y, l.color);
            }
            drawScrollbar(graphics);
        }

        graphics.disableScissor();

        // 状态提示（短暂展示后自动消失）
        if (!status.isEmpty() && Util.getMillis() >= statusUntil) {
            status = "";
        }
        if (!status.isEmpty()) {
            graphics.drawString(this.font, status, ViewerStyle.LEFT_PADDING,
                this.height - ViewerStyle.STATUS_BOTTOM, ViewerStyle.COLOR_STATUS);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (dropdownOpen) {
            drawDropdownScrollbar(graphics);
        }

        // 悬浮提示（画在最上层）
        for (TooltipRow r : tooltipRows) {
            if (mouseX >= r.x && mouseX <= r.x + r.width && mouseY >= r.y && mouseY <= r.y + ViewerStyle.ROW_HEIGHT) {
                graphics.renderComponentTooltip(this.font, List.of(Component.literal(r.tooltip)),
                    (int) mouseX, (int) mouseY);
                break;
            }
        }
    }

    /** 下拉面板背景 + 边框。 */
    private void drawDropdownBackground(GuiGraphics graphics) {
        int x1 = dropdownPanelLeft();
        int x2 = dropdownPanelRight();
        int y1 = ViewerStyle.TOP_OFFSET - ViewerStyle.DROPDOWN_PANEL_MARGIN;
        int y2 = dropdownPanelBottom() + ViewerStyle.DROPDOWN_PANEL_MARGIN;
        graphics.fill(x1, y1, x2, y2, ViewerStyle.COLOR_DROPDOWN_BG);
        graphics.hLine(x1, x2, y1, ViewerStyle.COLOR_DROPDOWN_BORDER);
        graphics.hLine(x1, x2, y2, ViewerStyle.COLOR_DROPDOWN_BORDER);
        graphics.vLine(x1, y1, y2, ViewerStyle.COLOR_DROPDOWN_BORDER);
        graphics.vLine(x2, y1, y2, ViewerStyle.COLOR_DROPDOWN_BORDER);
    }

    /** 下拉列表滚动条（画在按钮之上）。 */
    private void drawDropdownScrollbar(GuiGraphics graphics) {
        List<String> filtered = filteredRegistryNames();
        int maxRows = dropdownMaxRows();
        int maxScroll = Math.max(0, filtered.size() - maxRows);
        if (maxScroll <= 0) {
            return;
        }
        int trackX = dropdownPanelRight() - ViewerStyle.SCROLLBAR_WIDTH - ViewerStyle.SCROLLBAR_RIGHT_MARGIN;
        int trackY = dropdownListTop();
        int trackH = dropdownPanelBottom() - trackY;
        graphics.fill(trackX - 1, trackY, trackX + ViewerStyle.SCROLLBAR_WIDTH + 1, trackY + trackH,
            ViewerStyle.COLOR_SCROLL_TRACK);
        int thumbH = Math.max(ViewerStyle.ROW_HEIGHT, trackH * maxRows / Math.max(1, filtered.size()));
        int thumbY = trackY + (int)((long)dropdownScroll * (trackH - thumbH) / maxScroll);
        graphics.fill(trackX, thumbY, trackX + ViewerStyle.SCROLLBAR_WIDTH, thumbY + thumbH,
            ViewerStyle.COLOR_SCROLL_THUMB);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dropdownOpen) {
            List<String> filtered = filteredRegistryNames();
            int maxRows = dropdownMaxRows();
            int maxScroll = Math.max(0, filtered.size() - maxRows);
            dropdownScroll = Math.max(0,
                Math.min(maxScroll, dropdownScroll - (int)delta * ViewerStyle.DROPDOWN_SCROLL_STEP));
            rebuildDropdownList();
            return true;
        }
        int content = root == null ? 0 : root.height();
        int visible = this.height - ViewerStyle.TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
        int maxScroll = Math.max(0, content - visible);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int)delta * ViewerStyle.SCROLL_STEP));
        rebuild();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击下拉面板外关闭
        if (dropdownOpen && !isInsideDropdown(mouseX, mouseY)) {
            dropdownOpen = false;
            dropdownFilter = "";
            dropdownScroll = 0;
            rebuild();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isInsideDropdown(double x, double y) {
        return x >= dropdownPanelLeft() && x <= dropdownPanelRight()
            && y >= ViewerStyle.TOP_OFFSET - ViewerStyle.DROPDOWN_PANEL_MARGIN
            && y <= dropdownPanelBottom() + ViewerStyle.DROPDOWN_PANEL_MARGIN;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
