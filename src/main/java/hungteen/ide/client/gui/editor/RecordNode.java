package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.ide.common.codec.parser.SchemaKeys;

import java.util.ArrayList;
import java.util.List;

/** 记录/对象节点：字段集合，逐字段递归编辑。 */
final class RecordNode extends EditorFormNode {

    private final List<EditorFormNode> recordChildren = new ArrayList<>();

    RecordNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        if (!schema.has(SchemaKeys.FIELDS)) {
            return;
        }
        for (JsonElement f : schema.getAsJsonArray(SchemaKeys.FIELDS)) {
            JsonObject field = f.getAsJsonObject();
            String name = field.get(SchemaKeys.NAME).getAsString();
            JsonObject fieldSchema = field.getAsJsonObject(SchemaKeys.SCHEMA);
            recordChildren.add(EditorFormNode.create(fieldSchema, name, fieldValue(name)));
        }
    }

    @Override
    public int height() {
        int h = 0;
        for (EditorFormNode c : recordChildren) {
            h += c.height();
        }
        return h;
    }

    @Override
    public int buildControls(CodecEditorScreen screen, int x, int y, int width) {
        for (EditorFormNode child : recordChildren) {
            int indent = x + INDENT;
            y = child.buildControls(screen, indent, y, width - INDENT);
        }
        return y;
    }

    @Override
    public int collectLabels(CodecEditorScreen screen, int x, int y, int width) {
        for (EditorFormNode child : recordChildren) {
            int indent = x + INDENT;
            y = child.collectLabels(screen, indent, y, width - INDENT);
        }
        return y;
    }

    @Override
    public JsonElement collect() {
        JsonObject obj = new JsonObject();
        for (EditorFormNode child : recordChildren) {
            // 可选枚举且"不使用"：省略该键
            if (child.isOptionalAndAbsent()) {
                continue;
            }
            obj.add(child.label, child.collect());
        }
        return obj;
    }

    @Override
    public void load(JsonElement json) {
        this.value = json == null ? defaultFor(schema) : json;
        if (this.value == null) {
            this.value = new JsonObject();
        }
        for (EditorFormNode child : recordChildren) {
            child.load(fieldValue(child.label));
        }
    }
}