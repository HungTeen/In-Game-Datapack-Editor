package hungteen.ide.client.gui.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hungteen.htlib.common.network.NetworkHandler;
import hungteen.ide.common.network.RequestSchemaPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Codec 字段查看器（只读）。
 *
 * <p>顶部：数据包类型下拉框（点击展开选择）。主体：滚动字段树；
 * UNION（dispatch）节点提供类型循环按钮，选中 type 后展开对应分支字段。</p>
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
        if (Minecraft.getInstance().screen instanceof CodecViewerScreen viewer && viewer.selected.equals(registryName)) {
            viewer.applySchema(schemaJson);
        }
    }

    private static final int TOP_OFFSET = 30;
    private static final int FIELD_ROW = 16;
    private static final int FIELD_GAP = 4;

    private final List<String> registryNames;
    private String selected;
    private boolean dropdownOpen = false;
    private JsonObject schema;
    private ViewerNode root;
    private int scroll = 0;
    private String status = "";

    private final List<Label> labels = new ArrayList<>();

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
            NetworkHandler.sendToServer(new RequestSchemaPacket(selected));
            status = "请求 " + selected + " schema...";
        }
    }

    /** 重建界面。 */
    public void rebuild() {
        clearWidgets();
        labels.clear();

        // 顶部下拉框按钮
        Button typeButton = Button.builder(
                        Component.literal("数据包类型: " + (selected.isEmpty() ? "(未选择)" : selected)),
                        b -> {
                            dropdownOpen = !dropdownOpen;
                            rebuild();
                        })
                .bounds(8, 5, 180, 18)
                .build();
        addRenderableWidget(typeButton);

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
                y += FIELD_ROW + FIELD_GAP;
            }
        }

        // 字段树
        if (root != null) {
            int yPos = TOP_OFFSET - scroll;
            root.render(this, 8, yPos, this.width - 20);
        }
    }

    private void select(String name) {
        this.selected = name;
        this.schema = null;
        this.root = null;
        NetworkHandler.sendToServer(new RequestSchemaPacket(name));
        status = "请求 " + name + " schema...";
        rebuild();
    }

    private void applySchema(String schemaJson) {
        try {
            this.schema = JsonParser.parseString(schemaJson).getAsJsonObject();
            this.root = ViewerNode.root(this.schema);
            status = "已加载 " + selected;
            scroll = 0;
            rebuild();
        } catch (Exception e) {
            status = "schema 解析失败: " + e.getMessage();
            rebuild();
        }
    }

    // -------------------------------------------------
    // 供 ViewerNode 调用
    // -------------------------------------------------

    public void drawLabel(int x, int y, String text) {
        drawLabel(x, y, text, 0xFFFFFF);
    }

    /** 用指定颜色绘制标签。 */
    public void drawLabel(int x, int y, String text, int color) {
        labels.add(new Label(x, y, text, color));
    }

    /** 文本像素宽度（供 ViewerNode 拼接彩色片段用）。 */
    public int fontWidth(String text) {
        return this.font.width(text);
    }

    public void addCycleButton(int x, int y, int width, List<String> values, int index, Consumer<Integer> onSelect) {
        String current = index >= 0 && index < values.size() ? values.get(index) : "";
        Button b = Button.builder(Component.literal(current), btn -> {
            int next = (index + 1) % Math.max(1, values.size());
            onSelect.accept(next);
        }).bounds(x, y, width, 14).build();
        addRenderableWidget(b);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        for (Label l : labels) {
            graphics.drawString(this.font, l.text, l.x, l.y, l.color);
        }
        labels.clear();
        if (status != null && !status.isEmpty()) {
            graphics.drawString(this.font, status, 8, this.height - 16, 0xFFFFAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int content = root == null ? 0 : root.height();
        int visible = this.height - TOP_OFFSET - 20;
        int maxScroll = Math.max(0, content - visible);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) delta * 12));
        rebuild();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dropdownOpen && !(mouseX >= 8 && mouseX <= 188 && mouseY >= 5 && mouseY <= 23)) {
            dropdownOpen = false;
            rebuild();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
