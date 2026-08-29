package hungteen.ide.client.gui.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hungteen.ide.common.network.IDENetworkHandler;
import hungteen.ide.common.network.RequestSchemaPacket;
import hungteen.ide.common.network.SaveDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Codec 编辑器界面。
 *
 * <p>顶部工具条：数据包类型下拉框（支持搜索）、表单/JSON 模式切换、保存路径与文件名、保存按钮。
 * 主体区域：表单或 JSON 文本视图。表单支持滚轮滚动与右键拖拽平移。</p>
 *
 * @author PangTeen
 * @create 2026/8/10 14:15
 **/
public class CodecEditorScreen extends Screen {

    /** 打开编辑器界面（由 OpenEditorPacket 调用）。 */
    public static void open(List<String> registryNames) {
        Minecraft.getInstance().setScreen(new CodecEditorScreen(registryNames));
    }

    /** 收到某注册表的 schema（由 SchemaResponsePacket 调用）。 */
    public static void onSchema(String registryName, String schemaJson) {
        if (Minecraft.getInstance().screen instanceof CodecEditorScreen editor && editor.selected.equals(registryName)) {
            editor.applySchema(schemaJson);
        }
    }

    private static final int TOP_OFFSET = 20;
    private static final int FIELD_ROW = ViewerStyle.FIELD_ROW;
    private static final int FIELD_GAP = ViewerStyle.FIELD_GAP;
    /** 顶部类型下拉框宽度（编辑器工具条更紧凑）。 */
    private static final int TYPE_DROPDOWN_WIDTH = 150;
    /** 顶部下拉搜索框高度 / 与列表间距。 */
    private static final int DROPDOWN_SEARCH_HEIGHT = 13;
    private static final int DROPDOWN_SEARCH_GAP = 2;

    private final List<String> registryNames;
    private String selected = "";

    // 状态
    private boolean dropdownOpen = false;
    private int dropdownScroll = 0;
    private String dropdownFilter = "";
    private boolean jsonMode = false;
    private JsonObject schema;
    private EditorFormNode formRoot;
    private String savePath = "datapack:htlib_editor/data";
    private String saveFileName = "data.json";
    private int jsonScroll = 0;
    private int formScroll = 0;
    private int panX = 0;

    // 右键拖拽平移
    private boolean panning = false;
    private double panLastX, panLastY;

    // 顶部数据包下拉
    private EditBox searchBox;
    private boolean ignoreSearchResponder = false;
    private final List<Button> dropdownButtons = new ArrayList<>();

    // UNION 类型下拉
    private final NodeDropdown<EditorFormNode> unionDropdown;

    private final LabelQueue labelQueue;
    private final List<TooltipRow> tooltipRows = new ArrayList<>();

    private record TooltipRow(int x, int y, int width, int rowHeight, List<Component> tooltip) {
    }

    private String status = "";

    public CodecEditorScreen(List<String> registryNames) {
        super(Component.translatable("ide.screen.editor.title"));
        this.registryNames = new ArrayList<>(registryNames);
        this.labelQueue = new LabelQueue();
        this.unionDropdown = new NodeDropdown<>(this, this::addRenderableWidget, this::removeWidget, this::rebuild);
    }

    @Override
    protected void init() {
        rebuild();
    }

