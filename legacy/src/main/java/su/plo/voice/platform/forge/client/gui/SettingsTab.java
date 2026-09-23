package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import su.plo.voice.platform.forge.client.ClientState;

/** Upstream TabWidget: a scrolling list of category and option rows below the navigation header. */
@SideOnly(Side.CLIENT)
abstract class SettingsTab {
    static final int CONTAINER_WIDTH = 303;
    static final int ELEMENT_WIDTH = 124;
    static final int ROW_HEIGHT = 24;
    private static final int TOOLTIP_WIDTH = 200;
    private static final ResourceLocation RESET_ICON = new ResourceLocation("plasmovoice", "textures/icons/reset.png");

    final VoiceSettingsScreen screen;
    final ClientState state;
    private final List<Row> rows = new ArrayList<>();
    private int top;
    private int bottom;
    private int scroll;
    private Widget dragging;
    /** Upstream AbstractHotKeysTabWidget.focusedHotKey: receives every key and click while recording. */
    private HotKeyWidget recording;
    private List<String> tooltip;

    SettingsTab(VoiceSettingsScreen screen, ClientState state) {
        this.screen = screen;
        this.state = state;
    }

    /** Adds the rows; called again when the rows depend on a changed value. */
    abstract void build();

    /** The tab opens or the screen is resized. */
    void init(int top, int bottom) {
        this.top = top;
        this.bottom = bottom;
        rebuild();
    }

