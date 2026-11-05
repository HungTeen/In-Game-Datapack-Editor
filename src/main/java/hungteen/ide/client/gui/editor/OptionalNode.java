package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import hungteen.ide.common.codec.parser.SchemaType;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 可选节点：同一行内用 [+]/[-] 控制字段是否存在，缺省时由 Codec 使用默认值。
 * <ul>
 *   <li>内层<b>标量</b>（数字/字符串/枚举/布尔/Holder…）：字段名 + 内层控件内联到<b>同一行</b>；</li>
 *   <li>内层<b>结构型</b>（RECORD/LIST/MAP/UNION…）：额外的 [&gt;]/[&lt;] 控制折叠，折叠不会删除值。</li>
 * </ul>
 */
final class OptionalNode extends EditorFormNode {

    private EditorFormNode optionalInner;
    private boolean expanded = false;
    private boolean present;

    OptionalNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        present = value != null && !value.isJsonNull();
        JsonObject inner = elementSchema();
        if (inner != null) {
            optionalInner = EditorFormNode.create(inner, "", present ? value : null);
        }
    }

    /** 内层是否结构型（多行、可折叠）。 */
    private boolean structuralInner() {
        JsonObject inner = elementSchema();
        return inner != null && isStructuralType(typeOf(inner));
    }

    @Override
    public int height() {
        if (optionalInner == null || !present) {
            return ROW_HEIGHT;
        }
        if (structuralInner()) {
            return ROW_HEIGHT + (expanded ? optionalInner.height() : 0);
        }
        return ROW_HEIGHT;
    }

    @Override
    public int buildControls(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        if (optionalInner == null) {
            return y + ROW_HEIGHT;
        }
        int toggleX = x + LABEL_WIDTH;
        if (screen.formRowVisible(y)) {
            screen.addButton(toggleX, y, 12, present ? "-" : "+", b -> {
                setPresent(!present);
                screen.rebuild();
            });
        }
        if (!present) {
            return y + ROW_HEIGHT;
        }
        if (structuralInner()) {
            if (screen.formRowVisible(y)) {
                screen.addButton(toggleX + 15, y, 12, expanded ? "<" : ">", b -> {
                    expanded = !expanded;
                    // 只重排布局（不重建树、不 reload），否则 rebuildForm 会新建节点导致 expanded 重置。
                    screen.rebuild();
                });
            }
            y += ROW_HEIGHT;
            if (expanded) {
                y = optionalInner.buildControls(screen, x + INDENT, y, width - INDENT);
            }
        } else {
            y = optionalInner.buildInlineControl(screen, x + 15, y, width - 15);
        }
        return y;
    }

    @Override
    public int collectLabels(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        if (optionalInner == null) {
            return y + ROW_HEIGHT;
        }
        if (!present) {
            fieldLabel(screen, x, y);
            screen.drawLabel(x + LABEL_WIDTH + 15, y + 1, I18n.get("ide.editor.none"));
            screen.addTooltipRow(x, y, LABEL_WIDTH + CONTROL_WIDTH, ROW_HEIGHT, tooltipLines());
            return y + ROW_HEIGHT;
        }
        if (structuralInner()) {
            int cx = x + LABEL_WIDTH;
            int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
            fieldLabel(screen, x, y);
            screen.addTooltipRow(x, y, cx + cw - x, ROW_HEIGHT, tooltipLines());
            y += ROW_HEIGHT;
            if (expanded) {
                y = optionalInner.collectLabels(screen, x + INDENT, y, width - INDENT);
            }
        } else {
            optionalInner.validateValue();
            fieldLabelText(screen, x, y, label, optionalInner.fieldLabelColor());
            screen.addTooltipRow(x, y, LABEL_WIDTH + CONTROL_WIDTH + 15, ROW_HEIGHT, optionalInner.tooltipLines());
            y += ROW_HEIGHT;
        }
        return y;
    }

    @Override
    public JsonElement collect() {
        return !present || optionalInner == null ? JsonNull.INSTANCE : optionalInner.collect();
    }

    @Override
    public void load(JsonElement json) {
        this.value = json == null ? JsonNull.INSTANCE : json;
        present = !this.value.isJsonNull();
        JsonObject inner = elementSchema();
        optionalInner = inner == null ? null : EditorFormNode.create(inner, "", present ? this.value : null);
    }

    @Override
    public boolean isOptionalAndAbsent() {
        return !present;
    }

    /** Presence is independent of expansion; disabling a field keeps its draft for re-enabling. */
    void setPresent(boolean present) {
        this.present = present;
        if (present && structuralInner()) {
            expanded = true;
        }
    }

    @Override
    protected List<EditorFormNode> validationChildren() {
        return present && optionalInner != null ? List.of(optionalInner) : List.of();
    }

    private static boolean isStructuralType(SchemaType type) {
        return switch (type) {
            case RECORD, LIST, SET, MAP, OPTIONAL, UNION, EITHER -> true;
            default -> false;
        };
    }
}
