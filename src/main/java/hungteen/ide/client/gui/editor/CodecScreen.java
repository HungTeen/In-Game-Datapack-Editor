package hungteen.ide.client.gui.editor;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Codec 界面抽象基类：查看器与编辑器共用。
 *
 * <p>统一承载：注册表列表、选中态、状态提示（带超时）、顶部类型补全输入框、
 * 行内补全输入框（枚举/UNION 字段）、鼠标/键盘路由、补全面板最上层渲染。
 * 子类只需实现：{@link #createTypeSelector()}、{@link #rebuild()}、{@link #applySchema(String)}、
 * {@link #renderFormRegion}（主体绘制，含 scissor）与 {@link #renderOverlays}（覆盖层）。</p>
 *
 * @author PangTeen
 * @create 2026/8/29
 **/
public abstract class CodecScreen extends Screen {

    protected final List<ResourceLocation> registryNames;
    protected String selected = "";
    protected String status = "";
    protected long statusUntil = Long.MAX_VALUE;

    /** 顶部类型补全输入框（init 时由 {@link #createTypeSelector()} 创建）。 */
    protected TypeSelector typeSelector;
    /** 行内枚举/UNION 的补全输入框（节点在建控件时注册）。 */
    protected final List<TypeSelector> selectors = new ArrayList<>();

    protected CodecScreen(Component title, List<ResourceLocation> registryNames) {
        super(title);
        this.registryNames = new ArrayList<>(registryNames);
    }

    /** 收到某注册表的 schema：统一转发给当前打开的 Codec 界面。 */
    public static void onSchema(String registryName, String schemaJson) {
        if (Minecraft.getInstance().screen instanceof CodecScreen screen && screen.selected.equals(registryName)) {
            screen.applySchema(schemaJson);
        }
    }

    public static void onSchemaError(String registryName, Component message) {
        if (Minecraft.getInstance().screen instanceof CodecScreen screen && screen.selected.equals(registryName)) {
            screen.setStatusPersistent(message.getString());
        }
    }

    /**
     * 创建顶部类型补全输入框。
     */
    protected abstract TypeSelector createTypeSelector();

    /**
     * 全量重建界面。
     */
    protected abstract void rebuild();

    /**
     * 应用收到的 schema。
     */
    protected abstract void applySchema(String schemaJson);

    /**
     * 主体区域绘制（负责 scissor 管理），在裁剪区内的内容逐一画出。
     */
    protected abstract void renderFormRegion(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /**
     * super.render 之后的覆盖层（滚动条 / 悬浮提示）。
     */
    protected abstract void renderOverlays(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /**
     * 补全面板已处理后的剩余滚动。
     */
    protected abstract boolean onScroll(double mouseX, double mouseY, double delta);

    /**
     * 补全面板已处理后的剩余点击（如右键平移、UNION 下拉关闭）。
     */
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        return false;
    }

    /** 进入界面后的额外初始化（如预选注册表请求 schema）。 */
    protected void onInit() {
    }

    @Override
    protected void init() {
        typeSelector = createTypeSelector();
        rebuild();
        if (typeSelector != null) {
            typeSelector.focus();
        }
        onInit();
    }

    /** 短暂状态提示（自动消失）。 */
    protected void setStatus(String text) {
        this.status = text;
        this.statusUntil = Util.getMillis() + ViewerStyle.STATUS_TIMEOUT_MS;
    }

    /** 持久状态提示（不自动消失）。 */
    protected void setStatusPersistent(String text) {
        this.status = text;
        this.statusUntil = Long.MAX_VALUE;
    }

    private void tickStatus() {
        if (!status.isEmpty() && Util.getMillis() >= statusUntil) {
            status = "";
        }
    }

    /**
     * 注册一个行内补全输入框（枚举/UNION 字段用）。
     */
    protected void addSelector(int x, int y, int width, int height, List<String> options, String current,
        Consumer<Integer> onIndex) {
        if (options == null || options.isEmpty()) {
            return;
        }
        TypeSelector sel = new TypeSelector(this, this::addRenderableWidget, x, y, width, height, y, options,
            name -> {
                int idx = options.indexOf(name);
                if (idx >= 0) {
                    onIndex.accept(idx);
                }
            });
        sel.addToScreen(this.font);
        sel.setValue(current);
        selectors.add(sel);
    }

    /** 顶部 + 行内所有补全面板（画在最上层）。 */
    protected void paintSelectors(GuiGraphics graphics, double mouseX, double mouseY) {
        if (typeSelector != null) {
            typeSelector.paintPanel(graphics, this.font, mouseX, mouseY);
        }
        for (TypeSelector s : selectors) {
            s.paintPanel(graphics, this.font, mouseX, mouseY);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        tickStatus();
        renderFormRegion(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderStatus(graphics);
        renderOverlays(graphics, mouseX, mouseY, partialTick);
        // 补全面板画在最上层（不应被字段/控件遮挡）
        paintSelectors(graphics, mouseX, mouseY);
    }

    /** Keep long paths and validation messages readable without extending beyond the screen. */
    private void renderStatus(GuiGraphics graphics) {
        if (status.isEmpty()) {
            return;
        }
        int x = ViewerStyle.LEFT_PADDING;
        int textWidth = Math.max(1, this.width - x * 2);
        String ellipsis = "…";
        var lines = this.font.split(Component.literal(status), Math.max(1, textWidth - this.font.width(ellipsis)));
        int count = Math.min(3, lines.size());
        int lineHeight = this.font.lineHeight + 1;
        int lastY = this.height - ViewerStyle.STATUS_BOTTOM;
        int top = lastY - (count - 1) * lineHeight;
        graphics.fill(x - 3, top - 3, this.width - x + 3, lastY + this.font.lineHeight + 3, 0xE0101010);
        for (int i = 0; i < count; i++) {
            graphics.drawString(this.font, lines.get(i), x, top + i * lineHeight, ViewerStyle.COLOR_STATUS);
        }
        if (lines.size() > count) {
            graphics.drawString(this.font, ellipsis, x + this.font.width(lines.get(count - 1)), lastY,
                ViewerStyle.COLOR_STATUS);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (typeSelector != null && typeSelector.mouseScrolled(delta)) {
            return true;
        }
        for (TypeSelector s : selectors) {
            if (s.mouseScrolled(delta)) {
                return true;
            }
        }
        return onScroll(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (typeSelector != null && typeSelector.keyPressed(keyCode)) {
            return true;
        }
        for (TypeSelector s : selectors) {
            if (s.keyPressed(keyCode)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (typeSelector != null && typeSelector.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        for (TypeSelector s : selectors) {
            if (s.mouseClicked(mouseX, mouseY)) {
                return true;
            }
        }
        if (onMouseClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
