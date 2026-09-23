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
    private List<String> tooltip;

    SettingsTab(VoiceSettingsScreen screen, ClientState state) {
        this.screen = screen;
        this.state = state;
    }

    /** Adds the rows; called again when the rows depend on a changed value. */
    abstract void build();

    void init(int top, int bottom) {
        this.top = top;
        this.bottom = bottom;
        rows.clear();
        build();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    void rebuild() {
        init(top, bottom);
    }

    /** Called when the tab or the screen closes. */
    void removed() {
    }

    void addCategory(String translationKey, Object... args) {
        rows.add(new CategoryRow(I18n.format(translationKey, args)));
    }

    /** Upstream OptionEntry / ButtonOptionEntry: label, element, optional icon buttons, reset. */
    void addOption(String label, String tooltipKey, Widget element, BooleanSupplier isDefault, Runnable reset, Widget... buttons) {
        IconWidget resetButton = new IconWidget(() -> RESET_ICON, reset, () -> !isDefault.getAsBoolean(), null);
        rows.add(new OptionRow(label, tooltipKey, element, resetButton, Arrays.asList(buttons)));
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
        if (dragging != null) dragging.mouseReleased(mouseX, mouseY, button);
        dragging = null;
    }

    boolean keyTyped(char typedChar, int keyCode) {
        for (Row row : rows) {
            for (Widget widget : row.widgets()) {
                if (widget.keyTyped(typedChar, keyCode)) return true;
            }
        }
        return false;
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

    abstract static class Row {
        final int height = ROW_HEIGHT;

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

    private static final class OptionRow extends Row {
        private final String label;
        private final String tooltipKey;
        private final Widget element;
        private final IconWidget reset;
        private final List<Widget> buttons;
        private final List<Widget> widgets = new ArrayList<>();
        private int left;
        private int y;

        OptionRow(String label, String tooltipKey, Widget element, IconWidget reset, List<Widget> buttons) {
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
            int labelWidth = element.x - left - 4;
            font.drawStringWithShadow(Widget.fit(font, label, labelWidth), left, y + height / 2 - font.FONT_HEIGHT / 2, 0xFFFFFF);
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
