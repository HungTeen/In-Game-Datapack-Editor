package hungteen.ide.client.gui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.ide.common.codec.parser.SchemaKeys;
import hungteen.ide.common.codec.parser.SchemaType;

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
 * <p>行内<b>只显示字段名</b>；类型、必填、范围、默认值、枚举、注册表等全部放在
 * 悬浮提示里展示。容器节点行首有 {@code [-]}/{@code [+]}} 折叠按钮，
 * 子树的起始/结束用垂直参考线连接。</p>
 *
 * <p>类型判断基于 {@link SchemaType} 枚举（服务端序列化名一致），布局/配色统一走
 * {@link ViewerStyle}，schema JSON 键名走 {@link SchemaKeys}。</p>
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 16:40
 **/
public class ViewerNode {

    // ---- 结构标签 ----
    private static final String ELEMENT_LABEL = "元素";
    private static final String KEY_LABEL = "键";
    private static final String VALUE_LABEL = "值";
    private static final String UNION_FALLBACK_LABEL = "多态";
    private static final String VARIANT_FALLBACK_PREFIX = "分支";
    private static final String DEFAULT_VARIANT_KEY = "type";

    // ---- 提示内容限制 ----
    /** 悬浮提示里的枚举上限。 */
    private static final int TOOLTIP_ENUM_SHOWN = 20;
    /** 悬浮提示文本上限。 */
    private static final int MAX_TOOLTIP_TEXT = 240;

    // ---- 约束字符串解析 ----
    private static final Pattern RANGE = Pattern.compile("^Range\\[min=(.*), max=(.*)]$");
    private static final Pattern MIN = Pattern.compile("^Min\\[value=(.*)]$");
    private static final Pattern MAX = Pattern.compile("^Max\\[value=(.*)]$");
    private static final Pattern LENGTH = Pattern.compile("^Length\\[min=(\\d+), max=(\\d+)]$");
    private static final Pattern SIZE = Pattern.compile("^Size\\[min=(\\d+), max=(\\d+)]$");

    private final JsonObject schema;
    /** 字段元信息（required / default / path），根节点、元素与变体为 null。 */
    private final JsonObject fieldMeta;
    private final String label;
    private final int depth;
    private final List<ViewerNode> children = new ArrayList<>();