    /** 全量重建界面：顶部工具条 → 主体 → 顶部下拉（含搜索）→ UNION 下拉按钮。 */
    private void rebuild() {
        clearWidgets();
        dropdownButtons.clear();
        unionDropdown.removeAll();
        labelQueue.clear();
        tooltipRows.clear();

        // 顶部：数据包类型下拉框按钮
        String selectedText = selected.isEmpty() ? I18n.get("ide.screen.none") : selected;
        Button typeButton = Button.builder(Component.translatable("ide.screen.type", selectedText),
                b -> {
                    dropdownOpen = !dropdownOpen;
                    if (!dropdownOpen) {
                        dropdownScroll = 0;
                    } else {
                        unionDropdown.close();
                    }
                    rebuild();
                }).bounds(ViewerStyle.LEFT_PADDING, 4, TYPE_DROPDOWN_WIDTH, 14).build();
        addRenderableWidget(typeButton);

        // 主体（顶部工具条按钮 + 表单 / JSON）
        rebuildBody();

        // 顶部下拉列表：最后加入 → 盖在表单控件之上（展开时主体已隐藏，含搜索框）
        if (dropdownOpen) {
            if (searchBox == null) {
                searchBox = new EditBox(this.font, ViewerStyle.LEFT_PADDING, TOP_OFFSET,
                    TYPE_DROPDOWN_WIDTH, DROPDOWN_SEARCH_HEIGHT, Component.translatable("ide.screen.search"));
                searchBox.setMaxLength(64);
            }
            searchBox.setResponder(text -> {
                if (ignoreSearchResponder) {
                    return;
                }
                dropdownFilter = text;
                dropdownScroll = 0;
                rebuild();
            });
            addRenderableWidget(searchBox);
            ignoreSearchResponder = true;
            searchBox.setValue(dropdownFilter);
            ignoreSearchResponder = false;
            setFocused(searchBox);
            searchBox.setFocused(true);

            List<String> filtered = filteredRegistryNames();
            int maxRows = dropdownMaxRows();
            dropdownScroll = DropdownUtil.clampScroll(dropdownScroll, filtered.size(), maxRows);
            int y = dropdownListTop();
            int end = Math.min(filtered.size(), dropdownScroll + maxRows);
            for (int i = dropdownScroll; i < end; i++) {
                final String name = filtered.get(i);
                Button item = Button.builder(Component.literal(name), btn -> {
                    dropdownOpen = false;
                    dropdownScroll = 0;
                    dropdownFilter = "";
                    select(name);
                }).bounds(ViewerStyle.LEFT_PADDING, y, TYPE_DROPDOWN_WIDTH, FIELD_ROW).build();
                addRenderableWidget(item);
                dropdownButtons.add(item);
                y += FIELD_ROW + FIELD_GAP;
            }
        }

        // UNION 类型下拉按钮：最后加入 → 盖在其他控件之上
        unionDropdown.addDeferredToScreen();
    }

    /** 按过滤条件筛选后的注册表名列表（不区分大小写包含匹配）。 */
    private List<String> filteredRegistryNames() {
        String filter = dropdownFilter == null ? "" : dropdownFilter.trim().toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) {
            return registryNames;
        }
        return registryNames.stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).contains(filter))
            .toList();
    }

    /** 表单模式：从 schema 重建表单。 */
    public void rebuildForm() {
        if (schema == null) {
            return;
        }
        // 保留旧表单/JSON 的当前内容
        String current = collectJsonText();
        formRoot = EditorFormNode.root(schema);
        formRoot.load(parseOrNull(current));
        // 渲染：清空并重建
        rebuild();
    }

    /** 重建主体区域：顶部工具条按钮（模式切换/路径/文件名/保存）+ 表单。 */
    private void rebuildBody() {
        // 模式切换
        Button modeButton = Button.builder(Component.translatable(jsonMode ? "ide.screen.form" : "ide.screen.json"), b -> {
            jsonMode = !jsonMode;
            rebuild();
        }).bounds(166, 4, 70, 14).build();
        addRenderableWidget(modeButton);

        EditBox pathField = new EditBox(this.font, 244, 4, 80, 13, Component.translatable("ide.screen.path"));
        pathField.setValue(savePath);
        pathField.setResponder(s -> savePath = s);
        addRenderableWidget(pathField);

        EditBox fileNameField = new EditBox(this.font, 330, 4, 50, 13, Component.translatable("ide.screen.file"));
        fileNameField.setValue(saveFileName);
        fileNameField.setResponder(s -> saveFileName = s);
        addRenderableWidget(fileNameField);

        Button saveButton = Button.builder(Component.translatable("ide.screen.save"), b -> save()).bounds(386, 4, 40, 14).build();
        addRenderableWidget(saveButton);

        // 顶部下拉展开时隐藏主体
        if (dropdownOpen) {
            return;
        }
        if (!jsonMode && formRoot != null) {
            formRoot.buildControls(this, ViewerStyle.LEFT_PADDING + panX, TOP_OFFSET - formScroll,
                this.width - ViewerStyle.LEFT_PADDING * 2);
        }
    }

