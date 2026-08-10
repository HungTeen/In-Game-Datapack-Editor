package hungteen.ide.client.gui.editor;

/**
 * 查看器的布局尺寸、配色与行为参数集中处。
 *
 * <p>调整界面外观/间距/滚动步长/提示时长时只改这里即可，避免散落的魔法数字。</p>
 */
public final class ViewerStyle {

    private ViewerStyle() {
    }

    // ---- 布局 ----
    /** 顶部工具条高度，字段树从这之下开始。 */
    public static final int TOP_OFFSET = 30;
    /** 字段行高。 */
    public static final int ROW_HEIGHT = 14;
    /** 每级缩进宽度。 */
    public static final int INDENT = 12;
    /** 折叠按钮宽/高。 */
    public static final int TOGGLE_WIDTH = 12;
    public static final int TOGGLE_BUTTON_HEIGHT = 12;
    /** 折叠按钮与标签之间的间距。 */
    public static final int TOGGLE_GAP = 3;
    /** UNION 类型循环按钮尺寸。 */
    public static final int TYPE_BUTTON_WIDTH = 140;
    public static final int CYCLE_BUTTON_HEIGHT = 14;
    /** 标签与 UNION 按钮之间的最小间距 / 标签最小宽度。 */
    public static final int TYPE_BUTTON_GAP = 10;
    public static final int MIN_LABEL_WIDTH = 40;
    /** 滚动条宽度与距右边缘的间距。 */
    public static final int SCROLLBAR_WIDTH = 3;
    public static final int SCROLLBAR_RIGHT_MARGIN = 4;
    /** 下拉框行高与行间距。 */
    public static final int FIELD_ROW = 16;
    public static final int FIELD_GAP = 4;
    public static final int DROPDOWN_BOTTOM_PADDING = 10;
    /** 左右留白 / 底部留白 / 状态提示距底部高度。 */
    public static final int LEFT_PADDING = 8;
    public static final int RIGHT_PADDING = 20;
    public static final int BOTTOM_PADDING = 20;
    public static final int STATUS_BOTTOM = 16;
    /** 滚轮滚动步长（像素）。 */
    public static final int SCROLL_STEP = 12;
    /** 顶部下拉框按钮位置与尺寸。 */
    public static final int TYPE_DROPDOWN_Y = 5;
    public static final int TYPE_DROPDOWN_WIDTH = 180;
    public static final int TYPE_DROPDOWN_HEIGHT = 18;
    /** 下拉搜索框高度、与列表的间距、滚轮每格滚动行数。 */
    public static final int DROPDOWN_SEARCH_HEIGHT = 16;
    public static final int DROPDOWN_SEARCH_GAP = 4;
    public static final int DROPDOWN_SCROLL_STEP = 3;
    /** 下拉面板背景相对内容的外扩边距。 */
    public static final int DROPDOWN_PANEL_MARGIN = 2;

    // ---- 配色 ----
    /** 字段名文字。 */
    public static final int COLOR_TEXT = 0xFFFFFF;
    /** 层级参考线。 */
    public static final int COLOR_GUIDE = 0x505050;
    /** 状态提示。 */
    public static final int COLOR_STATUS = 0xFFFFAA;
    /** 滚动条轨道 / 滑块。 */
    public static final int COLOR_SCROLL_TRACK = 0x30000000;
    public static final int COLOR_SCROLL_THUMB = 0xFFA0A0A0;
    /** 下拉面板背景 / 边框。 */
    public static final int COLOR_DROPDOWN_BG = 0xC0101010;
    public static final int COLOR_DROPDOWN_BORDER = 0xFF6E6E6E;

    // ---- 行为 ----
    /** 状态提示自动消失时长（毫秒）。 */
    public static final long STATUS_TIMEOUT_MS = 3000L;
}