    private boolean collapsed = false;

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
        switch (type()) {
            case RECORD -> {
                if (!schema.has(SchemaKeys.FIELDS)) break;
                for (JsonElement f : schema.getAsJsonArray(SchemaKeys.FIELDS)) {
                    JsonObject field = f.getAsJsonObject();
                    String name = field.get(SchemaKeys.NAME).getAsString();
                    JsonObject fieldSchema = field.getAsJsonObject(SchemaKeys.SCHEMA);
                    children.add(new ViewerNode(fieldSchema, field, name, depth + 1));
                }
            }
            case LIST, SET -> {
                if (schema.has(SchemaKeys.ELEMENT)) {
                    addElementChild(schema.getAsJsonObject(SchemaKeys.ELEMENT));
                }
            }
            case MAP -> {
                if (schema.has(SchemaKeys.KEY)) {
                    children.add(new ViewerNode(schema.getAsJsonObject(SchemaKeys.KEY), null, KEY_LABEL, depth + 1));
                }
                if (schema.has(SchemaKeys.VALUE)) {
                    addElementChild(schema.getAsJsonObject(SchemaKeys.VALUE));
                }
            }
            case OPTIONAL -> {
                if (schema.has(SchemaKeys.ELEMENT)) {
                    addElementChild(schema.getAsJsonObject(SchemaKeys.ELEMENT));
                }
            }
            case UNION, EITHER -> {
                variantKey = schema.has(SchemaKeys.VARIANT_KEY)
                    ? schema.get(SchemaKeys.VARIANT_KEY).getAsString() : DEFAULT_VARIANT_KEY;
                if (schema.has(SchemaKeys.VARIANTS)) {
                    JsonArray variants = schema.getAsJsonArray(SchemaKeys.VARIANTS);
                    for (JsonElement v : variants) {
                        JsonObject vo = v.getAsJsonObject();
                        unionNames.add(vo.has(SchemaKeys.NAME) ? vo.get(SchemaKeys.NAME).getAsString()
                            : (VARIANT_FALLBACK_PREFIX + unionNames.size()));
                    }
                }
                selectVariant(0);
            }
            default -> {
            }
        }
    }

    /**
     * 添加一个"元素/值"子节点。
     *
     * <p>若元素是结构型（RECORD / LIST / MAP / ...），直接<b>内联</b>到当前节点
     * （空标签、同级缩进），避免出现"元素 RECORD"这类冗余中间行和多余的折叠层级；
     * 标量元素则保留"元素"标签行。</p>
     */
    private void addElementChild(JsonObject elementSchema) {
        if (isStructural(typeOf(elementSchema))) {
            children.add(new ViewerNode(elementSchema, null, "", depth));
        } else {
            children.add(new ViewerNode(elementSchema, null, ELEMENT_LABEL, depth + 1));
        }
    }

    private static boolean isStructural(SchemaType type) {
        return switch (type) {
            case RECORD, LIST, SET, MAP, OPTIONAL, UNION, EITHER -> true;
            default -> false;
        };
    }

    /** 选中某个 UNION 分支。 */
    public void selectVariant(int index) {
        if (schema == null || !schema.has(SchemaKeys.VARIANTS)) return;
        JsonArray variants = schema.getAsJsonArray(SchemaKeys.VARIANTS);
        if (variants.size() == 0) return;
        unionIndex = Math.max(0, Math.min(index, variants.size() - 1));
        selectedVariant = new ViewerNode(variants.get(unionIndex).getAsJsonObject(), "", depth + 1);
    }

    public int unionIndex() {
        return unionIndex;
    }

    /** 渲染字段树，返回下一个可用 y。 */
    public int render(CodecViewerScreen screen, int x, int y, int width) {
        SchemaType type = type();
        int indent = x + depth * ViewerStyle.INDENT;
        int labelWidth = Math.max(ViewerStyle.MIN_LABEL_WIDTH,
            width - depth * ViewerStyle.INDENT - ViewerStyle.TYPE_BUTTON_WIDTH
                - ViewerStyle.TOGGLE_WIDTH - ViewerStyle.TYPE_BUTTON_GAP);

        switch (type) {
            case UNION, EITHER -> {
                int rowY = y;
                List<Seg> segs = List.of(new Seg(label.isEmpty() ? UNION_FALLBACK_LABEL : label, ViewerStyle.COLOR_TEXT));
                int endX = drawLine(screen, indent + ViewerStyle.TOGGLE_WIDTH + ViewerStyle.TOGGLE_GAP, rowY, segs,
                    screen.isRowInView(rowY));
                if (screen.isRowFullyVisible(rowY)) {
                    screen.addToggleButton(indent, rowY, ViewerStyle.TOGGLE_WIDTH, collapsed ? "+" : "-",
                        () -> toggle(screen));
                    if (!unionNames.isEmpty()) {
                        screen.addCycleButton(indent + labelWidth, rowY, ViewerStyle.TYPE_BUTTON_WIDTH, unionNames,
                            unionIndex, idx -> {
                                selectVariant(idx);
                                screen.rebuild();
                            });
                    }
                    screen.addTooltipRow(indent, rowY,
                        Math.max(endX, indent + labelWidth + ViewerStyle.TYPE_BUTTON_WIDTH) - indent, fullLine());
                }
                y += ViewerStyle.ROW_HEIGHT;
                if (!collapsed && selectedVariant != null) {
                    y = selectedVariant.render(screen, x, y, width);
                    if (screen.isRowInView(rowY)) {
                        screen.addGuideLine(indent + ViewerStyle.TOGGLE_WIDTH + 1,
                            rowY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                    }
                }
                return y;
            }
            case RECORD -> {
                int headerY = -1;
                if (!label.isEmpty()) {
                    headerY = y;
                    int endX = drawLine(screen, indent + ViewerStyle.TOGGLE_WIDTH + ViewerStyle.TOGGLE_GAP, headerY,
                        describeSegments(), screen.isRowInView(headerY));
                    if (screen.isRowFullyVisible(headerY)) {
                        screen.addToggleButton(indent, headerY, ViewerStyle.TOGGLE_WIDTH, collapsed ? "+" : "-",
                            () -> toggle(screen));
                        screen.addTooltipRow(indent, headerY, endX - indent, fullLine());
                    }
                    y += ViewerStyle.ROW_HEIGHT;
                }
                if (!collapsed) {
                    for (ViewerNode child : children) {
                        y = child.render(screen, x, y, width);
                    }
                    if (headerY >= 0 && !children.isEmpty() && screen.isRowInView(headerY)) {
                        screen.addGuideLine(indent + ViewerStyle.TOGGLE_WIDTH + 1,
                            headerY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                    }
                }
                return y;
            }
            case LIST, SET, OPTIONAL, MAP -> {
                int rowY = y;
                int endX = drawLine(screen, indent + ViewerStyle.TOGGLE_WIDTH + ViewerStyle.TOGGLE_GAP, rowY,
                    describeSegments(), screen.isRowInView(rowY));
                if (screen.isRowFullyVisible(rowY)) {
                    screen.addToggleButton(indent, rowY, ViewerStyle.TOGGLE_WIDTH, collapsed ? "+" : "-",
                        () -> toggle(screen));
                    screen.addTooltipRow(indent, rowY, endX - indent, fullLine());
                }
                y += ViewerStyle.ROW_HEIGHT;
                if (!collapsed) {
                    for (ViewerNode child : children) {
                        y = child.render(screen, x, y, width);
                    }
                    if (!children.isEmpty() && screen.isRowInView(rowY)) {
                        screen.addGuideLine(indent + ViewerStyle.TOGGLE_WIDTH + 1,
                            rowY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                    }
                }
                return y;
            }
            default -> {
                int endX = drawLine(screen, indent, y, describeSegments(), screen.isRowInView(y));
                if (screen.isRowFullyVisible(y)) {
                    screen.addTooltipRow(indent, y, endX - indent, fullLine());
                }
                y += ViewerStyle.ROW_HEIGHT;
                return y;
            }
        }
    }

    /** 切换折叠状态并重建界面。 */
    private void toggle(CodecViewerScreen screen) {
        collapsed = !collapsed;
        screen.rebuild();
    }

    /** 预览整棵子树占用的总行高（用于滚动范围）。 */
    public int height() {
        return switch (type()) {
            case UNION, EITHER ->
                ViewerStyle.ROW_HEIGHT + (collapsed || selectedVariant == null ? 0 : selectedVariant.height());
            case RECORD -> {
                int h = label.isEmpty() ? 0 : ViewerStyle.ROW_HEIGHT;
                if (!collapsed) {
                    for (ViewerNode child : children) {
                        h += child.height();
                    }
                }
                yield h;
            }
            case LIST, SET, OPTIONAL, MAP -> {
                int h = ViewerStyle.ROW_HEIGHT;
                if (!collapsed) {
                    for (ViewerNode child : children) {
                        h += child.height();
                    }
                }
                yield h;
            }
            default -> ViewerStyle.ROW_HEIGHT;
        };
    }

    /** 行内显示名：字段名；无字段名（内联结构）时用类型名。 */
    private String displayName() {
        return label.isEmpty() ? type().name() : label;
    }

    /** 一行描述的彩色片段：现在只显示字段名。 */
    private List<Seg> describeSegments() {
        return List.of(new Seg(displayName(), ViewerStyle.COLOR_TEXT));
    }

    /** 完整行信息（悬浮提示用，不截断约束、枚举展示更多）。 */
    private String fullLine() {
        StringBuilder sb = new StringBuilder();
        if (label.isEmpty()) {
            sb.append(type().name());
        } else {
            sb.append(label);
            if (fieldMeta != null && fieldMeta.has(SchemaKeys.REQUIRED)) {
                sb.append(fieldMeta.get(SchemaKeys.REQUIRED).getAsBoolean() ? " [必填]" : " [可选]");
            }
            sb.append(": ").append(type().name());
        }
        if (schema.has(SchemaKeys.JAVA_TYPE)) {
            sb.append("(").append(schema.get(SchemaKeys.JAVA_TYPE).getAsString()).append(")");
        }
        if (schema.has(SchemaKeys.REGISTRY)) {
            JsonObject reg = schema.getAsJsonObject(SchemaKeys.REGISTRY);
            sb.append(" → ").append(reg.get(SchemaKeys.REGISTRY).getAsString());
            if (reg.has(SchemaKeys.ALLOW_TAG) && reg.get(SchemaKeys.ALLOW_TAG).getAsBoolean()) {
                sb.append(" (+tag)");
            }
        }
        String constraints = formatConstraints(schema);
        if (!constraints.isEmpty()) {
            sb.append("  约束: ").append(constraints);
        }
        String def = defaultText();
        if (!def.isEmpty()) {
            sb.append("  默认: ").append(def);
        }
        if (schema.has(SchemaKeys.ENUM_VALUES)) {
            JsonArray evs = schema.getAsJsonArray(SchemaKeys.ENUM_VALUES);
            if (evs.size() > 0) {
                sb.append("  = ");
                int shown = Math.min(evs.size(), TOOLTIP_ENUM_SHOWN);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(evs.get(i).getAsJsonObject().get(SchemaKeys.SERIALIZED_NAME).getAsString());
                }
                if (evs.size() > shown) sb.append("… (共 ").append(evs.size()).append(" 个)");
            }
        }
        if (isUnion() && variantKey != null) {
            sb.append("  分派键: ").append(variantKey);
            if (!unionNames.isEmpty() && unionIndex >= 0 && unionIndex < unionNames.size()) {
                sb.append("  当前: ").append(unionNames.get(unionIndex));
            }
        }
        return truncate(sb.toString(), MAX_TOOLTIP_TEXT);
    }

    /** 依次绘制彩色片段，返回下一个可用 x。 */
    private int drawLine(CodecViewerScreen screen, int x, int y, List<Seg> segs, boolean visible) {
        if (!visible) {
            return x;
        }
        int cx = x;
        for (Seg seg : segs) {
            if (seg.text().isEmpty()) continue;
            screen.drawLabel(cx, y, seg.text(), seg.color());
            cx += screen.fontWidth(seg.text());
        }
        return cx;
    }

    /** 把 constraints JSON 数组格式化为可读文本。 */
    private static String formatConstraints(JsonObject schema) {
        if (!schema.has(SchemaKeys.CONSTRAINTS)) return "";
        List<String> parts = new ArrayList<>();
        for (JsonElement c : schema.getAsJsonArray(SchemaKeys.CONSTRAINTS)) {
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
        if (fieldMeta != null && fieldMeta.has(SchemaKeys.DEFAULT)) {
            d = fieldMeta.get(SchemaKeys.DEFAULT);
        } else if (schema.has(SchemaKeys.DEFAULT)) {
            d = schema.get(SchemaKeys.DEFAULT);
        }
        if (d == null || d.isJsonNull()) return "";
        if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isString()) {
            return d.getAsString();
        }
        return d.toString();
    }

    private boolean isUnion() {
        SchemaType t = type();
        return t == SchemaType.UNION || t == SchemaType.EITHER;
    }

    private static SchemaType typeOf(JsonObject schema) {
        if (schema == null || !schema.has(SchemaKeys.TYPE)) {
            return SchemaType.UNKNOWN;
        }
        try {
            return SchemaType.valueOf(schema.get(SchemaKeys.TYPE).getAsString());
        } catch (IllegalArgumentException e) {
            return SchemaType.UNKNOWN;
        }
    }

    private SchemaType type() {
        return typeOf(schema);
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

    /** 一行中的一个彩色片段。 */
    private record Seg(String text, int color) {
    }
}
