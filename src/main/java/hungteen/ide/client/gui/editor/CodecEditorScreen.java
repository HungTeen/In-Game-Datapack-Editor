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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

/**
 * Codec 编辑器界面。
 *
 * <p>顶部工具条：数据包类型下拉框（支持搜索）、表单/JSON 模式切换、保存路径与文件名、保存按钮。
 * 主体区域：表单或 JSON 文本视图。表单支持滚轮滚动与右键拖拽平移。</p>
 *
 * @author PangTeen
 * @create 2026/8/10 14:15
 **/
public class CodecEditorScreen extends CodecScreen {

    /** 打开编辑器界面（由 OpenCodecPacket 调用）。 */
    public static void open(List<ResourceLocation> registryNames) {
        Minecraft.getInstance().setScreen(new CodecEditorScreen(registryNames));
    }

    private static final int TOP_OFFSET = 20;

    private boolean jsonMode = false;
    private JsonObject schema;
    private EditorFormNode formRoot;
    private String saveRegistryName = "";
    private int jsonScroll = 0;
    private int formScroll = 0;
    private int panX = 0;

    // 右键拖拽平移
    private boolean panning = false;
    private double panLastX, panLastY;

    private final LabelQueue labelQueue;
    private final List<TooltipRow> tooltipRows = new ArrayList<>();

    private record TooltipRow(int x, int y, int width, int rowHeight, List<Component> tooltip) {
    }

    public CodecEditorScreen(List<ResourceLocation> registryNames) {
        super(Component.translatable("ide.screen.editor.title"), registryNames);
        this.labelQueue = new LabelQueue();
    }

    /** 顶部类型补全输入框（候选为全部注册表名）。 */
    @Override
    protected TypeSelector createTypeSelector() {
        List<String> names = registryNames.stream().map(ResourceLocation::toString).toList();
        EditorToolbarLayout layout = EditorToolbarLayout.forWidth(this.width);
        return new TypeSelector(this, this::addRenderableWidget,
            layout.typeX(), 4, layout.typeWidth(), 13, TOP_OFFSET, names, this::select);
    }

    /**
     * 全量重建界面：类型输入框 → 主体。
     */
    @Override
    protected void rebuild() {
        clearWidgets();
        selectors.clear();
        labelQueue.clear();
        tooltipRows.clear();

        // 顶部：数据包类型输入框（自动补全）
        typeSelector.setValue(selected);
        typeSelector.addToScreen(this.font);

        // 主体（顶部工具条按钮 + 表单 / JSON）
        rebuildBody();
    }

    /**
     * 表单模式：从 schema 重建表单。
     */
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

    /**
     * 重建主体区域：顶部工具条按钮（模式切换/路径/文件名/保存）+ 表单。
     */
    private void rebuildBody() {
        EditorToolbarLayout layout = EditorToolbarLayout.forWidth(this.width);
        // 模式切换按钮
        Button modeButton =
            Button.builder(Component.translatable(jsonMode ? "ide.screen.form" : "ide.screen.json"), b -> {
                jsonMode = !jsonMode;
                rebuild();
            }).bounds(layout.modeX(), 4, layout.modeWidth(), 14).build();
        addRenderableWidget(modeButton);

        // 注册名输入框
        EditBox registryNameField = new EditBox(this.font, layout.resourceX(), 4, layout.resourceWidth(), 13,
            Component.translatable("ide.screen.path"));
        registryNameField.setMaxLength(SaveDataPacket.MAX_ID_LENGTH);
        registryNameField.setHint(Component.literal("pack:path"));
        registryNameField.setTooltip(Tooltip.create(Component.translatable("ide.screen.resource_id_hint")));
        registryNameField.setValue(saveRegistryName);
        registryNameField.setResponder(s -> saveRegistryName = s);
        addRenderableWidget(registryNameField);

        // 保存按钮
        Button saveButton = Button.builder(Component.translatable("ide.screen.save"), b -> save())
            .bounds(layout.saveX(), 4, layout.saveWidth(), 14).build();
        addRenderableWidget(saveButton);

        // 顶部下拉展开时隐藏主体
        if (!jsonMode && formRoot != null) {
            formRoot.buildControls(this, ViewerStyle.LEFT_PADDING + panX, TOP_OFFSET - formScroll,
                this.width - ViewerStyle.LEFT_PADDING * 2);
        }
    }

    /**
     * 把 JSON 文本格式化（多行缩进）。
     */
    private static String prettyJson(String text) {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(text));
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 选中注册表 → 请求 schema。
     */
    public void select(String name) {
        this.selected = name;
        this.schema = null;
        this.formRoot = null;
        this.formScroll = 0;
        this.panX = 0;
        IDENetworkHandler.sendToServer(new RequestSchemaPacket(name));
        setStatusPersistent(I18n.get("ide.screen.request_schema", name));
        rebuild();
    }

