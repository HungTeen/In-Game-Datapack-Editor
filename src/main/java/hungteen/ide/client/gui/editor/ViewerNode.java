package hungteen.ide.client.gui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 schema 查看节点：把 schema JSON 渲染成缩进字段树。
 *
 * <p>与 {@link EditorFormNode} 不同，这里只展示"json 包含哪些字段"，不做编辑。
 * UNION（dispatch）节点提供类型循环按钮，选中 type 后展开对应分支的字段。</p>
 *
 * <p>每一行由多个彩色片段组成：字段名（白）、必填/可选标记（红/灰）、类型（浅灰）、
 * 注册表引用（蓝）、约束条件（黄）、默认值（绿）、枚举取值（紫）。</p>
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 16:40
 **/
public class ViewerNode {

    private static final int ROW_HEIGHT = 14;
    private static final int TYPE_BUTTON_WIDTH = 140;

    // 片段配色
    private static final int COLOR_NAME = 0xFFFFFF;
    private static final int COLOR_TYPE = 0xD0D0D0;
    private static final int COLOR_REQUIRED = 0xFF6B6B;
    private static final int COLOR_OPTIONAL = 0x9E9E9E;
    private static final int COLOR_CONSTRAINT = 0xFFD54F;
    private static final int COLOR_DEFAULT = 0x7CFC7C;
    private static final int COLOR_REGISTRY = 0x6ECBFF;
    private static final int COLOR_ENUM = 0xD4A9FF;

    /** 约束文本过长时截断，避免超出屏幕。 */
    private static final int MAX_CONSTRAINT_TEXT = 64;
    /** 枚举最多展示个数。 */
    private static final int MAX_ENUM_SHOWN = 6;

    private static final Pattern RANGE = Pattern.compile("^Range\\[min=(.*), max=(.*)]$");
    private static final Pattern MIN = Pattern.compile("^Min\\[value=(.*)]$");
    private static final Pattern MAX = Pattern.compile("^Max\\[value=(.*)]$");
    private static final Pattern LENGTH = Pattern.compile("^Length\\[min=(\\d+), max=(\\d+)]$");
    private static final Pattern SIZE = Pattern.compile("^Size\\[min=(\\d+), max=(\\d+)]$");

    private final JsonObject schema;
    /** 字段元信息（required / default / path），根节点与变体为 null。 */
    private final JsonObject fieldMeta;
    private final String label;
    private final int depth;
    private final List<ViewerNode> children = new ArrayList<>();

    // UNION
    private final List<String> unionNames = new ArrayList<>();
    private int unionIndex = 0;
    private ViewerNode selectedVariant;
    private String variantKey;

    public ViewerNode(JsonObject schema, String label, int depth) {
        this(schema, null, label, depth);
    }

    public ViewerNode(JsonObject schema, JsonObject fieldMeta, String label, int depth) {
        this.schema = schema;
        this.fieldMeta = fieldMeta;
        this.label = label == null ? "" : label;
        this.depth = depth;
        init();
    }

    public static ViewerNode root(JsonObject schema) {
        return new ViewerNode(schema, "", 0);
    }

    private void init() {
        String type = type();
        switch (type) {
            case "RECORD" -> {
                if (!schema.has("fields")) break;
                for (JsonElement f : schema.getAsJsonArray("fields")) {
                    JsonObject field = f.getAsJsonObject();
                    String name = field.get("name").getAsString();
                    JsonObject fieldSchema = field.getAsJsonObject("schema");
                    children.add(new ViewerNode(fieldSchema, field, name, depth + 1));
                }
            }
            case "LIST", "SET" -> {
                if (schema.has("element")) {
                    children.add(new ViewerNode(schema.getAsJsonObject("element"), "元素", depth + 1));
                }
            }
            case "MAP" -> {
                if (schema.has("key")) {
                    children.add(new ViewerNode(schema.getAsJsonObject("key"), "键", depth + 1));
                }
                if (schema.has("value")) {
                    children.add(new ViewerNode(schema.getAsJsonObject("value"), "值", depth + 1));
                }
            }
            case "OPTIONAL" -> {
                if (schema.has("element")) {
                    children.add(new ViewerNode(schema.getAsJsonObject("element"), "元素", depth + 1));
                }
            }
            case "UNION", "EITHER" -> {
                variantKey = schema.has("variantKey") ? schema.get("variantKey").getAsString() : "type";
                if (schema.has("variants")) {
                    JsonArray variants = schema.getAsJsonArray("variants");
                    for (JsonElement v : variants) {
                        JsonObject vo = v.getAsJsonObject();
                        unionNames.add(vo.has("name") ? vo.get("name").getAsString() : ("分支" + unionNames.size()));
                    }
                }
                selectVariant(0);
            }
            default -> {
            }
        }
    }

