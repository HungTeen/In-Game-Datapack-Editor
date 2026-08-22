package hungteen.ide.client.gui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import hungteen.ide.common.codec.parser.SchemaKeys;

import java.util.ArrayList;
import java.util.List;

/** 列表/集合节点：头部 [＋][－] 增删，元素逐项递归编辑。 */
final class ListSetNode extends EditorFormNode {

    private final List<EditorFormNode> listItems = new ArrayList<>();

    ListSetNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        JsonObject el = elementSchema();
        if (el == null) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement e : value.getAsJsonArray()) {
                listItems.add(EditorFormNode.create(el, "", e));
            }
        }
    }

    @Override
    public int height() {
        int h = ROW_HEIGHT;
        for (EditorFormNode item : listItems) {
            h += item.height();
        }
        return h;
    }

    @Override
    public int buildControls(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        int cx = x + LABEL_WIDTH;
        if (screen.formRowVisible(y)) {
            screen.addButton(cx, y, 20, "+", b -> {
                listItems.add(EditorFormNode.create(elementSchema(), "", JsonNull.INSTANCE));
                screen.rebuildForm();
            });
            screen.addButton(cx + 22, y, 20, "-", b -> {
                if (!listItems.isEmpty()) {
                    listItems.remove(listItems.size() - 1);
                    screen.rebuildForm();
                }
            });
        }
        y += ROW_HEIGHT;
        for (EditorFormNode item : listItems) {
            y = item.buildControls(screen, x + INDENT, y, width - INDENT);
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
        for (EditorFormNode item : listItems) {
            y = item.collectLabels(screen, x + INDENT, y, width - INDENT);
        }
        return y;
    }

    @Override
    public JsonElement collect() {
        JsonArray arr = new JsonArray();
        for (EditorFormNode item : listItems) {
            arr.add(item.collect());
        }
        return arr;
    }

    @Override
    public void load(JsonElement json) {
        listItems.clear();
        JsonObject el = elementSchema();
        if (el != null && json != null && json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) {
                listItems.add(EditorFormNode.create(el, "", e));
            }
        }
    }

    @Override
    protected void validateValue() {
        invalid = false;
        errorMessage = "";
        if (!schema.has(SchemaKeys.CONSTRAINTS)) {
            return;
        }
        for (JsonElement c : schema.getAsJsonArray(SchemaKeys.CONSTRAINTS)) {
            String err = SchemaTooltip.checkConstraint(c.getAsString(), SchemaTooltip.Kind.SIZE,
                "", listItems.size());
            if (err != null) {
                invalid = true;
                errorMessage = "值不满足约束 " + err;
                return;
            }
        }
    }
}