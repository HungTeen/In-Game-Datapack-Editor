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
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Codec 编辑器界面。
 *
 * <p>顶部工具条：数据包类型下拉框（点击展开选择）、表单/JSON 模式切换、保存路径与文件名、保存按钮。
 * 主体区域：表单或 JSON 文本视图。</p>
 *
 * @author PangTeen
 * @program: HTLib
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

    private static final int TOP_OFFSET = 30;
    private static final int FIELD_ROW = 16;
    private static final int FIELD_GAP = 4;

    private final List<String> registryNames;
    private String selected = "";

    // 状态
    private boolean dropdownOpen = false;
    private boolean jsonMode = false;
    private JsonObject schema;
    private EditorFormNode formRoot;
    private String savePath = "datapack:htlib_editor/data";
    private String saveFileName = "data.json";
    private int jsonScroll = 0;

    // 控件
    private Button typeButton;
    private final List<Button> dropdownButtons = new ArrayList<>();
    private EditBox pathField;
    private EditBox fileNameField;
    private Button modeButton;
    private Button saveButton;
    private String status = "";

    public CodecEditorScreen(List<String> registryNames) {
        super(Component.literal("Codec 编辑器"));
        this.registryNames = new ArrayList<>(registryNames);
    }

    @Override
    protected void init() {
        rebuildControls();
    }

    /** 全量重建界面控件。 */
    private void rebuildControls() {
        clearWidgets();
        dropdownButtons.clear();
        labelQueue.clear();

        // 顶部：数据包类型下拉框按钮
        typeButton = Button.builder(Component.literal("数据包类型: " + (selected.isEmpty() ? "(未选择)" : selected)),
                b -> {
                    dropdownOpen = !dropdownOpen;
                    rebuildBody();
                }).bounds(8, 5, 180, 18).build();
        addRenderableWidget(typeButton);

        // 下拉列表
        if (dropdownOpen) {
            int y = TOP_OFFSET;
            int maxRows = (this.height - TOP_OFFSET - 10) / (FIELD_ROW + FIELD_GAP);
            for (int i = 0; i < Math.min(registryNames.size(), maxRows); i++) {
                final String name = registryNames.get(i);
                Button item = Button.builder(Component.literal(name), btn -> {
                    dropdownOpen = false;
                    select(name);
                }).bounds(8, y, 180, FIELD_ROW).build();
                addRenderableWidget(item);
                dropdownButtons.add(item);
                y += FIELD_ROW + FIELD_GAP;
            }
        }

        rebuildBody();
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
        rebuildControls();
    }

    /** 重建主体区域（顶部工具条 + 表单；不含数据包类型下拉框，那个由 rebuildControls 处理）。 */
    private void rebuildBody() {
        // 模式切换
        modeButton = Button.builder(Component.literal(jsonMode ? "表单模式" : "JSON 模式"), b -> {
            String current = collectJsonText();
            jsonMode = !jsonMode;
            rebuildBody();
        }).bounds(198, 5, 80, 18).build();
        addRenderableWidget(modeButton);

        pathField = new EditBox(this.font, 288, 5, 160, 16, Component.literal("路径"));
        pathField.setValue(savePath);
        pathField.setResponder(s -> savePath = s);
        addRenderableWidget(pathField);

        fileNameField = new EditBox(this.font, 458, 5, 100, 16, Component.literal("文件名"));
        fileNameField.setValue(saveFileName);
        fileNameField.setResponder(s -> saveFileName = s);
        addRenderableWidget(fileNameField);

        saveButton = Button.builder(Component.literal("保存"), b -> save()).bounds(568, 5, 50, 18).build();
        addRenderableWidget(saveButton);

        if (jsonMode) {
            // JSON 用多行滚动文本渲染（见 render()），无需 EditBox。
        } else if (formRoot != null) {
            int yPos = TOP_OFFSET;
            yPos = formRoot.render(this, 8, yPos, this.width - 16);
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
        IDENetworkHandler.sendToServer(new RequestSchemaPacket(name));
        status = "请求 " + name + " schema...";
        rebuildControls();
    }

    /** 应用收到的 schema。 */
    private void applySchema(String schemaJson) {
        try {
            this.schema = JsonParser.parseString(schemaJson).getAsJsonObject();
            this.formRoot = EditorFormNode.root(this.schema);
            status = "已加载 " + selected + " schema";
            rebuildControls();
        } catch (Exception e) {
            status = "schema 解析失败: " + e.getMessage();
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
        status = "保存请求已发送: " + path;
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
        Button b = Button.builder(Component.literal(text), action).bounds(x, y, width, 16).build();
        addRenderableWidget(b);
    }

    public void addCycleButton(int x, int y, int width, List<String> values, int index, Consumer<Integer> onSelect) {
        String current = index >= 0 && index < values.size() ? values.get(index) : "";
        Button b = Button.builder(Component.literal(current), btn -> {
            int next = (index + 1) % Math.max(1, values.size());
            onSelect.accept(next);
        }).bounds(x, y, width, 16).build();
        addRenderableWidget(b);
    }

    public EditBox addEditBox(int x, int y, int width, String value, Consumer<String> onChanged) {
        EditBox box = new EditBox(this.font, x, y, width, 16, Component.literal(""));
        box.setValue(value);
        box.setResponder(onChanged);
        addRenderableWidget(box);
        return box;
    }

    public void drawLabel(int x, int y, String text) {
        labelQueue.add(new Label(x, y, text));
    }

    private static class Label {
        final int x;
        final int y;
        final String text;

        Label(int x, int y, String text) {
            this.x = x;
            this.y = y;
            this.text = text;
        }
    }

    private final List<Label> labelQueue = new ArrayList<>();

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        // 表单区域的标签
        for (Label l : labelQueue) {
            graphics.drawString(this.font, l.text, l.x, l.y, 0xFFFFFF);
        }
        labelQueue.clear();

        if (jsonMode) {
            // 多行滚动 JSON 视图
            String text = formRoot != null ? formRoot.collect().toString() : "{}";
            List<String> lines = splitLines(prettyJson(text));
            int x = 8;
            int y = TOP_OFFSET - jsonScroll;
            for (String line : lines) {
                if (y >= TOP_OFFSET - 4 && y < this.height - 30) {
                    graphics.drawString(this.font, line, x, y, 0xFFFFFF);
                }
                y += 10;
            }
        }

        if (status != null && !status.isEmpty()) {
            graphics.drawString(this.font, status, 8, this.height - 16, 0xFFFFAA);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
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
        if (jsonMode) {
            jsonScroll = (int) Math.max(0, jsonScroll - delta * 10);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击空白处收起下拉框
        if (dropdownOpen && !isInsideTypeButton(mouseX, mouseY)) {
            dropdownOpen = false;
            rebuildControls();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isInsideTypeButton(double x, double y) {
        return x >= 8 && x <= 188 && y >= 5 && y <= 23;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
