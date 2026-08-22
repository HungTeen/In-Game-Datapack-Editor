package hungteen.ide.client.gui.editor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 节点（UNION 字段）类型下拉，查看器与编辑器共用。
 *
 * <p>点击类型按钮展开变体列表；列表按钮<b>延迟到最后加入</b>渲染列表，
 * 保证盖在字段行/其他控件之上（z-order 修复）。面板背景与滚动条在 render 中绘制。</p>
 *
 * @param <T> 节点类型（ViewerNode / EditorFormNode）
 */
public final class NodeDropdown<T> {

    private static final int PANEL_MARGIN = ViewerStyle.DROPDOWN_PANEL_MARGIN;

    /** 宿主 Screen（仅用于取界面高度；protected 的 addRenderableWidget/removeWidget 以回调形式注入）。 */
    private final Screen host;
    private final Consumer<AbstractWidget> addWidget;
    private final Consumer<AbstractWidget> removeWidget;
    /** 选择/滚动/点击外部后触发的重建。 */
    private final Runnable refresh;

    private T openNode;
    private int scroll = 0;
    private Rect trigger;
    private Rect panel;
    private int itemCount = 0;

    /** 该下拉创建过的全部按钮（触发按钮 + 列表按钮），宿主重建时统一移除。 */
    private final List<Button> allButtons = new ArrayList<>();
    /** 延迟到重建末尾统一加入渲染列表的列表按钮。 */
    private final List<Button> deferredButtons = new ArrayList<>();
    private final List<Rect> panels = new ArrayList<>();

    public NodeDropdown(Screen host, Consumer<AbstractWidget> addWidget, Consumer<AbstractWidget> removeWidget,
                        Runnable refresh) {
        this.host = host;
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
        this.refresh = refresh;
    }

    public boolean isOpen(T node) {
        return openNode == node;
    }

    public boolean hasOpen() {
        return openNode != null;
    }

    /** 展开/收起某节点的类型下拉。 */
    public void toggle(T node) {
        openNode = openNode == node ? null : node;
        scroll = 0;
        trigger = null;
        panel = null;
    }

    public void close() {
        openNode = null;
        scroll = 0;
        trigger = null;
        panel = null;
    }

    /** 移除该下拉创建过的全部按钮（宿主重建时调用）。 */
    public void removeAll() {
        for (Button b : allButtons) {
            removeWidget.accept(b);
        }
        allButtons.clear();
        deferredButtons.clear();
    }

    /**
     * 类型选择按钮（点击展开/收起）。
     * 当前展开节点为 {@code node} 时记录其区域，供点击外部关闭判定。
     */
    public void addTrigger(T node, int x, int y, int width, String label, Runnable onClick) {
        Button b = Button.builder(Component.literal(label), btn -> onClick.run())
            .bounds(x, y, width, ViewerStyle.ROW_HEIGHT).build();
        addWidget.accept(b);
        allButtons.add(b);
        if (node == openNode) {
            trigger = new Rect(x, y, width, ViewerStyle.ROW_HEIGHT);
        }
    }

    /** 为已展开节点的类型下拉添加列表按钮（滚动取可见切片，从 {@code y} 延伸到底部）。 */
    public void addItems(int x, int y, int width, List<String> names, IntConsumer onSelect) {
        int bottom = host.height - ViewerStyle.BOTTOM_PADDING;
        int rows = Math.max(0, (bottom - y) / (ViewerStyle.FIELD_ROW + ViewerStyle.FIELD_GAP));
        int maxScroll = Math.max(0, names.size() - rows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        itemCount = names.size();
        panels.add(new Rect(x - PANEL_MARGIN, y - PANEL_MARGIN,
            width + PANEL_MARGIN * 2, bottom - y + PANEL_MARGIN * 2));
        panel = new Rect(x, y, width, bottom - y);

        int rowY = y;
        int end = Math.min(names.size(), scroll + rows);
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            Button b = Button.builder(Component.literal(names.get(i)), btn -> {
                onSelect.accept(idx);
                close();
                refresh.run();
            }).bounds(x, rowY, width, ViewerStyle.FIELD_ROW).build();
            deferredButtons.add(b);
            allButtons.add(b);
            rowY += ViewerStyle.FIELD_ROW + ViewerStyle.FIELD_GAP;
        }
    }

    /** 把延迟收集的列表按钮加入渲染列表（保证盖在其他控件之上）。需在重建末尾调用。 */
    public void addDeferredToScreen() {
        for (Button b : deferredButtons) {
            addWidget.accept(b);
        }
        deferredButtons.clear();
    }

    /** 绘制下拉面板背景（读取后即清空，供每帧调用）。 */
    public void renderPanels(GuiGraphics graphics) {
        for (Rect r : panels) {
            DropdownUtil.drawPanel(graphics, r);
        }
        panels.clear();
    }

    /** 绘制滚动条（画在按钮之上）。 */
    public void renderScrollbar(GuiGraphics graphics) {
        if (panel == null) {
            return;
        }
        Rect track = new Rect(panel.x() + panel.width() - ViewerStyle.SCROLLBAR_WIDTH
            - ViewerStyle.SCROLLBAR_RIGHT_MARGIN, panel.y(), ViewerStyle.SCROLLBAR_WIDTH, panel.height());
        DropdownUtil.drawScrollbar(graphics, track, itemCount, maxRows(), scroll);
    }

    /** 滚轮滚动；返回是否消费。 */
    public boolean mouseScrolled(double delta) {
        if (panel == null) {
            return false;
        }
        scroll = DropdownUtil.clampScroll(scroll - (int)delta * ViewerStyle.DROPDOWN_SCROLL_STEP, itemCount, maxRows());
        refresh.run();
        return true;
    }

    /** 点击下拉区域外部时关闭；返回是否消费。 */
    public boolean handleClickOutside(double mouseX, double mouseY) {
        if (openNode != null && !contains(trigger, mouseX, mouseY) && !contains(panel, mouseX, mouseY)) {
            close();
            refresh.run();
            return true;
        }
        return false;
    }

    private int maxRows() {
        return panel == null ? 0 : Math.max(0, panel.height() / (ViewerStyle.FIELD_ROW + ViewerStyle.FIELD_GAP));
    }

    private static boolean contains(Rect r, double x, double y) {
        return r != null && x >= r.x() && x <= r.x() + r.width() && y >= r.y() && y <= r.y() + r.height();
    }
}