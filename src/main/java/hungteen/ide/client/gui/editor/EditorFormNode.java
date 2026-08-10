package hungteen.ide.client.gui.editor;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 由 schema JSON 构建的递归表单节点。
 *
 * <p>每个节点持有 schema 与当前值 {@link JsonElement}，提供：</p>
 * <ul>
 *   <li>{@link #height()} 预估占用的行高；</li>
 *   <li>{@link #render(CodecEditorScreen, int, int, int)} 在指定位置布局控件；</li>
 *   <li>{@link #collect()} 从控件收集 JSON 值；</li>
 *   <li>{@link #load(JsonElement)} 把 JSON 值加载回控件。</li>
 * </ul>
 *
 * <p>结构类型（LIST / MAP / OPTIONAL / UNION）的增删、切换通过重建整棵表单实现
 * （{@link CodecEditorScreen#rebuildForm()} 先 clearWidgets 再全量重绘）。</p>
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 14:10
 **/
public class EditorFormNode {

    private static final int ROW_HEIGHT = 20;
    private static final int LABEL_WIDTH = 140;
    private static final int CONTROL_WIDTH = 120;

    private final JsonObject schema;
    private final String label;
    private JsonElement value;

    // 叶子控件
    private net.minecraft.client.gui.components.EditBox editBox;
    private net.minecraft.client.gui.components.Button boolButton;
    private net.minecraft.client.gui.components.Button enumButton;
    private final List<String> enumValues = new ArrayList<>();
    private int enumIndex = 0;

    // 结构子节点
    private final List<EditorFormNode> recordChildren = new ArrayList<>();
    private final List<EditorFormNode> listItems = new ArrayList<>();
    private final List<EditorFormNode> mapKeys = new ArrayList<>();
    private final List<EditorFormNode> mapValues = new ArrayList<>();
    private EditorFormNode optionalInner;
    private boolean optionalPresent = false;
    private final List<EditorFormNode> unionVariants = new ArrayList<>();
    private int unionIndex = 0;
    private final List<String> unionNames = new ArrayList<>();

    public EditorFormNode(JsonObject schema, String label, JsonElement value) {
        this.schema = schema;
        this.label = label == null ? "" : label;
        this.value = value == null ? defaultFor(schema) : value;
        if (this.value == null) {
            this.value = JsonNull.INSTANCE;
        }
        initChildren();
    }

    /** 根节点便捷构造。 */
    public static EditorFormNode root(JsonObject schema) {
        return new EditorFormNode(schema, "", JsonNull.INSTANCE);
    }

    private void initChildren() {
        String type = type();
        switch (type) {
            case "RECORD" -> {
                if (!schema.has("fields")) break;
                for (JsonElement f : schema.getAsJsonArray("fields")) {
                    JsonObject field = f.getAsJsonObject();
                    String name = field.get("name").getAsString();
                    JsonObject fieldSchema = field.getAsJsonObject("schema");
                    recordChildren.add(new EditorFormNode(fieldSchema, name, fieldValue(name)));
                }
            }
            case "LIST", "SET" -> {
                if (!schema.has("element")) break;
                JsonObject elementSchema = schema.getAsJsonObject("element");
                if (value.isJsonArray()) {
                    for (JsonElement e : value.getAsJsonArray()) {
                        listItems.add(new EditorFormNode(elementSchema, "", e));
                    }
                }
            }
            case "MAP" -> {
                if (!schema.has("key") || !schema.has("value")) break;
                JsonObject keySchema = schema.getAsJsonObject("key");
                JsonObject valueSchema = schema.getAsJsonObject("value");
                if (value.isJsonObject()) {
                    for (var entry : value.getAsJsonObject().entrySet()) {
                        mapKeys.add(new EditorFormNode(keySchema, "", new JsonPrimitive(entry.getKey())));
                        mapValues.add(new EditorFormNode(valueSchema, "", entry.getValue()));
                    }
                }
            }
            case "OPTIONAL" -> {
                if (!schema.has("element")) break;
                JsonObject elementSchema = schema.getAsJsonObject("element");
                optionalPresent = value != null && !value.isJsonNull();
                optionalInner = new EditorFormNode(elementSchema, "", optionalPresent ? value : JsonNull.INSTANCE);
            }
            case "UNION", "EITHER" -> {
                if (!schema.has("variants")) break;
                JsonArray variants = schema.getAsJsonArray("variants");
                for (JsonElement v : variants) {
                    JsonObject vo = v.getAsJsonObject();
                    unionNames.add(vo.has("name") ? vo.get("name").getAsString() : ("分支" + unionNames.size()));
                    unionVariants.add(new EditorFormNode(vo, "", JsonNull.INSTANCE));
                }
                if (!unionVariants.isEmpty()) {
                    // 探测当前值属于哪个变体：尝试匹配分派键或首个可解析的。
                    unionIndex = matchUnionVariant(value);
                    unionVariants.get(unionIndex).load(value);
                }
            }
            case "ENUM" -> {
                if (schema.has("enumValues")) {
                    for (JsonElement e : schema.getAsJsonArray("enumValues")) {
                        enumValues.add(e.getAsJsonObject().get("serializedName").getAsString());
                    }
                }
                String current = value.isJsonPrimitive() ? value.getAsString() : "";
                enumIndex = Math.max(0, enumValues.indexOf(current));
            }
            default -> {
            }
        }
    }

    // -------------------------------------------------
    // 布局
    // -------------------------------------------------

    /** 预估占用的总行高。 */
    public int height() {
        return switch (type()) {
            case "RECORD" -> {
                int h = 0;
                for (EditorFormNode c : recordChildren) {
                    h += c.height() + ROW_HEIGHT;
                }
                yield h;
            }
            case "LIST", "SET" -> ROW_HEIGHT + listItems.size() * (ROW_HEIGHT + 4) + ROW_HEIGHT;
            case "MAP" -> ROW_HEIGHT + mapKeys.size() * (ROW_HEIGHT * 2 + 4) + ROW_HEIGHT;
            case "OPTIONAL" -> ROW_HEIGHT + (optionalPresent ? optionalInner.height() + ROW_HEIGHT : 0);
            case "UNION", "EITHER" -> ROW_HEIGHT + (unionVariants.isEmpty() ? 0 : unionVariants.get(unionIndex).height() + ROW_HEIGHT);
            default -> ROW_HEIGHT;
        };
    }

    /** 渲染控件，返回下一个可用的 y 坐标。 */
    public int render(CodecEditorScreen screen, int x, int y, int width) {
        String type = type();
        int cx = x + LABEL_WIDTH;
        int cw = Math.min(width - LABEL_WIDTH, CONTROL_WIDTH);

        switch (type) {
            case "RECORD" -> {
                for (EditorFormNode child : recordChildren) {
                    int indent = x + 10;
                    y = child.render(screen, indent, y, width - 10);
                }
            }
            case "LIST", "SET" -> {
                screen.drawLabel(x, y, label.isEmpty() ? "列表" : label);
                y += ROW_HEIGHT;
                for (int i = 0; i < listItems.size(); i++) {
                    EditorFormNode item = listItems.get(i);
                    final int idx = i;
                    screen.addButton(x, y, 24, "-", b -> {
                        listItems.remove(idx);
                        screen.rebuildForm();
                    });
                    y = item.render(screen, x + 28, y, width - 28);
                    y += 4;
                }
                screen.addButton(x, y, 80, "+ 添加", b -> {
                    listItems.add(new EditorFormNode(elementSchema(), "", JsonNull.INSTANCE));
                    screen.rebuildForm();
                });
                y += ROW_HEIGHT;
            }
            case "MAP" -> {
                screen.drawLabel(x, y, label.isEmpty() ? "映射" : label);
                y += ROW_HEIGHT;
                for (int i = 0; i < mapKeys.size(); i++) {
                    EditorFormNode key = mapKeys.get(i);
                    EditorFormNode val = mapValues.get(i);
                    final int idx = i;
                    screen.addButton(x, y, 24, "-", b -> {
                        mapKeys.remove(idx);
                        mapValues.remove(idx);
                        screen.rebuildForm();
                    });
                    y = key.render(screen, x + 28, y, width - 28);
                    y = val.render(screen, x + 28, y, width - 28);
                    y += 4;
                }
                screen.addButton(x, y, 80, "+ 添加", b -> {
                    mapKeys.add(new EditorFormNode(keySchema(), "", new JsonPrimitive("")));
                    mapValues.add(new EditorFormNode(valueSchema(), "", JsonNull.INSTANCE));
                    screen.rebuildForm();
                });
                y += ROW_HEIGHT;
            }
            case "OPTIONAL" -> {
                String presentText = optionalPresent ? "启用" : "禁用";
                screen.addButton(x, y, 60, presentText, b -> {
                    optionalPresent = !optionalPresent;
                    if (optionalPresent && optionalInner == null) {
                        optionalInner = new EditorFormNode(elementSchema(), "", JsonNull.INSTANCE);
                    }
                    screen.rebuildForm();
                });
                screen.drawLabel(x + 66, y, label);
                y += ROW_HEIGHT;
                if (optionalPresent && optionalInner != null) {
                    y = optionalInner.render(screen, x + 10, y, width - 10);
                }
            }
            case "UNION", "EITHER" -> {
                if (!unionVariants.isEmpty()) {
                    screen.drawLabel(x, y, "类型");
                    screen.addCycleButton(x + LABEL_WIDTH, y, cw, unionNames, unionIndex, idx -> {
                        unionIndex = idx;
                        unionVariants.get(idx).load(JsonNull.INSTANCE);
                        screen.rebuildForm();
                    });
                    y += ROW_HEIGHT;
                    EditorFormNode selected = unionVariants.get(unionIndex);
                    if (selected != null) {
                        y = selected.render(screen, x + 10, y, width - 10);
                    }
                }
            }
            case "ENUM" -> {
                screen.drawLabel(x, y, label);
                screen.addCycleButton(x + LABEL_WIDTH, y, cw, enumValues, enumIndex, i -> enumIndex = i);
                y += ROW_HEIGHT;
            }
            case "BOOLEAN" -> {
                boolean current = value.isJsonPrimitive() && value.getAsBoolean();
                screen.addButton(x + LABEL_WIDTH, y, cw, String.valueOf(current), b -> {
                    value = new JsonPrimitive(!(value.isJsonPrimitive() && value.getAsBoolean()));
                    screen.rebuildForm();
                });
                screen.drawLabel(x, y, label);
                y += ROW_HEIGHT;
            }
            default -> {
                screen.drawLabel(x, y, label);
                editBox = screen.addEditBox(cx, y, cw, textOf(value), s -> value = parseInput(s, type()));
                y += ROW_HEIGHT;
            }
        }
        return y;
    }

    /**
     * 探测当前值匹配哪个变体：
     * 1) 若值有分派键且其值与某个变体名相同，匹配该变体；
     * 2) 否则匹配第一个能解析出对象的变体；
     * 3) 兜底 0。
     */
    private int matchUnionVariant(JsonElement value) {
        if (value != null && value.isJsonObject() && schema.has("variantKey")) {
            JsonElement typeVal = value.getAsJsonObject().get(schema.get("variantKey").getAsString());
            if (typeVal != null && typeVal.isJsonPrimitive()) {
                String name = typeVal.getAsString();
                int idx = unionNames.indexOf(name);
                if (idx >= 0) {
                    return idx;
                }
            }
        }
        for (int i = 0; i < unionVariants.size(); i++) {
            unionVariants.get(i).load(value);
            if (unionVariants.get(i).collect().isJsonObject()) {
                return i;
            }
        }
        return 0;
    }

    private JsonObject elementSchema() {
        return schema.getAsJsonObject("element");
    }

    private JsonObject keySchema() {
        return schema.getAsJsonObject("key");
    }

    private JsonObject valueSchema() {
        return schema.getAsJsonObject("value");
    }

    // -------------------------------------------------
    // 值收集 / 加载
    // -------------------------------------------------

    /** 从控件收集 JSON 值。 */
    public JsonElement collect() {
        String type = type();
        switch (type) {
            case "RECORD" -> {
                JsonObject obj = new JsonObject();
                for (EditorFormNode child : recordChildren) {
                    JsonElement childValue = child.collect();
                    // 可选字段且未启用：省略该键（与 optionalFieldOf 缺失语义一致）。
                    if (child.isOptionalAndAbsent()) {
                        continue;
                    }
                    obj.add(child.label, childValue);
                }
                return obj;
            }
            case "LIST", "SET" -> {
                JsonArray arr = new JsonArray();
                for (EditorFormNode item : listItems) {
                    arr.add(item.collect());
                }
                return arr;
            }
            case "MAP" -> {
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
            case "OPTIONAL" -> {
                if (!optionalPresent || optionalInner == null) {
                    return JsonNull.INSTANCE;
                }
                return optionalInner.collect();
            }
            case "UNION", "EITHER" -> {
                if (unionVariants.isEmpty()) {
                    return JsonNull.INSTANCE;
                }
                JsonElement inner = unionVariants.get(unionIndex).collect();
                String variantKey = schema.has("variantKey") ? schema.get("variantKey").getAsString() : "type";
                String variantName = unionIndex < unionNames.size() ? unionNames.get(unionIndex) : "";
                // 用 {分派键: 变体名, ...变体字段} 组装，符合 dispatch codec 的 JSON 结构。
                JsonObject obj = new JsonObject();
                obj.add(variantKey, new JsonPrimitive(variantName));
                if (inner.isJsonObject()) {
                    for (var e : inner.getAsJsonObject().entrySet()) {
                        obj.add(e.getKey(), e.getValue());
                    }
                }
                return obj;
            }
            case "ENUM" -> {
                return enumIndex >= 0 && enumIndex < enumValues.size()
                        ? new JsonPrimitive(enumValues.get(enumIndex)) : JsonNull.INSTANCE;
            }
            default -> {
                return value == null ? JsonNull.INSTANCE : value;
            }
        }
    }

    /** 把 JSON 值加载回控件。 */
    public void load(JsonElement json) {
        this.value = json == null ? defaultFor(schema) : json;
        if (this.value == null) {
            this.value = JsonNull.INSTANCE;
        }
        String type = type();
        switch (type) {
            case "RECORD" -> {
                for (EditorFormNode child : recordChildren) {
                    child.load(fieldValue(child.label));
                }
            }
            case "LIST", "SET" -> {
                listItems.clear();
                if (value.isJsonArray()) {
                    for (JsonElement e : value.getAsJsonArray()) {
                        listItems.add(new EditorFormNode(elementSchema(), "", e));
                    }
                }
            }
            case "MAP" -> {
                mapKeys.clear();
                mapValues.clear();
                if (value.isJsonObject()) {
                    for (var entry : value.getAsJsonObject().entrySet()) {
                        mapKeys.add(new EditorFormNode(keySchema(), "", new JsonPrimitive(entry.getKey())));
                        mapValues.add(new EditorFormNode(valueSchema(), "", entry.getValue()));
                    }
                }
            }
            case "OPTIONAL" -> {
                optionalPresent = value != null && !value.isJsonNull();
                optionalInner = new EditorFormNode(elementSchema(), "", optionalPresent ? value : JsonNull.INSTANCE);
            }
            case "UNION", "EITHER" -> {
                if (!unionVariants.isEmpty()) {
                    unionIndex = matchUnionVariant(value);
                    unionVariants.get(unionIndex).load(value);
                }
            }
            case "ENUM" -> {
                String current = value.isJsonPrimitive() ? value.getAsString() : "";
                enumIndex = Math.max(0, enumValues.indexOf(current));
            }
            default -> {
                if (editBox != null) {
                    editBox.setValue(textOf(value));
                }
            }
        }
    }

    /** 该节点是否"可选且当前未启用"（用于 RECORD 收集时省略键）。 */
    private boolean isOptionalAndAbsent() {
        return type().equals("OPTIONAL") && !optionalPresent;
    }

    private JsonElement fieldValue(String name) {
        if (value != null && value.isJsonObject() && value.getAsJsonObject().has(name)) {
            return value.getAsJsonObject().get(name);
        }
        return null;
    }

    private String type() {
        return schema.has("type") ? schema.get("type").getAsString() : "UNKNOWN";
    }

    private String textOf(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        return e.toString();
    }

    private static JsonElement defaultFor(JsonObject schema) {
        String type = schema.has("type") ? schema.get("type").getAsString() : "UNKNOWN";
        return switch (type) {
            case "RECORD" -> new JsonObject();
            case "LIST", "SET" -> new JsonArray();
            case "MAP" -> new JsonObject();
            case "OPTIONAL" -> JsonNull.INSTANCE;
            case "BOOLEAN" -> new JsonPrimitive(false);
            case "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE" -> new JsonPrimitive(0);
            default -> new JsonPrimitive("");
        };
    }

    private static JsonElement parseInput(String text, String type) {
        if (text == null || text.isEmpty()) {
            return switch (type) {
                case "STRING", "RESOURCE_LOCATION", "COMPONENT", "UNKNOWN", "HOLDER", "HOLDER_SET", "REGISTRY" -> new JsonPrimitive("");
                default -> JsonNull.INSTANCE;
            };
        }
        return switch (type) {
            case "INT" -> new JsonPrimitive((int) parseDouble(text));
            case "LONG" -> new JsonPrimitive((long) parseDouble(text));
            case "FLOAT" -> new JsonPrimitive((float) parseDouble(text));
            case "DOUBLE" -> new JsonPrimitive(parseDouble(text));
            case "BYTE" -> new JsonPrimitive((byte) parseDouble(text));
            case "SHORT" -> new JsonPrimitive((short) parseDouble(text));
            default -> new JsonPrimitive(text);
        };
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