    /** 选中某个 UNION 分支。 */
    public void selectVariant(int index) {
        if (schema == null || !schema.has("variants")) return;
        JsonArray variants = schema.getAsJsonArray("variants");
        if (variants.size() == 0) return;
        unionIndex = Math.max(0, Math.min(index, variants.size() - 1));
        selectedVariant = new ViewerNode(variants.get(unionIndex).getAsJsonObject(), "", depth + 1);
    }

    public int unionIndex() {
        return unionIndex;
    }

    /** 渲染字段树，返回下一个可用 y。 */
    public int render(CodecViewerScreen screen, int x, int y, int width) {
        String type = type();
        int indent = x + depth * 10;
        int labelWidth = Math.max(40, width - depth * 10 - TYPE_BUTTON_WIDTH - 10);

        switch (type) {
            case "UNION", "EITHER" -> {
                List<Seg> segs = new ArrayList<>();
                segs.add(new Seg(label.isEmpty() ? "多态" : label, COLOR_NAME));
                segs.add(new Seg("（分派键: " + variantKey + "）", COLOR_TYPE));
                drawLine(screen, indent, y, segs);
                if (!unionNames.isEmpty()) {
                    screen.addCycleButton(indent + labelWidth, y, TYPE_BUTTON_WIDTH, unionNames, unionIndex, idx -> {
                        selectVariant(idx);
                        screen.rebuild();
                    });
                }
                y += ROW_HEIGHT;
                if (selectedVariant != null) {
                    y = selectedVariant.render(screen, x, y, width);
                }
                return y;
            }
            case "RECORD" -> {
                if (!label.isEmpty()) {
                    drawLine(screen, indent, y, describeSegments());
                    y += ROW_HEIGHT;
                }
                for (ViewerNode child : children) {
                    y = child.render(screen, x, y, width);
                }
                return y;
            }
            case "LIST", "SET", "OPTIONAL", "MAP" -> {
                drawLine(screen, indent, y, describeSegments());
                y += ROW_HEIGHT;
                for (ViewerNode child : children) {
                    y = child.render(screen, x, y, width);
                }
                return y;
            }
            default -> {
                drawLine(screen, indent, y, describeSegments());
                y += ROW_HEIGHT;
                return y;
            }
        }
    }

    /** 预估整棵子树占用的总行高（用于滚动范围）。 */
    public int height() {
        return switch (type()) {
            case "UNION", "EITHER" -> ROW_HEIGHT + (selectedVariant == null ? 0 : selectedVariant.height());
            case "RECORD" -> {
                int h = label.isEmpty() ? 0 : ROW_HEIGHT;
                for (ViewerNode child : children) {
                    h += child.height();
                }
                yield h;
            }
            case "LIST", "SET", "OPTIONAL", "MAP" -> {
                int h = ROW_HEIGHT;
                for (ViewerNode child : children) {
                    h += child.height();
                }
                yield h;
            }
            default -> ROW_HEIGHT;
        };
    }