    void rebuild() {
        rows.clear();
        build();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    /** Every game tick while the tab is shown. */
    void tick() {
    }

    /** Called when the tab or the screen closes. */
    void removed() {
        recording = null;
    }

    HotKeyWidget getRecording() {
        return recording;
    }

    void setRecording(HotKeyWidget recording) {
        this.recording = recording;
    }

    void addHotkey(String labelKey, String name) {
        addOption(I18n.format(labelKey), null, new HotKeyWidget(this, state.getHotkeys(), name),
                () -> state.getHotkeys().isDefault(name), () -> state.getHotkeys().reset(name));
    }

    void addCategory(String translationKey, Object... args) {
        rows.add(new CategoryRow(I18n.format(translationKey, args)));
    }

    /** Upstream OptionEntry / ButtonOptionEntry: label, element, optional icon buttons, reset. */
    void addOption(String label, String tooltipKey, Widget element, BooleanSupplier isDefault, Runnable reset, Widget... buttons) {
        addIconOption(null, label, tooltipKey, element, isDefault, reset, buttons);
    }

    /** Upstream OverlaySourceEntry: a 16px icon in front of the label. */
    void addIconOption(ResourceLocation labelIcon, String label, String tooltipKey, Widget element,
                       BooleanSupplier isDefault, Runnable reset, Widget... buttons) {
        addRow(ROW_HEIGHT, labelIcon == null ? null : iconLabel(labelIcon), label, tooltipKey, element, isDefault, reset, buttons);
    }

    static LabelIcon iconLabel(ResourceLocation icon) {
        return (mc, x, centerY) -> {
            mc.getTextureManager().bindTexture(icon);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            Gui.func_146110_a(x, centerY - 8, 0F, 0F, 16, 16, 16F, 16F);
            return 20;
        };
    }

    /** Option row with a custom label icon, e.g. upstream PlayerVolumeEntry's 24px head in a 30px row. */
    void addRow(int height, LabelIcon labelIcon, String label, String tooltipKey, Widget element,
                BooleanSupplier isDefault, Runnable reset, Widget... buttons) {
        IconWidget resetButton = new IconWidget(() -> RESET_ICON, reset, () -> !isDefault.getAsBoolean(), null);
        rows.add(new OptionRow(height, labelIcon, label, tooltipKey, element, resetButton, Arrays.asList(buttons)));
    }

    /** Upstream FullWidthEntry: one widget across the whole list width. */
    void addFullWidth(Widget widget, int height) {
        rows.add(new FullWidthRow(widget, height));
    }

    void render(Minecraft mc, int mouseX, int mouseY) {
        tooltip = null;
        int left = screen.width / 2 - CONTAINER_WIDTH / 2;
        DropDownWidget open = openDropDown();
        boolean inside = mouseY >= top && mouseY < bottom && (open == null || !open.isOverList(mouseX, mouseY));

        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, mc.displayHeight - bottom * scale, mc.displayWidth, (bottom - top) * scale);
        int y = top + 4 - scroll;
        for (Row row : rows) {
            row.layout(left, y);
            if (y + row.height > top && y < bottom) {
                row.render(mc, inside ? mouseX : -1, inside ? mouseY : -1);
                if (inside && mouseY >= y && mouseY < y + row.height) {
                    String rowTooltip = row.tooltip(mouseX, mouseY);
                    if (rowTooltip != null) tooltip = wrap(mc.fontRenderer, rowTooltip);
                }
            }
            y += row.height;
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        drawScrollbar(left + CONTAINER_WIDTH + 8);

        if (open != null) {
            open.renderList(mc, mouseX, mouseY, screen.height);
            String name = open.listTooltip(mc, mouseX, mouseY);
            if (name != null) tooltip = wrap(mc.fontRenderer, name);
        }
    }

    List<String> getTooltip() {
        return tooltip;
    }

    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (recording != null) return recording.mouseClicked(mouseX, mouseY, button);
        DropDownWidget open = openDropDown();
        if (open != null) {
            open.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (mouseY < top || mouseY >= bottom) return false;
        for (Row row : rows) {
            for (Widget widget : row.widgets()) {
                if (widget.mouseClicked(mouseX, mouseY, button)) {
                    dragging = widget;
                    return true;
                }
            }
        }
        return false;
    }

    void mouseDragged(int mouseX, int mouseY) {
        if (dragging != null) dragging.mouseDragged(mouseX, mouseY);
    }

    void mouseReleased(int mouseX, int mouseY, int button) {
        if (recording != null) {
            recording.mouseReleased(mouseX, mouseY, button);
            return;
        }
        if (dragging != null) dragging.mouseReleased(mouseX, mouseY, button);
        dragging = null;
    }

    boolean keyTyped(char typedChar, int keyCode) {
        if (recording != null) return recording.keyTyped(typedChar, keyCode);
        for (Row row : rows) {
            for (Widget widget : row.widgets()) {
                if (widget.keyTyped(typedChar, keyCode)) return true;
            }
        }
        return false;
    }

    void keyReleased(int keyCode) {
        if (recording != null) recording.keyReleased(keyCode);
    }

    void scroll(int direction, int mouseX, int mouseY) {
        DropDownWidget open = openDropDown();
        if (open != null && open.isOverList(mouseX, mouseY)) {
            open.scrollList(direction);
            return;
        }
        if (open != null) open.close();
        scroll = Math.max(0, Math.min(maxScroll(), scroll + direction * ROW_HEIGHT));
    }

    private DropDownWidget openDropDown() {
        for (Row row : rows) {
            for (Widget widget : row.widgets()) {
                if (widget instanceof DropDownWidget && ((DropDownWidget) widget).isOpen()) return (DropDownWidget) widget;
            }
        }
        return null;
    }

    private int contentHeight() {
        int height = 8;
        for (Row row : rows) height += row.height;
        return height;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (bottom - top));
    }

    private void drawScrollbar(int x) {
        int max = maxScroll();
        if (max <= 0) return;
        int viewport = bottom - top;
        int thumb = Math.max(32, viewport * viewport / contentHeight());
        int thumbY = top + scroll * (viewport - thumb) / max;
        Gui.drawRect(x, top, x + 6, bottom, 0xFF000000);
        Gui.drawRect(x, thumbY, x + 6, thumbY + thumb, 0xFF808080);
        Gui.drawRect(x, thumbY, x + 5, thumbY + thumb - 1, 0xFFC0C0C0);
    }

    static List<String> wrap(FontRenderer font, String text) {
        List<String> lines = new ArrayList<>();
        for (String line : VoiceSettingsScreen.lines(text)) {
            if (line.isEmpty()) {
                lines.add("");
                continue;
            }
            for (Object wrapped : font.listFormattedStringToWidth(line, TOOLTIP_WIDTH)) lines.add((String) wrapped);
        }
        return lines;
    }

