package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import hungteen.ide.common.codec.parser.SchemaKeys;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 枚举节点：单行循环按钮，选中某个常量。 */
final class EnumNode extends EditorFormNode {

    private final List<String> enumValues = new ArrayList<>();
    private int enumIndex = 0;

    EnumNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        if (schema.has(SchemaKeys.ENUM_VALUES)) {
            for (JsonElement e : schema.getAsJsonArray(SchemaKeys.ENUM_VALUES)) {
                enumValues.add(e.getAsJsonObject().get(SchemaKeys.SERIALIZED_NAME).getAsString());
            }
        }
        String current = value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        enumIndex = Math.max(0, enumValues.indexOf(current));
    }

    @Override
    public int height() {
        return ROW_HEIGHT;
    }

    @Override
    public int buildControls(CodecEditorScreen screen, int x, int y, int width) {
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        if (screen.formRowVisible(y)) {
            screen.addCycleButton(cx, y, cw, enumValues, enumIndex, i -> enumIndex = i);
        }
        return y + ROW_HEIGHT;
    }

    @Override
    public int collectLabels(CodecEditorScreen screen, int x, int y, int width) {
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        fieldLabel(screen, x, y);
        screen.addTooltipRow(x, y, cx + cw - x, ROW_HEIGHT, tooltipLines());
        return y + ROW_HEIGHT;
    }

    @Override
    public int buildInlineControl(CodecEditorScreen screen, int x, int y, int width) {
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        if (screen.formRowVisible(y)) {
            screen.addCycleButton(cx, y, cw, enumValues, enumIndex, i -> enumIndex = i);
        }
        return y + ROW_HEIGHT;
    }

    @Override
    public int enumIndex() {
        return enumIndex;
    }

    @Override
    public JsonElement collect() {
        return enumIndex >= 0 && enumIndex < enumValues.size()
            ? new JsonPrimitive(enumValues.get(enumIndex)) : JsonNull.INSTANCE;
    }

    @Override
    public void load(JsonElement json) {
        String current = json != null && json.isJsonPrimitive() ? json.getAsString() : "";
        enumIndex = Math.max(0, enumValues.indexOf(current));
    }

    @Override
    protected void appendTypeInfo(List<Component> lines) {
        if (enumValues.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("取值: ");
        int shown = Math.min(enumValues.size(), 20);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(enumValues.get(i));
        }
        if (enumValues.size() > shown) {
            sb.append("… (共 ").append(enumValues.size()).append(" 个)");
        }
        lines.add(line(sb.toString(), ViewerStyle.COLOR_ENUM));
    }
}