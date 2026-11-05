package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/** 映射节点：头部 [＋][－] 增删（删最后一项），键/值各占一行递归编辑。 */
final class MapNode extends EditorFormNode {

    private final List<EditorFormNode> mapKeys = new ArrayList<>();
    private final List<EditorFormNode> mapValues = new ArrayList<>();

    MapNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        JsonObject k = keySchema();
        JsonObject v = valueSchema();
        if (k == null || v == null) {
            return;
        }
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                mapKeys.add(EditorFormNode.create(k, "", new JsonPrimitive(entry.getKey())));
                mapValues.add(EditorFormNode.create(v, "", entry.getValue()));
            }
        }
    }

    @Override
    public int height() {
        int h = ROW_HEIGHT;
        for (int i = 0; i < mapKeys.size(); i++) {
            h += mapKeys.get(i).height() + mapValues.get(i).height();
        }
        return h;
    }

    @Override
    public int buildControls(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        int cx = x + LABEL_WIDTH;
        if (screen.formRowVisible(y)) {
            screen.addButton(cx, y, 20, "+", b -> {
                mapKeys.add(EditorFormNode.create(keySchema(), "", new JsonPrimitive("")));
                mapValues.add(EditorFormNode.create(valueSchema(), "", JsonNull.INSTANCE));
                screen.rebuild();
            });
            screen.addButton(cx + 22, y, 20, "-", b -> {
                if (!mapKeys.isEmpty()) {
                    mapKeys.remove(mapKeys.size() - 1);
                    mapValues.remove(mapValues.size() - 1);
                    screen.rebuild();
                }
            });
        }
        y += ROW_HEIGHT;
        for (int i = 0; i < mapKeys.size(); i++) {
            y = mapKeys.get(i).buildControls(screen, x + INDENT, y, width - INDENT);
            y = mapValues.get(i).buildControls(screen, x + INDENT, y, width - INDENT);
        }
        return y;
    }

    @Override
    public int collectLabels(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        fieldLabel(screen, x, y);
        screen.addTooltipRow(x, y, cx + cw - x, ROW_HEIGHT, tooltipLines());
        y += ROW_HEIGHT;
        for (int i = 0; i < mapKeys.size(); i++) {
            y = mapKeys.get(i).collectLabels(screen, x + INDENT, y, width - INDENT);
            y = mapValues.get(i).collectLabels(screen, x + INDENT, y, width - INDENT);
        }
        return y;
    }

    @Override
    public JsonElement collect() {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < Math.min(mapKeys.size(), mapValues.size()); i++) {
            JsonElement k = mapKeys.get(i).collect();
            JsonElement v = mapValues.get(i).collect();
            if (k.isJsonPrimitive()) {
                obj.add(k.getAsString(), v);
            }
        }
        return obj;
    }

    @Override
    protected List<EditorFormNode> validationChildren() {
        List<EditorFormNode> children = new ArrayList<>(mapKeys);
        children.addAll(mapValues);
        return children;
    }

    @Override
    protected void validateValue() {
        invalid = false;
        errorMessage = "";
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (EditorFormNode key : mapKeys) {
            JsonElement current = key.collect();
            if (!current.isJsonPrimitive() || !keys.add(current.getAsString())) {
                invalid = true;
                errorMessage = I18n.get("ide.error.map_keys");
                return;
            }
        }
    }

    @Override
    public void load(JsonElement json) {
        mapKeys.clear();
        mapValues.clear();
        JsonObject k = keySchema();
        JsonObject v = valueSchema();
        if (k != null && v != null && json != null && json.isJsonObject()) {
            for (var entry : json.getAsJsonObject().entrySet()) {
                mapKeys.add(EditorFormNode.create(k, "", new JsonPrimitive(entry.getKey())));
                mapValues.add(EditorFormNode.create(v, "", entry.getValue()));
            }
        }
    }
}