    /** Draws the label icon centered on the row and returns the label indent. */
    interface LabelIcon {
        int draw(Minecraft mc, int x, int centerY);
    }

    abstract static class Row {
        final int height;

        Row(int height) {
            this.height = height;
        }

        abstract void layout(int left, int y);

        abstract void render(Minecraft mc, int mouseX, int mouseY);

        List<Widget> widgets() {
            return new ArrayList<>();
        }

        String tooltip(int mouseX, int mouseY) {
            return null;
        }
    }

    private static final class CategoryRow extends Row {
        private final String text;
        private int left;
        private int y;

        CategoryRow(String text) {
            super(ROW_HEIGHT);
            this.text = text;
        }

        @Override
        void layout(int left, int y) {
            this.left = left;
            this.y = y;
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
            FontRenderer font = mc.fontRenderer;
            font.drawStringWithShadow(text, left + CONTAINER_WIDTH / 2 - font.getStringWidth(text) / 2,
                    y + height / 2 - font.FONT_HEIGHT / 2, 0xFFFFFF);
        }
    }

    private static final class FullWidthRow extends Row {
        private final Widget widget;
        private final List<Widget> widgets = new ArrayList<>();

        FullWidthRow(Widget widget, int height) {
            super(height);
            this.widget = widget;
            widgets.add(widget);
        }

        @Override
        void layout(int left, int y) {
            widget.x = left;
            widget.y = y + height / 2 - widget.height / 2;
            widget.width = CONTAINER_WIDTH;
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
            widget.render(mc, mouseX, mouseY);
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }
    }

    private static final class OptionRow extends Row {
        private final LabelIcon labelIcon;
        private final String label;
        private final String tooltipKey;
        private final Widget element;
        private final IconWidget reset;
        private final List<Widget> buttons;
        private final List<Widget> widgets = new ArrayList<>();
        private int left;
        private int y;

        OptionRow(int height, LabelIcon labelIcon, String label, String tooltipKey, Widget element, IconWidget reset,
                  List<Widget> buttons) {
            super(height);
            this.labelIcon = labelIcon;
            this.label = label;
            this.tooltipKey = tooltipKey;
            this.element = element;
            this.reset = reset;
            this.buttons = buttons;
            widgets.add(element);
            widgets.addAll(buttons);
            widgets.add(reset);
        }

        /** From the right: reset, then the icon buttons, then the element, 4px apart. */
        @Override
        void layout(int left, int y) {
            this.left = left;
            this.y = y;
            int widgetY = y + height / 2 - element.height / 2;
            int right = left + CONTAINER_WIDTH;
            reset.x = right - 20;
            reset.y = widgetY;
            int cursor = right - 24;
            for (int i = buttons.size() - 1; i >= 0; i--) {
                Widget button = buttons.get(i);
                button.x = cursor - button.width;
                button.y = widgetY;
                cursor -= button.width + 4;
            }
            element.x = cursor - element.width;
            element.y = widgetY;
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
            FontRenderer font = mc.fontRenderer;
            int labelX = left;
            if (labelIcon != null) labelX += labelIcon.draw(mc, left, y + height / 2);
            int labelWidth = element.x - labelX - 4;
            font.drawStringWithShadow(Widget.fit(font, label, labelWidth), labelX, y + height / 2 - font.FONT_HEIGHT / 2, 0xFFFFFF);
            element.render(mc, mouseX, mouseY);
            for (Widget button : buttons) button.render(mc, mouseX, mouseY);
            reset.render(mc, mouseX, mouseY);
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }

        @Override
        String tooltip(int mouseX, int mouseY) {
            for (Widget widget : widgets) {
                if (widget.isMouseOver(mouseX, mouseY)) return widget.tooltip(mouseX, mouseY);
            }
            if (mouseX < element.x && tooltipKey != null) return I18n.format(tooltipKey);
            return null;
        }
    }
}
