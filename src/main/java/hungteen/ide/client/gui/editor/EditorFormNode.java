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

    /** 表单行高。 */
    static final int ROW_HEIGHT = 13;
    private static final int LABEL_WIDTH = 60;
    private static final int CONTROL_WIDTH = 64;
    private static final int CONTROL_MIN_WIDTH = 40;
    /** 结构型子结构缩进。 */
    private static final int INDENT = 6;
    /** 可选枚举折叠按钮里的"不使用"项。 */
    private static final String EMPTY_ENUM_LABEL = "不使用";

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

    // 校验
    private boolean invalid = false;
    private String errorMessage = "";

    public EditorFormNode(JsonObject schema, String label, JsonElement value) {
        this.schema = schema;
        this.label = label == null ? "" : label;
        this.value = value == null ? defaultFor(schema) : value;
        if (this.value == null) {
            this.value = JsonNull.INSTANCE;
        }
        initChildren();
        validateValue();
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
            case "OPTIONAL" -> {
                if (collapsedOptionalEnum()) {
                    yield ROW_HEIGHT;
                }
                if (optionalInner == null) {
                    yield ROW_HEIGHT;
                }
                // 标量内联单行（= 内层 ROW_HEIGHT）；结构型 = 标题行 + 内层
                yield isStructural(typeOf(elementSchema())) ? ROW_HEIGHT + optionalInner.height() : optionalInner.height();
            }
            case "UNION", "EITHER" -> ROW_HEIGHT + (unionVariants.isEmpty() ? 0 : unionVariants.get(unionIndex).height() + ROW_HEIGHT);
            default -> ROW_HEIGHT;
        };
    }

    /**
     * 校验当前值是否满足 schema 约束（数字范围 / 字符串长度 / 容器个数）。
     * 不满足时置 {@link #invalid} 与 {@link #errorMessage}。
     */
    private void validateValue() {
        invalid = false;
        errorMessage = "";
        if (!schema.has("constraints")) {
            return;
        }
        SchemaTooltip.Kind kind;
        String raw = "";
        int count = 0;
        switch (type()) {
            case "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE" -> {
                kind = SchemaTooltip.Kind.NUMBER;
                raw = value != null && value.isJsonPrimitive() ? value.getAsString() : "";
            }
            case "STRING" -> {
                kind = SchemaTooltip.Kind.STRING;
                raw = value != null && value.isJsonPrimitive() ? value.getAsString() : "";
            }
            case "LIST", "SET" -> {
                kind = SchemaTooltip.Kind.SIZE;
                count = listItems.size();
            }
            default -> {
                return;
            }
        }
        for (JsonElement c : schema.getAsJsonArray("constraints")) {
            String err = SchemaTooltip.checkConstraint(c.getAsString(), kind, raw, count);
            if (err != null) {
                invalid = true;
                errorMessage = "值不满足约束 " + err;
                return;
            }
        }
    }

    /** 悬浮提示：描述该字段的类型/约束/默认值/注册表/枚举/变体及校验状态。 */
    private List<net.minecraft.network.chat.Component> tooltipLines() {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        net.minecraft.network.chat.MutableComponent head =
            net.minecraft.network.chat.Component.literal(label.isEmpty() ? type() : label)
                .withStyle(st -> st.withColor(ViewerStyle.COLOR_TEXT));
        head.append(net.minecraft.network.chat.Component.literal(": " + type())
            .withStyle(st -> st.withColor(ViewerStyle.COLOR_TYPE)));
        if (schema.has("javaType")) {
            head.append(net.minecraft.network.chat.Component.literal(" (" + schema.get("javaType").getAsString() + ")")
                .withStyle(st -> st.withColor(ViewerStyle.COLOR_TYPE)));
        }
        lines.add(head);

        if (schema.has("registry")) {
            JsonObject reg = schema.getAsJsonObject("registry");
            String t = "注册表: " + reg.get("registry").getAsString();
            if (reg.has("allowTag") && reg.get("allowTag").getAsBoolean()) {
                t += " (+tag)";
            }
            lines.add(line(t, ViewerStyle.COLOR_REGISTRY));
        }

        String cc = SchemaTooltip.formatConstraints(schema);
        if (!cc.isEmpty()) {
            lines.add(line("约束: " + cc, ViewerStyle.COLOR_CONSTRAINT));
        }

        String def = SchemaTooltip.defaultText(schema);
        if (!def.isEmpty()) {
            lines.add(line("默认: " + def, ViewerStyle.COLOR_DEFAULT));
        }

        if (schema.has("enumValues")) {
            JsonArray evs = schema.getAsJsonArray("enumValues");
            if (evs.size() > 0) {
                StringBuilder sb = new StringBuilder("取值: ");
                int shown = Math.min(evs.size(), 20);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(evs.get(i).getAsJsonObject().get("serializedName").getAsString());
                }
                if (evs.size() > shown) sb.append("… (共 ").append(evs.size()).append(" 个)");
                lines.add(line(sb.toString(), ViewerStyle.COLOR_ENUM));
            }
        }

        switch (type()) {
            case "LIST", "SET", "OPTIONAL" -> appendElementTooltip(lines);
            case "MAP" -> {
                appendChildType(lines, "键", schema.getAsJsonObject("key"));
                appendChildType(lines, "值", schema.getAsJsonObject("value"));
            }
            case "UNION", "EITHER" -> {
                if (schema.has("variantKey")) {
                    lines.add(line("分派键: " + schema.get("variantKey").getAsString(), ViewerStyle.COLOR_TYPE));
                }
                if (!unionNames.isEmpty()) {
                    lines.add(line("类型(" + unionNames.size() + "): " + String.join(", ", unionNames),
                        ViewerStyle.COLOR_ENUM));
                }
            }
            default -> {
            }
        }

        if (invalid) {
            lines.add(line(errorMessage, ViewerStyle.COLOR_ERROR));
        }
        return lines;
    }

    private void appendElementTooltip(List<net.minecraft.network.chat.Component> lines) {
        if (!schema.has("element")) {
            return;
        }
        JsonObject el = schema.getAsJsonObject("element");
        String t = "元素: " + typeOf(el);
        String cc = SchemaTooltip.formatConstraints(el);
        if (!cc.isEmpty()) {
            t += "  (" + cc + ")";
        }
        lines.add(line(t, ViewerStyle.COLOR_TYPE));
    }

    private void appendChildType(List<net.minecraft.network.chat.Component> lines, String role, JsonObject child) {
        if (child == null) {
            return;
        }
        String t = role + ": " + typeOf(child);
        String cc = SchemaTooltip.formatConstraints(child);
        if (!cc.isEmpty()) {
            t += "  (" + cc + ")";
        }
        lines.add(line(t, ViewerStyle.COLOR_TYPE));
    }

    private static net.minecraft.network.chat.Component line(String text, int color) {
        return net.minecraft.network.chat.Component.literal(text)
            .withStyle(style -> style.withColor(color));
    }

    private static String typeOf(JsonObject s) {
        return s != null && s.has("type") ? s.get("type").getAsString() : "UNKNOWN";
    }

    /** 字段名标签：超宽时省略号截断（完整名在悬浮提示里），避免挡住输入框。 */
    private void fieldLabel(CodecEditorScreen screen, int x, int y, int color) {
        fieldLabelText(screen, x, y, label.isEmpty() ? type() : label, color);
    }

    /** 用给定文本绘制字段名；与同行控件文字同一垂直线。 */
    private void fieldLabelText(CodecEditorScreen screen, int x, int y, String text, int color) {
        // 控件高 12，其文字居中于 y + (12-9)/2 = y+1；标签也画在同行 y+1，实现基线对齐
        screen.drawLabelEllipsis(x, y + 1, text, LABEL_WIDTH, color);
    }

    /** 该 schema 类型是否为结构型（需要多行展示）。 */
    private static boolean isStructural(String t) {
        return switch (t) {
            case "RECORD", "LIST", "SET", "MAP", "OPTIONAL", "UNION", "EITHER" -> true;
            default -> false;
        };
    }

    /** 可选字段包裹的是枚举类型：折叠成单个循环按钮，首项为"不使用"。 */
    private boolean collapsedOptionalEnum() {
        JsonObject inner = elementSchema();
        return inner != null && schema.has("element") && "ENUM".equals(typeOf(inner));
    }

    /** 渲染控件（仅控件），返回下一个可用的 y 坐标。 */
    public int render(CodecEditorScreen screen, int x, int y, int width) {
        String type = type();
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));

        switch (type) {
            case "RECORD" -> {
                for (EditorFormNode child : recordChildren) {
                    int indent = x + INDENT;
                    y = child.render(screen, indent, y, width - INDENT);
                }
            }
            case "LIST", "SET" -> {
                validateValue();
                if (screen.formRowVisible(y)) {
                    screen.addButton(cx, y, 20, "+", b -> {
                        listItems.add(new EditorFormNode(elementSchema(), "", JsonNull.INSTANCE));
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
                    y = item.render(screen, x + INDENT, y, width - INDENT);
                }
            }
            case "MAP" -> {
                validateValue();
                if (screen.formRowVisible(y)) {
                    screen.addButton(cx, y, 20, "+", b -> {
                        mapKeys.add(new EditorFormNode(keySchema(), "", new JsonPrimitive("")));
                        mapValues.add(new EditorFormNode(valueSchema(), "", JsonNull.INSTANCE));
                        screen.rebuildForm();
                    });
                    screen.addButton(cx + 22, y, 20, "-", b -> {
                        if (!mapKeys.isEmpty()) {
                            mapKeys.remove(mapKeys.size() - 1);
                            mapValues.remove(mapValues.size() - 1);
                            screen.rebuildForm();
                        }
                    });
                }
                y += ROW_HEIGHT;
                for (int i = 0; i < Math.max(mapKeys.size(), mapValues.size()); i++) {
                    EditorFormNode key = mapKeys.get(i);
                    EditorFormNode val = mapValues.get(i);
                    if (key != null || val != null) {
                        if (key != null) {
                            y = key.render(screen, x + INDENT, y, width - INDENT);
                        }
                        if (val != null) {
                            y = val.render(screen, x + INDENT, y, width - INDENT);
                        }
                    }
                }
            }
            case "OPTIONAL" -> {
                validateValue();
                if (collapsedOptionalEnum()) {
                    if (screen.formRowVisible(y)) {
                        List<String> opts = new ArrayList<>();
                        opts.add(EMPTY_ENUM_LABEL);
                        JsonObject inner = elementSchema();
                        if (inner.has("enumValues")) {
                            for (JsonElement e : inner.getAsJsonArray("enumValues")) {
                                opts.add(e.getAsJsonObject().get("serializedName").getAsString());
                            }
                        }
                        int innerIdx = optionalPresent && optionalInner != null ? optionalInner.enumIndex() : -1;
                        screen.addCycleButton(cx, y, cw, opts, innerIdx < 0 ? 0 : innerIdx + 1, i -> {
                            if (i == 0) {
                                optionalPresent = false;
                                optionalInner = null;
                            } else if (i - 1 < opts.size() - 1) {
                                optionalPresent = true;
                                optionalInner = new EditorFormNode(inner, "", new JsonPrimitive(opts.get(i)));
                            }
                            screen.rebuildForm();
                        });
                    }
                    y += ROW_HEIGHT;
                } else if (optionalInner != null) {
                    // 不再有"启用/禁用"行：标量直接内联到本行，结构型给一行标题后展开
                    if (isStructural(typeOf(elementSchema()))) {
                        y = optionalInner.render(screen, x + INDENT, y, width - INDENT);
                    } else {
                        y = optionalInner.renderWithLabel(screen, x, y, width, label);
                    }
                }
            }
            case "UNION", "EITHER" -> {
                if (!unionVariants.isEmpty()) {
                    if (screen.formRowVisible(y)) {
                        String currentName = unionIndex >= 0 && unionIndex < unionNames.size()
                            ? unionNames.get(unionIndex) : "";
                        screen.addUnionTypeButton(this, cx, y, cw, currentName);
                        if (screen.isUnionDropdownOpen(this)) {
                            screen.addUnionDropdownButtons(this, cx,
                                y + ViewerStyle.ROW_HEIGHT + ViewerStyle.FIELD_GAP, cw);
                        }
                    }
                    y += ROW_HEIGHT;
                    // 类型下拉展开时隐藏下方分支字段，避免透过面板显示
                    if (!screen.isUnionDropdownOpen(this)) {
                        EditorFormNode selected = unionVariants.get(unionIndex);
                        if (selected != null) {
                            y = selected.render(screen, x + INDENT, y, width - INDENT);
                        }
                    }
                }
            }
            case "ENUM" -> {
                if (screen.formRowVisible(y)) {
                    screen.addCycleButton(cx, y, cw, enumValues, enumIndex, i -> enumIndex = i);
                }
                y += ROW_HEIGHT;
            }
            case "BOOLEAN" -> {
                if (screen.formRowVisible(y)) {
                    boolean current = value.isJsonPrimitive() && value.getAsBoolean();
                    screen.addButton(cx, y, cw, String.valueOf(current), b -> {
                        value = new JsonPrimitive(!(value.isJsonPrimitive() && value.getAsBoolean()));
                        screen.rebuildForm();
                    });
                }
                y += ROW_HEIGHT;
            }
            default -> {
                validateValue();
                if (screen.formRowVisible(y)) {
                    editBox = screen.addEditBox(cx, y, cw, textOf(value), s -> {
                        value = parseInput(s, type());
                        validateValue();
                        // 输入立即校验：实时更新文字颜色
                        if (editBox != null) {
                            editBox.setTextColor(invalid ? ViewerStyle.COLOR_ERROR_VALUE : 0xFFFFFFFF);
                        }
                    });
                    if (invalid) {
                        editBox.setTextColor(ViewerStyle.COLOR_ERROR_VALUE);
                    }
                }
                y += ROW_HEIGHT;
            }
        }
        return y;
    }

    /** 用外部行标签渲染单行标量控件（可选字段去掉"启用/禁用"后内联用）。 */
    public int renderWithLabel(CodecEditorScreen screen, int x, int y, int width, String rowLabel) {
        String t = type();
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        if (screen.formRowVisible(y)) {
            switch (t) {
                case "ENUM" -> {
                    screen.addCycleButton(cx, y, cw, enumValues, enumIndex, i -> enumIndex = i);
                }
                case "BOOLEAN" -> {
                    boolean cur = value.isJsonPrimitive() && value.getAsBoolean();
                    screen.addButton(cx, y, cw, String.valueOf(cur), b -> {
                        value = new JsonPrimitive(!(value.isJsonPrimitive() && value.getAsBoolean()));
                        screen.rebuildForm();
                    });
                }
                default -> {
                    validateValue();
                    editBox = screen.addEditBox(cx, y, cw, textOf(value), s -> {
                        value = parseInput(s, t);
                        validateValue();
                        if (editBox != null) {
                            editBox.setTextColor(invalid ? ViewerStyle.COLOR_ERROR_VALUE : 0xFFFFFFFF);
                        }
                    });
                    if (invalid) {
                        editBox.setTextColor(ViewerStyle.COLOR_ERROR_VALUE);
                    }
                }
            }
        }
        return y + ROW_HEIGHT;
    }

    /** {@link #renderWithLabel} 的标签/提示版本（每帧重建）。 */
    public int renderLabelsWithLabel(CodecEditorScreen screen, int x, int y, int width, String rowLabel) {
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        validateValue();
        fieldLabelText(screen, x, y, rowLabel, invalid ? ViewerStyle.COLOR_ERROR : ViewerStyle.COLOR_TEXT);
        screen.addTooltipRow(x, y, cx + cw - x, ROW_HEIGHT, tooltipLines());
        return y + ROW_HEIGHT;
    }

    /**
     * 只重建标签与悬浮提示（不建控件），每帧调用。
     *
     * <p>布局推进与 {@link #render} 完全一致；因为校验状态在节点上实时更新，
     * 标签颜色/报错能即时反映，无需整屏重建（重建控件会丢输入焦点）。</p>
     */
    public int renderLabels(CodecEditorScreen screen, int x, int y, int width) {
        String type = type();
        int cx = x + LABEL_WIDTH;
        int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
        int endX = cx + cw;

        switch (type) {
            case "RECORD" -> {
                for (EditorFormNode child : recordChildren) {
                    int indent = x + INDENT;
                    y = child.renderLabels(screen, indent, y, width - INDENT);
                }
            }
            case "LIST", "SET", "MAP" -> {
                validateValue();
                fieldLabel(screen, x, y, invalid ? ViewerStyle.COLOR_ERROR : ViewerStyle.COLOR_TEXT);
                screen.addTooltipRow(x, y, LABEL_WIDTH, ROW_HEIGHT, tooltipLines());
                y += ROW_HEIGHT;
                if (type.equals("LIST") || type.equals("SET")) {
                    for (EditorFormNode item : listItems) {
                        y = item.renderLabels(screen, x + INDENT, y, width - INDENT);
                    }
                } else {
                    for (int i = 0; i < mapKeys.size(); i++) {
                        y = mapKeys.get(i).renderLabels(screen, x + INDENT, y, width - INDENT);
                        y = mapValues.get(i).renderLabels(screen, x + INDENT, y, width - INDENT);
                    }
                }
            }
            case "OPTIONAL" -> {
                if (collapsedOptionalEnum()) {
                    fieldLabel(screen, x, y, ViewerStyle.COLOR_TEXT);
                    screen.addTooltipRow(x, y, endX - x, ROW_HEIGHT, tooltipLines());
                    y += ROW_HEIGHT;
                } else if (optionalInner != null) {
                    if (isStructural(typeOf(elementSchema()))) {
                        fieldLabel(screen, x, y, ViewerStyle.COLOR_TEXT);
                        screen.addTooltipRow(x, y, endX - x, ROW_HEIGHT, tooltipLines());
                        y += ROW_HEIGHT;
                        y = optionalInner.renderLabels(screen, x + INDENT, y, width - INDENT);
                    } else {
                        y = optionalInner.renderLabelsWithLabel(screen, x, y, width, label);
                    }
                }
            }
            case "UNION", "EITHER" -> {
                if (!unionVariants.isEmpty()) {
                    fieldLabel(screen, x, y, ViewerStyle.COLOR_TEXT);
                    screen.addTooltipRow(x, y, endX - x, ROW_HEIGHT, tooltipLines());
                    y += ROW_HEIGHT;
                    if (!screen.isUnionDropdownOpen(this)) {
                        EditorFormNode selected = unionVariants.get(unionIndex);
                        if (selected != null) {
                            y = selected.renderLabels(screen, x + INDENT, y, width - INDENT);
                        }
                    }
                }
            }
            case "ENUM", "BOOLEAN" -> {
                fieldLabel(screen, x, y, ViewerStyle.COLOR_TEXT);
                screen.addTooltipRow(x, y, endX - x, ROW_HEIGHT, tooltipLines());
                y += ROW_HEIGHT;
            }
            default -> {
                validateValue();
                fieldLabel(screen, x, y, invalid ? ViewerStyle.COLOR_ERROR : ViewerStyle.COLOR_TEXT);
                screen.addTooltipRow(x, y, endX - x, ROW_HEIGHT, tooltipLines());
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

    /** UNION 变体名列表（供类型下拉用）。 */
    public List<String> unionNames() {
        return unionNames;
    }

    /** 当前枚举下标（供可选枚举折叠按钮计算)。 */
    public int enumIndex() {
        return enumIndex;
    }

    /** 切换到指定 UNION 变体。 */
    public void selectVariant(int idx) {
        if (idx < 0 || idx >= unionVariants.size()) {
            return;
        }
        unionIndex = idx;
        unionVariants.get(idx).load(JsonNull.INSTANCE);
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
                if (optionalInner == null) {
                    return JsonNull.INSTANCE;
                }
                // 可选枚举才可能"不使用"（缺省省略键）；其余可选字段总是输出内层值。
                if (collapsedOptionalEnum() && !optionalPresent) {
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
        return type().equals("OPTIONAL") && collapsedOptionalEnum() && !optionalPresent;
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
        if (e == null || e.isJsonNull()) {
            return "";
        }
        // 字符串不加引号：JsonPrimitive.toString() 会带引号，回填输入框后再解析会造成引号翻倍
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return e.getAsString();
        }
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