    /** 一行描述的全部彩色片段。 */
    private List<Seg> describeSegments() {
        List<Seg> segs = new ArrayList<>();
        String typeText = type();
        String java = schema.has("javaType") ? "(" + simpleName(schema.get("javaType").getAsString()) + ")" : "";

        if (label.isEmpty()) {
            segs.add(new Seg(typeText + java, COLOR_TYPE));
        } else {
            segs.add(new Seg(label, COLOR_NAME));
            if (fieldMeta != null && fieldMeta.has("required")) {
                boolean req = fieldMeta.get("required").getAsBoolean();
                segs.add(new Seg(req ? " [必填]" : " [可选]", req ? COLOR_REQUIRED : COLOR_OPTIONAL));
            }
            segs.add(new Seg(": " + typeText + java, COLOR_TYPE));
        }

        if (schema.has("registry")) {
            JsonObject reg = schema.getAsJsonObject("registry");
            StringBuilder sb = new StringBuilder(" → ").append(reg.get("registry").getAsString());
            if (reg.has("allowTag") && reg.get("allowTag").getAsBoolean()) {
                sb.append(" (+tag)");
            }
            segs.add(new Seg(sb.toString(), COLOR_REGISTRY));
        }

        String constraints = formatConstraints(schema);
        if (!constraints.isEmpty()) {
            segs.add(new Seg("  约束: " + truncate(constraints, MAX_CONSTRAINT_TEXT), COLOR_CONSTRAINT));
        }

        String def = defaultText();
        if (!def.isEmpty()) {
            segs.add(new Seg("  默认: " + def, COLOR_DEFAULT));
        }

        if (schema.has("enumValues")) {
            JsonArray evs = schema.getAsJsonArray("enumValues");
            if (evs.size() > 0) {
                StringBuilder sb = new StringBuilder("  = ");
                int shown = Math.min(evs.size(), MAX_ENUM_SHOWN);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(evs.get(i).getAsJsonObject().get("serializedName").getAsString());
                }
                if (evs.size() > shown) sb.append("…");
                segs.add(new Seg(sb.toString(), COLOR_ENUM));
            }
        }

        return segs;
    }

    /** 依次绘制彩色片段，返回下一个可用 x。 */
    private void drawLine(CodecViewerScreen screen, int x, int y, List<Seg> segs) {
        int cx = x;
        for (Seg seg : segs) {
            if (seg.text().isEmpty()) continue;
            screen.drawLabel(cx, y, seg.text(), seg.color());
            cx += screen.fontWidth(seg.text());
        }
    }

    /** 把 constraints JSON 数组格式化为可读文本。 */
    private static String formatConstraints(JsonObject schema) {
        if (!schema.has("constraints")) return "";
        List<String> parts = new ArrayList<>();
        for (JsonElement c : schema.getAsJsonArray("constraints")) {
            String f = formatConstraint(c.getAsString());
            if (f != null && !f.isEmpty()) {
                parts.add(f);
            }
        }
        return String.join("; ", parts);
    }

    /** 单条约束字符串 → 可读文本（未知格式原样返回）。 */
    private static String formatConstraint(String s) {
        if (s == null) return "";
        Matcher m;
        if ((m = RANGE.matcher(s)).matches()) {
            return "∈ [" + trimNum(m.group(1)) + ", " + trimNum(m.group(2)) + "]";
        }
        if ((m = MIN.matcher(s)).matches()) {
            return "≥ " + trimNum(m.group(1));
        }
        if ((m = MAX.matcher(s)).matches()) {
            return "≤ " + trimNum(m.group(1));
        }
        if ((m = LENGTH.matcher(s)).matches()) {
            return "长度 ∈ [" + m.group(1) + ", " + m.group(2) + "]";
        }
        if ((m = SIZE.matcher(s)).matches()) {
            return "个数 ∈ [" + m.group(1) + ", " + m.group(2) + "]";
        }
        if (s.startsWith("Pattern[regex=")) {
            String inner = s.substring("Pattern[regex=".length());
            if (inner.endsWith("]")) {
                inner = inner.substring(0, inner.length() - 1);
            }
            return "匹配 /" + inner + "/";
        }
        return s;
    }

    /** 字段默认值文本（优先字段级，其次节点级）。 */
    private String defaultText() {
        JsonElement d = null;
        if (fieldMeta != null && fieldMeta.has("default")) {
            d = fieldMeta.get("default");
        } else if (schema.has("default")) {
            d = schema.get("default");
        }
        if (d == null || d.isJsonNull()) return "";
        if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isString()) {
            return d.getAsString();
        }
        return d.toString();
    }

    /** 把 "10.0" 这类数值格式化成 "10"。 */
    private static String trimNum(String s) {
        String t = s.trim();
        if (t.endsWith(".0") && !t.contains("e") && !t.contains("E")) {
            return t.substring(0, t.length() - 2);
        }
        return t;
    }

    private static String truncate(String text, int max) {
        if (text != null && text.length() > max) {
            return text.substring(0, max - 1) + "…";
        }
        return text;
    }

    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private String type() {
        return schema != null && schema.has("type") ? schema.get("type").getAsString() : "UNKNOWN";
    }

    /** 一行中的一个彩色片段。 */
    private record Seg(String text, int color) {
    }
}
