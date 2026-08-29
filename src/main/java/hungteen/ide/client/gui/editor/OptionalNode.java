package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import hungteen.ide.common.codec.parser.SchemaKeys;
import hungteen.ide.common.codec.parser.SchemaType;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选节点：内层直接内联，不再有"启用/禁用"行。
 * <ul>
 *   <li>包裹枚举：折叠成单个循环按钮，首项"不使用"即缺省；</li>
 *   <li>内层标量：与字段名同一行显示控件；</li>
 *   <li>内层结构型：字段名一行标题 + 内层展开。</li>
 * </ul>
 */
final class OptionalNode extends EditorFormNode {

    private EditorFormNode optionalInner;
    private boolean optionalPresent;

    OptionalNode(JsonObject schema, String label, JsonElement value) {
        super(schema, label, value);
    }

    @Override
    protected void init() {
        JsonObject inner = elementSchema();
        optionalPresent = value != null && !value.isJsonNull();
        if (inner != null) {
            optionalInner = EditorFormNode.create(inner, "", optionalPresent ? value : JsonNull.INSTANCE);
        }
    }

    /** 内层是枚举：折叠成单按钮。 */
    private boolean collapsedOptionalEnum() {
        JsonObject inner = elementSchema();
        return inner != null && typeOf(inner) == SchemaType.ENUM;
    }

    @Override
    public int height() {
        if (collapsedOptionalEnum() || optionalInner == null) {
            return ROW_HEIGHT;
        }
        JsonObject inner = elementSchema();
        if (inner != null && isStructural(typeOf(inner))) {
            return ROW_HEIGHT + optionalInner.height();
        }
        return optionalInner.height();
    }

    @Override
    public int buildControls(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        if (collapsedOptionalEnum()) {
            if (screen.formRowVisible(y)) {
                List<String> opts = new ArrayList<>();
                opts.add(I18n.get("ide.editor.none"));
                JsonObject inner = elementSchema();
                if (inner.has(SchemaKeys.ENUM_VALUES)) {
                    for (JsonElement e : inner.getAsJsonArray(SchemaKeys.ENUM_VALUES)) {
                        opts.add(e.getAsJsonObject().get(SchemaKeys.SERIALIZED_NAME).getAsString());
                    }
                }
                int cx = x + LABEL_WIDTH;
                int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
                int innerIdx = optionalPresent && optionalInner != null ? optionalInner.enumIndex() : -1;
                screen.addCycleButton(cx, y, cw, opts, innerIdx < 0 ? 0 : innerIdx + 1, i -> {
                    if (i == 0) {
                        optionalPresent = false;
                        optionalInner = null;
                    } else if (i - 1 < opts.size() - 1) {
                        optionalPresent = true;
                        optionalInner = EditorFormNode.create(inner, "", new JsonPrimitive(opts.get(i)));
                    }
                    screen.rebuildForm();
                });
            }
            y += ROW_HEIGHT;
        } else if (optionalInner != null) {
            JsonObject inner = elementSchema();
            if (inner != null && isStructural(typeOf(inner))) {
                y = optionalInner.buildControls(screen, x + INDENT, y, width - INDENT);
            } else {
                y = optionalInner.buildInlineControl(screen, x, y, width);
            }
        }
        return y;
    }

    @Override
    public int collectLabels(CodecEditorScreen screen, int x, int y, int width) {
        validateValue();
        if (collapsedOptionalEnum()) {
            int cx = x + LABEL_WIDTH;
            int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
            fieldLabel(screen, x, y);
            screen.addTooltipRow(x, y, cx + cw - x, ROW_HEIGHT, tooltipLines());
            return y + ROW_HEIGHT;
        }
        if (optionalInner != null) {
            JsonObject inner = elementSchema();
            if (inner != null && isStructural(typeOf(inner))) {
                int cx = x + LABEL_WIDTH;
                int cw = Math.max(CONTROL_MIN_WIDTH, Math.min(width - LABEL_WIDTH, CONTROL_WIDTH));
                fieldLabel(screen, x, y);
                screen.addTooltipRow(x, y, cx + cw - x, ROW_HEIGHT, tooltipLines());
                y += ROW_HEIGHT;
                return optionalInner.collectLabels(screen, x + INDENT, y, width - INDENT);
            }
            return optionalInner.collectInlineLabels(screen, x, y, width, label);
        }
        return y;
    }

    @Override
    public JsonElement collect() {
        if (optionalInner == null) {
            return JsonNull.INSTANCE;
        }
        // 可选枚举才可能"不使用"（缺省省略键）；其余可选字段总是输出内层值。
        if (collapsedOptionalEnum() && !optionalPresent) {
            return JsonNull.INSTANCE;
        }
        return optionalInner.collect();
    }

    @Override
    public void load(JsonElement json) {
        this.value = json == null ? JsonNull.INSTANCE : json;
        optionalPresent = this.value != null && !this.value.isJsonNull();
        JsonObject inner = elementSchema();
        if (inner != null) {
            optionalInner = EditorFormNode.create(inner, "", optionalPresent ? this.value : JsonNull.INSTANCE);
        }
    }

    @Override
    public boolean isOptionalAndAbsent() {
        return type() == SchemaType.OPTIONAL && collapsedOptionalEnum() && !optionalPresent;
    }

    private static boolean isStructural(SchemaType t) {
        return switch (t) {
            case RECORD, LIST, SET, MAP, OPTIONAL, UNION, EITHER -> true;
            default -> false;
        };
    }
}