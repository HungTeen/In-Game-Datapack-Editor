package hungteen.ide.client.gui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hungteen.ide.common.codec.parser.SchemaKeys;
import hungteen.ide.common.codec.parser.SchemaType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读 schema 查看节点：把 schema JSON 渲染成缩进字段树。
 *
 * <p>与 {@link EditorFormNode} 不同，这里只展示"json 包含哪些字段"，不做编辑。
 * UNION（dispatch）节点提供类型循环按钮，选中 type 后展开对应分支的字段。</p>
 *
 * <p>布局约定：</p>
 * <ul>
 *   <li>行内<b>只显示字段名</b>，同一层级的字段左对齐；折叠按钮放在字段名之后
 *       （{@code [-]} / {@code [+]}）；</li>
 *   <li>类型、必填、范围、默认值、枚举、注册表、容器元素/键/值、UNION 全部类型等放在
 *       <b>彩色多行悬浮提示</b>里（{@link #tooltipLines()}）；</li>
 *   <li>标量的元素/键/值<b>不单独占行</b>；结构型子结构内联展开。</li>
 * </ul>
 *
 * <p>类型判断基于 {@link SchemaType} 枚举（服务端序列化名一致），布局/配色统一走
 * {@link ViewerStyle}，schema JSON 键名走 {@link SchemaKeys}。</p>
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 16:40
 **/
public class ViewerNode {

    // ---- 提示文本 ----
    private static final String VARIANT_FALLBACK_PREFIX = "分支";
    private static final String DEFAULT_VARIANT_KEY = "type";

    /** 悬浮提示里的枚举上限。 */
    private static final int TOOLTIP_ENUM_SHOWN = 20;

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
            case LIST, SET -> inlineStructuralChild(schema.getAsJsonObject(SchemaKeys.ELEMENT));
            case MAP -> {
                inlineStructuralChild(schema.getAsJsonObject(SchemaKeys.KEY));
                inlineStructuralChild(schema.getAsJsonObject(SchemaKeys.VALUE));
            }
            case OPTIONAL -> inlineStructuralChild(schema.getAsJsonObject(SchemaKeys.ELEMENT));
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
     * 结构型子结构内联展开；标量元素/键/值<b>不创建行</b>（信息并入容器 tooltip）。
     */
    private void inlineStructuralChild(JsonObject childSchema) {
        if (childSchema != null && isStructural(typeOf(childSchema))) {
            children.add(new ViewerNode(childSchema, null, "", depth));
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

    /** 全部可选的类型名（UNION 下拉用）。 */
    public List<String> unionNames() {
        return List.copyOf(unionNames);
    }

    /** 渲染字段树，返回下一个可用 y。 */
    public int render(CodecViewerScreen screen, int x, int y, int width) {
        SchemaType type = type();
        int indent = x + depth * ViewerStyle.INDENT;
        int labelWidth = Math.max(ViewerStyle.MIN_LABEL_WIDTH,
            width - depth * ViewerStyle.INDENT - ViewerStyle.TYPE_BUTTON_WIDTH - ViewerStyle.TYPE_BUTTON_GAP);

        switch (type) {
            case UNION, EITHER -> {
                int rowY = y;
                List<Seg> segs = List.of(new Seg(label.isEmpty() ? I18n.get("ide.tree.polymorphic") : label,
                    ViewerStyle.COLOR_TEXT));
                int endX = drawLine(screen, indent, rowY, segs, screen.isRowInView(rowY));
                int buttonX = endX + ViewerStyle.TOGGLE_GAP;
                int typeBtnX = indent + labelWidth;
                boolean fullyVisible = screen.isRowFullyVisible(rowY);
                if (fullyVisible) {
                    if (!unionNames.isEmpty()) {
                        screen.addToggleButton(buttonX, toggleButtonY(rowY), ViewerStyle.TOGGLE_WIDTH,
                            collapsed ? "+" : "-",
                            () -> toggle(screen));
                    }
                    String current = unionIndex >= 0 && unionIndex < unionNames.size()
                        ? unionNames.get(unionIndex) : "";
                    screen.addTypeButton(this, typeBtnX, rowY, ViewerStyle.TYPE_BUTTON_WIDTH,
                        unionNames.isEmpty() ? "类型: (无)" : "类型: " + current,
                        () -> screen.toggleUnionDropdown(this));
                    screen.addTooltipRow(indent, rowY,
                        Math.max(endX, typeBtnX + ViewerStyle.TYPE_BUTTON_WIDTH) - indent, tooltipLines());
                }
                // 类型下拉弹层
                if (screen.isUnionDropdownOpen(this)) {
                    int popupY = rowY + ViewerStyle.ROW_HEIGHT;
                    screen.addUnionDropdownButtons(typeBtnX, popupY, ViewerStyle.TYPE_BUTTON_WIDTH, unionNames,
                        idx -> selectVariant(idx));
                }
                y += ViewerStyle.ROW_HEIGHT;
                if (!collapsed && selectedVariant != null) {
                    y = selectedVariant.render(screen, x, y, width);
                    screen.addGuideLine(buttonX + ViewerStyle.TOGGLE_WIDTH / 2,
                        rowY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                }
                return y;
            }
            case RECORD -> {
                int headerY = -1;
                int buttonX = -1;
                if (!label.isEmpty()) {
                    headerY = y;
                    int endX = drawLine(screen, indent, headerY, describeSegments(), screen.isRowInView(headerY));
                    buttonX = endX + ViewerStyle.TOGGLE_GAP;
                    if (screen.isRowFullyVisible(headerY)) {
                        if (!children.isEmpty()) {
                            screen.addToggleButton(buttonX, toggleButtonY(headerY), ViewerStyle.TOGGLE_WIDTH,
                                collapsed ? "+" : "-",
                                () -> toggle(screen));
                        }
                        screen.addTooltipRow(indent, headerY, endX - indent, tooltipLines());
                    }
                    y += ViewerStyle.ROW_HEIGHT;
                }
                if (!collapsed) {
                    int childrenStartY = y;
                    for (ViewerNode child : children) {
                        y = child.render(screen, x, y, width);
                    }
                    if (headerY >= 0 && !children.isEmpty()) {
                        screen.addGuideLine(buttonX + ViewerStyle.TOGGLE_WIDTH / 2,
                            headerY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                    } else if (depth == 0 && !children.isEmpty()) {
                        // 根节点：贯穿整个子树的主干参考线
                        screen.addGuideLine(indent + ViewerStyle.GUIDE_ROOT_X,
                            childrenStartY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                    }
                }
                return y;
            }
            case LIST, SET, OPTIONAL, MAP -> {
                int rowY = y;
                int endX = drawLine(screen, indent, rowY, describeSegments(), screen.isRowInView(rowY));
                int buttonX = endX + ViewerStyle.TOGGLE_GAP;
                if (screen.isRowFullyVisible(rowY)) {
                    if (!children.isEmpty()) {
                        screen.addToggleButton(buttonX, toggleButtonY(rowY), ViewerStyle.TOGGLE_WIDTH,
                            collapsed ? "+" : "-",
                            () -> toggle(screen));
                    }
                    screen.addTooltipRow(indent, rowY, endX - indent, tooltipLines());
                }
                y += ViewerStyle.ROW_HEIGHT;
                if (!collapsed) {
                    for (ViewerNode child : children) {
                        y = child.render(screen, x, y, width);
                    }
                    if (!children.isEmpty()) {
                        screen.addGuideLine(buttonX + ViewerStyle.TOGGLE_WIDTH / 2,
                            rowY + ViewerStyle.ROW_HEIGHT / 2, y - ViewerStyle.ROW_HEIGHT / 2);
                    }
                }
                return y;
            }
            default -> {
                int endX = drawLine(screen, indent, y, describeSegments(), screen.isRowInView(y));
                if (screen.isRowFullyVisible(y)) {
                    screen.addTooltipRow(indent, y, endX - indent, tooltipLines());
                }
                y += ViewerStyle.ROW_HEIGHT;
                return y;
            }
        }
    }

    /** 切换折叠状态并刷新字段树。 */
    private void toggle(CodecViewerScreen screen) {
        collapsed = !collapsed;
        screen.refreshTree();
    }

    /** 折叠按钮的 y：在行内垂直居中，与字段名文本对齐。 */
    private static int toggleButtonY(int rowY) {
        return rowY + Math.max(0, (ViewerStyle.ROW_HEIGHT - ViewerStyle.TOGGLE_BUTTON_HEIGHT) / 2);
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

    /**
     * 彩色多行悬浮提示：逐行给出类型/必填、注册表、约束、默认值、枚举、
     * 容器标量元素/键/值、UNION 类型列表。
     */
    private List<Component> tooltipLines() {
        List<Component> lines = new ArrayList<>();

        // 首行：字段名（白） + [必填]/[可选]（红/灰） + 类型（灰）
        MutableComponent head;
        if (label.isEmpty()) {
            head = Component.literal(type().name()).withStyle(st -> st.withColor(ViewerStyle.COLOR_TEXT));
        } else {
            head = Component.literal(label).withStyle(st -> st.withColor(ViewerStyle.COLOR_TEXT));
            if (fieldMeta != null && fieldMeta.has(SchemaKeys.REQUIRED)) {
                boolean req = fieldMeta.get(SchemaKeys.REQUIRED).getAsBoolean();
                head.append(Component.translatable(req ? "ide.tooltip.required" : "ide.tooltip.optional")
                    .withStyle(st -> st.withColor(req ? ViewerStyle.COLOR_REQUIRED : ViewerStyle.COLOR_OPTIONAL)));
            }
            head.append(Component.literal(": " + type().name()).withStyle(st -> st.withColor(ViewerStyle.COLOR_TYPE)));
        }
        if (schema.has(SchemaKeys.JAVA_TYPE)) {
            head.append(Component.literal(" (" + schema.get(SchemaKeys.JAVA_TYPE).getAsString() + ")")
                .withStyle(st -> st.withColor(ViewerStyle.COLOR_TYPE)));
        }
        lines.add(head);

        if (schema.has(SchemaKeys.REGISTRY)) {
            JsonObject reg = schema.getAsJsonObject(SchemaKeys.REGISTRY);
            String t = I18n.get("ide.tooltip.registry", reg.get(SchemaKeys.REGISTRY).getAsString());
            if (reg.has(SchemaKeys.ALLOW_TAG) && reg.get(SchemaKeys.ALLOW_TAG).getAsBoolean()) {
                t += I18n.get("ide.tooltip.tag");
            }
            lines.add(line(t, ViewerStyle.COLOR_REGISTRY));
        }

        String constraints = formatConstraints(schema);
        if (!constraints.isEmpty()) {
            lines.add(line(I18n.get("ide.tooltip.constraints", constraints), ViewerStyle.COLOR_CONSTRAINT));
        }

        String def = defaultOf(fieldMeta);
        if (def.isEmpty()) {
            def = defaultOf(schema);
        }
        if (!def.isEmpty()) {
            lines.add(line(I18n.get("ide.tooltip.default", def), ViewerStyle.COLOR_DEFAULT));
        }

        if (schema.has(SchemaKeys.ENUM_VALUES)) {
            JsonArray evs = schema.getAsJsonArray(SchemaKeys.ENUM_VALUES);
            if (evs.size() > 0) {
                StringBuilder shown = new StringBuilder();
                int limit = Math.min(evs.size(), TOOLTIP_ENUM_SHOWN);
                for (int i = 0; i < limit; i++) {
                    if (i > 0) shown.append(", ");
                    shown.append(evs.get(i).getAsJsonObject().get(SchemaKeys.SERIALIZED_NAME).getAsString());
                }
                if (evs.size() > limit) shown.append(I18n.get("ide.tooltip.enum_more", evs.size()));
                lines.add(line(I18n.get("ide.tooltip.enum", shown.toString()), ViewerStyle.COLOR_ENUM));
            }
        }

        switch (type()) {
            case LIST, SET, OPTIONAL ->
                appendChildTooltip(lines, SchemaKeys.ELEMENT, I18n.get("ide.tooltip.element"));
            case MAP -> {
                appendChildTooltip(lines, SchemaKeys.KEY, I18n.get("ide.tooltip.key"));
                appendChildTooltip(lines, SchemaKeys.VALUE, I18n.get("ide.tooltip.value"));
            }
            case UNION, EITHER -> {
                if (variantKey != null) {
                    lines.add(line(I18n.get("ide.tooltip.variant_key", variantKey), ViewerStyle.COLOR_TYPE));
                }
                if (!unionNames.isEmpty()) {
                    lines.add(line(I18n.get("ide.tooltip.types", unionNames.size(), String.join(", ", unionNames)),
                        ViewerStyle.COLOR_ENUM));
                }
            }
            default -> {
            }
        }
        return lines;
    }

    /** 追加容器标量子结构的提示行（结构型已内联成子行，跳过）。 */
    private void appendChildTooltip(List<Component> lines, String key, String role) {
        if (!schema.has(key)) return;
        JsonObject child = schema.getAsJsonObject(key);
        if (isStructural(typeOf(child))) return;
        lines.add(line(I18n.get("ide.tooltip.role", role, typeOf(child).name()), ViewerStyle.COLOR_TYPE));
        StringBuilder sb = new StringBuilder();
        if (child.has(SchemaKeys.JAVA_TYPE)) {
            sb.append(child.get(SchemaKeys.JAVA_TYPE).getAsString());
        }
        String cc = formatConstraints(child);
        if (!cc.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(I18n.get("ide.tooltip.constraints", cc));
        }
        String cd = defaultOf(child);
        if (!cd.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(I18n.get("ide.tooltip.default", cd));
        }
        if (sb.length() > 0) {
            lines.add(line(sb.toString(), ViewerStyle.COLOR_TYPE));
        }
    }

    private static Component line(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(color));
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
        return SchemaTooltip.formatConstraints(schema);
    }

    private static String formatConstraint(String s) {
        return SchemaTooltip.formatConstraint(s);
    }

    /** 取 schema 的默认值文本。 */
    private static String defaultOf(JsonObject s) {
        return SchemaTooltip.defaultText(s);
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

    /** 一行中的一个彩色片段。 */
    private record Seg(String text, int color) {
    }
}