/** 把 JSON 文本格式化（多行缩进）。 */
    private static String prettyJson(String text) {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(text));
        } catch (Exception e) {
            return text;
        }
    }

    /** 选中注册表 → 请求 schema。 */
    private void select(String name) {
        this.selected = name;
        this.schema = null;
        this.formRoot = null;
        this.dropdownOpen = false;
        this.dropdownScroll = 0;
        this.dropdownFilter = "";
        this.formScroll = 0;
        this.panX = 0;
        this.unionDropdown.close();
        // 保存路径与所选数据包类型对齐：datapack:<pack>/data/<命名空间>/<注册表路径>
        this.savePath = "datapack:htlib_editor/data/" + name.replace(':', '/');
        IDENetworkHandler.sendToServer(new RequestSchemaPacket(name));
        status = I18n.get("ide.screen.request_schema", name);
        rebuild();
    }

    /** 应用收到的 schema。 */
    private void applySchema(String schemaJson) {
        try {
            this.schema = JsonParser.parseString(schemaJson).getAsJsonObject();
            this.formRoot = EditorFormNode.root(this.schema);
            this.formScroll = 0;
            this.panX = 0;
            this.unionDropdown.close();
            status = I18n.get("ide.screen.loaded_schema", selected);
            rebuild();
        } catch (Exception e) {
            status = I18n.get("ide.screen.schema_error", e.getMessage());
        }
    }

    /** 保存：发送到服务端。 */
    private void save() {
        String json = collectJsonText();
        String path = savePath + "/" + saveFileName;
        if (!path.endsWith(".json")) {
            path = path + ".json";
        }
        IDENetworkHandler.sendToServer(new SaveDataPacket(selected, path, json));
        status = I18n.get("ide.screen.save_sent", path);
    }

    private String collectJsonText() {
        if (formRoot != null) {
            return formRoot.collect().toString();
        }
        return "{}";
    }

    private com.google.gson.JsonElement parseOrNull(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    // -------------------------------------------------
    // 下拉面板几何
    // -------------------------------------------------

    private int dropdownPanelLeft() {
        return ViewerStyle.LEFT_PADDING - ViewerStyle.DROPDOWN_PANEL_MARGIN;
    }

    private int dropdownPanelRight() {
        return ViewerStyle.LEFT_PADDING + TYPE_DROPDOWN_WIDTH + ViewerStyle.DROPDOWN_PANEL_MARGIN;
    }

    private int dropdownListTop() {
        return TOP_OFFSET + DROPDOWN_SEARCH_HEIGHT + DROPDOWN_SEARCH_GAP;
    }

    private int dropdownPanelBottom() {
        return this.height - ViewerStyle.BOTTOM_PADDING;
    }

    private int dropdownMaxRows() {
        return Math.max(0, (dropdownPanelBottom() - dropdownListTop()) / (FIELD_ROW + FIELD_GAP));
    }

    // -------------------------------------------------
    // 供 EditorFormNode 调用的控件辅助
    // -------------------------------------------------

    public void addButton(int x, int y, int width, String text, Button.OnPress action) {
        Button b = Button.builder(Component.literal(text), action).bounds(x, y, width, 12).build();
        addRenderableWidget(b);
    }

    public void addCycleButton(int x, int y, int width, List<String> values, int index, Consumer<Integer> onSelect) {
        String current = index >= 0 && index < values.size() ? values.get(index) : "";
        Button b = Button.builder(Component.literal(current), btn -> {
            int next = (index + 1) % Math.max(1, values.size());
            onSelect.accept(next);
        }).bounds(x, y, width, 12).build();
        addRenderableWidget(b);
    }

    public EditBox addEditBox(int x, int y, int width, String value, Consumer<String> onChanged) {
        EditBox box = new EditBox(this.font, x, y, width, 12, Component.literal(""));
        box.setValue(value);
        box.setResponder(onChanged);
        addRenderableWidget(box);
        return box;
    }

    /** 该行是否在工具条之下的可视区内（避免滚动后表单控件盖住顶部工具条）。 */
    public boolean formRowVisible(int y) {
        return y >= TOP_OFFSET;
    }

    /** 超宽标签省略号截断地绘制（完整文本在悬浮提示里）。 */
    public void drawLabelEllipsis(int x, int y, String text, int maxWidth) {
        drawLabelEllipsis(x, y, text, maxWidth, ViewerStyle.COLOR_TEXT);
    }

    public void drawLabelEllipsis(int x, int y, String text, int maxWidth, int color) {
        drawLabel(x, y, ellipsize(text, maxWidth), color);
    }

    private String ellipsize(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        int budget = maxWidth - this.font.width(suffix);
        if (budget <= 0) {
            return suffix;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            if (this.font.width(sb.toString()) + this.font.width(c) > budget) {
                break;
            }
            sb.append(c);
        }
        return sb.append(suffix).toString();
    }

    /** 记录一个悬浮提示热区（表单行）。 */
    public void addTooltipRow(int x, int y, int width, int rowHeight, List<Component> tooltip) {
        if (tooltip != null && !tooltip.isEmpty()) {
            tooltipRows.add(new TooltipRow(x, y, width, rowHeight, tooltip));
        }
    }

    public void drawLabel(int x, int y, String text) {
        labelQueue.add(x, y, text);
    }

    public void drawLabel(int x, int y, String text, int color) {
        labelQueue.add(x, y, text, color);
    }

    // -------------------------------------------------
    // UNION 类型下拉（复用 NodeDropdown）
    // -------------------------------------------------

    /** 展开/收起某 UNION 节点的类型下拉。 */
    private void toggleUnionDropdown(EditorFormNode node) {
        unionDropdown.toggle(node);
        rebuild();
    }

    /** 该 UNION 节点的类型下拉是否展开。 */
    public boolean isUnionDropdownOpen(EditorFormNode node) {
        return unionDropdown.isOpen(node);
    }

    /** 类型选择按钮（点击展开下拉）。 */
    public void addUnionTypeButton(EditorFormNode node, int x, int y, int width, String text) {
        unionDropdown.addTrigger(node, x, y, width, text, () -> toggleUnionDropdown(node));
    }

    /** 为已展开 UNION 节点的类型下拉添加列表按钮（滚动取可见切片，延迟到最后加入渲染）。 */
    public void addUnionDropdownButtons(EditorFormNode node, int x, int y, int width) {
        unionDropdown.addItems(x, y, width, node.unionNames(), node::selectVariant);
    }

@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        // 每帧从节点状态重建标签与悬浮提示：校验红字/报错即时生效（控件仍保持重建时创建的实例，不丢焦点）
        labelQueue.clear();
        tooltipRows.clear();
        if (!dropdownOpen && !jsonMode && formRoot != null) {
            formRoot.collectLabels(this, ViewerStyle.LEFT_PADDING + panX, TOP_OFFSET - formScroll,
                this.width - ViewerStyle.LEFT_PADDING * 2);
        }

        // 主体区域裁剪在 TOP_OFFSET 之下：滚动/平移时不会盖住顶部工具条
        graphics.enableScissor(0, TOP_OFFSET, this.width, this.height);
        // 表单区域的标签
        labelQueue.render(graphics, this.font);

        // 多行滚动 JSON 视图
        if (!dropdownOpen && jsonMode) {
            String text = formRoot != null ? formRoot.collect().toString() : "{}";
            List<String> lines = splitLines(prettyJson(text));
            int x = ViewerStyle.LEFT_PADDING;
            int y = TOP_OFFSET - jsonScroll;
            for (String line : lines) {
                if (y >= TOP_OFFSET - 4 && y < this.height - 30) {
                    graphics.drawString(this.font, line, x, y, 0xFFFFFF);
                }
                y += 10;
            }
        }

        // 顶部下拉面板背景（覆盖其下方的表单/JSON）
        if (dropdownOpen) {
            drawDropdownBackground(graphics);
        }

        // UNION 类型下拉背景（覆盖其下方的表单行）
        unionDropdown.renderPanels(graphics);
        graphics.disableScissor();

        if (status != null && !status.isEmpty()) {
            graphics.drawString(this.font, status, ViewerStyle.LEFT_PADDING,
                this.height - 16, ViewerStyle.COLOR_STATUS);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (dropdownOpen) {
            drawDropdownScrollbar(graphics);
        } else if (!jsonMode) {
            drawFormScrollbar(graphics);
        }
        if (unionDropdown.hasOpen()) {
            unionDropdown.renderScrollbar(graphics);
        }

        // 悬浮提示（画在最上层，多行彩色描述）
        for (TooltipRow r : tooltipRows) {
            if (mouseX >= r.x && mouseX <= r.x + r.width && mouseY >= r.y && mouseY <= r.y + r.rowHeight) {
                graphics.renderComponentTooltip(this.font, r.tooltip, (int) mouseX, (int) mouseY);
                break;
            }
        }
    }

    /** 顶部下拉面板背景 + 边框（覆盖搜索框与列表）。 */
    private void drawDropdownBackground(GuiGraphics graphics) {
        int y1 = TOP_OFFSET - ViewerStyle.DROPDOWN_PANEL_MARGIN;
        int y2 = dropdownPanelBottom() + ViewerStyle.DROPDOWN_PANEL_MARGIN;
        DropdownUtil.drawPanel(graphics,
            new Rect(dropdownPanelLeft(), y1, dropdownPanelRight() - dropdownPanelLeft(), y2 - y1));
    }

    /** 顶部下拉列表滚动条（画在按钮之上）。 */
    private void drawDropdownScrollbar(GuiGraphics graphics) {
        int maxRows = dropdownMaxRows();
        int trackX = dropdownPanelRight() - ViewerStyle.SCROLLBAR_WIDTH - ViewerStyle.SCROLLBAR_RIGHT_MARGIN;
        DropdownUtil.drawScrollbar(graphics,
            new Rect(trackX, dropdownListTop(), ViewerStyle.SCROLLBAR_WIDTH,
                dropdownPanelBottom() - dropdownListTop()),
            filteredRegistryNames().size(), maxRows, dropdownScroll);
    }

    /** 表单右侧滚动条（仅内容超出可视区时显示）。 */
    private void drawFormScrollbar(GuiGraphics graphics) {
        if (formRoot == null) {
            return;
        }
        int content = formRoot.height();
        int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
        int maxScroll = Math.max(0, content - visible);
        if (maxScroll <= 0) {
            return;
        }
        int trackX = this.width - ViewerStyle.SCROLLBAR_WIDTH - ViewerStyle.SCROLLBAR_RIGHT_MARGIN;
        int trackY = TOP_OFFSET;
        graphics.fill(trackX - 1, trackY, trackX + ViewerStyle.SCROLLBAR_WIDTH + 1, trackY + visible,
            ViewerStyle.COLOR_SCROLL_TRACK);
        int thumbH = Math.max(EditorFormNode.ROW_HEIGHT, visible * visible / Math.max(1, content));
        int thumbY = trackY + (int)((long)formScroll * (visible - thumbH) / maxScroll);
        graphics.fill(trackX, thumbY, trackX + ViewerStyle.SCROLLBAR_WIDTH, thumbY + thumbH,
            ViewerStyle.COLOR_SCROLL_THUMB);
    }

    /** 按宽度折行。 */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.length() <= 120) {
                lines.add(line);
            } else {
                for (int i = 0; i < line.length(); i += 120) {
                    lines.add(line.substring(i, Math.min(line.length(), i + 120)));
                }
            }
        }
        return lines;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // UNION 类型下拉滚动
        if (unionDropdown.hasOpen() && unionDropdown.mouseScrolled(delta)) {
            return true;
        }
        // 顶部下拉滚动
        if (dropdownOpen) {
            int maxRows = dropdownMaxRows();
            dropdownScroll = DropdownUtil.clampScroll(
                dropdownScroll - (int)delta * ViewerStyle.DROPDOWN_SCROLL_STEP,
                filteredRegistryNames().size(), maxRows);
            rebuild();
            return true;
        }
        // 表单滚动
        if (!jsonMode) {
            int content = formRoot == null ? 0 : formRoot.height();
            int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
            formScroll = DropdownUtil.clampScroll(
                formScroll - (int)delta * ViewerStyle.SCROLL_STEP, content, visible);
            rebuild();
            return true;
        }
        if (jsonMode) {
            jsonScroll = (int) Math.max(0, jsonScroll - delta * 10);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击 UNION 类型下拉外部关闭
        if (unionDropdown.handleClickOutside(mouseX, mouseY)) {
            return true;
        }
        // 点击下拉面板外部关闭
        if (dropdownOpen && !isInsideDropdown(mouseX, mouseY)) {
            dropdownOpen = false;
            dropdownScroll = 0;
            rebuild();
            return true;
        }
        // 右键按下开始平移（表单/JSON 视图均可）
        if (button == 1) {
            panning = true;
            panLastX = mouseX;
            panLastY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 1) {
            // 内容跟随鼠标：鼠标下/右移 → 内容下/右移
            panX += (int) Math.round(mouseX - panLastX);
            int dy = (int) Math.round(mouseY - panLastY);
            if (!jsonMode) {
                int content = formRoot == null ? 0 : formRoot.height();
                int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
                formScroll = DropdownUtil.clampScroll(formScroll - dy, content, visible);
            } else {
                jsonScroll = (int) Math.max(0, jsonScroll - dy);
            }
            panX = Math.max(-this.width, Math.min(0, panX));
            panLastX = mouseX;
            panLastY = mouseY;
            rebuild();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1) {
            panning = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isInsideDropdown(double x, double y) {
        return x >= dropdownPanelLeft() && x <= dropdownPanelRight()
            && y >= dropdownListTop() - ViewerStyle.DROPDOWN_PANEL_MARGIN
            && y <= dropdownPanelBottom() + ViewerStyle.DROPDOWN_PANEL_MARGIN;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}