    /**
     * 应用收到的 schema。
     */
    @Override
    protected void applySchema(String schemaJson) {
        try {
            this.schema = JsonParser.parseString(schemaJson).getAsJsonObject();
            this.formRoot = EditorFormNode.root(this.schema);
            this.formScroll = 0;
            this.panX = 0;
            setStatusPersistent(I18n.get("ide.screen.loaded_schema", selected));
            rebuild();
        } catch (Exception e) {
            setStatusPersistent(I18n.get("ide.screen.schema_error", e.getMessage()));
        }
    }

    /**
     * 保存：发送到服务端。
     */
    private void save() {
        if (StringUtils.isBlank(this.selected) || formRoot == null) {
            setStatusPersistent(I18n.get("ide.error.select_type"));
            return;
        }
        if (StringUtils.isBlank(this.saveRegistryName) || !ResourceLocation.isValidResourceLocation(this.saveRegistryName)) {
            setStatusPersistent(I18n.get("ide.error.resource_id"));
            return;
        }
        ResourceLocation registryName = ResourceLocation.tryParse(this.saveRegistryName);
        ResourceLocation registryType = ResourceLocation.tryParse(this.selected);
        if (ObjectUtils.anyNull(registryName, registryType)) {
            setStatusPersistent(I18n.get("ide.error.resource_id"));
            return;
        }
        if (formRoot.hasErrors()) {
            setStatusPersistent(I18n.get("ide.error.invalid_form"));
            return;
        }
        String json = collectJsonText();
        if (json.getBytes(StandardCharsets.UTF_8).length > SaveDataPacket.MAX_JSON_BYTES) {
            setStatusPersistent(I18n.get("ide.error.json_too_large", SaveDataPacket.MAX_JSON_BYTES));
            return;
        }
        IDENetworkHandler.sendToServer(new SaveDataPacket(selected, registryName.toString(), json));
        setStatusPersistent(I18n.get("ide.screen.save_sent", registryName));
    }

    /** Apply the server acknowledgement only to the document that initiated the request. */
    public static void onSaveResult(String registryName, String resourceId, boolean success, Component message) {
        if (Minecraft.getInstance().screen instanceof CodecEditorScreen screen
            && screen.selected.equals(registryName)
            && resourceId.equals(String.valueOf(ResourceLocation.tryParse(screen.saveRegistryName)))) {
            screen.setStatusPersistent(message.getString());
        }
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
    // 供 EditorFormNode 调用的控件辅助
    // -------------------------------------------------

    public void addButton(int x, int y, int width, String text, Button.OnPress action) {
        Button b = Button.builder(Component.literal(text), action).bounds(x, y, width, 11).build();
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
        box.setMaxLength(SaveDataPacket.MAX_JSON_LENGTH);
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

    @Override
    protected void renderFormRegion(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 每帧从节点状态重建标签与悬浮提示：校验红字/报错即时生效（控件仍保持重建时创建的实例，不丢焦点）
        labelQueue.clear();
        tooltipRows.clear();
        if (!jsonMode && formRoot != null) {
            formRoot.collectLabels(this, ViewerStyle.LEFT_PADDING + panX, TOP_OFFSET - formScroll,
                this.width - ViewerStyle.LEFT_PADDING * 2);
        }

        // 主体区域裁剪在 TOP_OFFSET 之下：滚动/平移时不会盖住顶部工具条
        graphics.enableScissor(0, TOP_OFFSET, this.width, this.height);
        // 表单区域的标签
        labelQueue.render(graphics, this.font);

        // 多行滚动 JSON 视图
        if (jsonMode) {
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

        graphics.disableScissor();
    }

    @Override
    protected void renderOverlays(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!jsonMode) {
            drawFormScrollbar(graphics);
        }

        // 悬浮提示（画在控件之上）
        for (TooltipRow r : tooltipRows) {
            if (mouseX >= r.x && mouseX <= r.x + r.width && mouseY >= r.y && mouseY <= r.y + r.rowHeight) {
                graphics.renderComponentTooltip(this.font, r.tooltip, (int)mouseX, (int)mouseY);
                break;
            }
        }
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

    /** 补全面板之外：表单/JSON 滚动。 */
    @Override
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        if (!jsonMode) {
            int content = formRoot == null ? 0 : formRoot.height();
            int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
            formScroll = DropdownUtil.clampScroll(formScroll - (int)delta * ViewerStyle.SCROLL_STEP, content, visible);
            rebuild();
            return true;
        }
        if (jsonMode) {
            jsonScroll = (int)Math.max(0, jsonScroll - delta * 10);
            return true;
        }
        return false;
    }

    /** 补全面板之外：右键按下开始平移（表单/JSON 视图均可）。 */
    @Override
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (button == 1) {
            panning = true;
            panLastX = mouseX;
            panLastY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 1) {
            // 内容跟随鼠标：鼠标下/右移 → 内容下/右移
            panX += (int)Math.round(mouseX - panLastX);
            int dy = (int)Math.round(mouseY - panLastY);
            if (!jsonMode) {
                int content = formRoot == null ? 0 : formRoot.height();
                int visible = this.height - TOP_OFFSET - ViewerStyle.BOTTOM_PADDING;
                formScroll = DropdownUtil.clampScroll(formScroll - dy, content, visible);
            } else {
                jsonScroll = (int)Math.max(0, jsonScroll - dy);
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
}
