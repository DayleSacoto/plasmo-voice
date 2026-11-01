package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/** Custom rows of the Groups tab, laid out like the native option rows (303 px wide, 4 px between controls). */
@SideOnly(Side.CLIENT)
final class GroupsRows {
    static final int GAP = 4;
    private static final int LINE_HEIGHT = 10;

    private GroupsRows() {
    }

    /** Wrapped text lines across the list width; a loading hint, an empty state or an info block. */
    static final class TextRow extends SettingsTab.Row {
        private final List<String> lines;
        private final int color;
        private final boolean centered;
        private int left;
        private int y;

        TextRow(FontRenderer font, String text, int color, boolean centered) {
            this(wrapLines(font, text), color, centered);
        }

        private TextRow(List<String> lines, int color, boolean centered) {
            super(Math.max(SettingsTab.ROW_HEIGHT - 8, lines.size() * LINE_HEIGHT + 8));
            this.lines = lines;
            this.color = color;
            this.centered = centered;
        }

        private static List<String> wrapLines(FontRenderer font, String text) {
            List<String> lines = new ArrayList<>();
            for (String line : VoiceSettingsScreen.lines(text)) {
                if (line.isEmpty()) {
                    lines.add("");
                    continue;
                }
                for (Object wrapped : font.listFormattedStringToWidth(line, SettingsTab.CONTAINER_WIDTH)) {
                    lines.add((String) wrapped);
                }
            }
            return lines;
        }

        @Override
        void layout(int left, int y) {
            this.left = left;
            this.y = y;
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
            FontRenderer font = mc.fontRenderer;
            int textY = y + (height - lines.size() * LINE_HEIGHT) / 2 + 1;
            for (String line : lines) {
                int x = centered ? left + SettingsTab.CONTAINER_WIDTH / 2 - font.getStringWidth(line) / 2 : left;
                font.drawStringWithShadow(line, x, textY, color);
                textY += LINE_HEIGHT;
            }
        }
    }

    /** Empty space, e.g. under the last row so the pinned status line never covers content. */
    static final class SpacerRow extends SettingsTab.Row {
        SpacerRow(int height) {
            super(height);
        }

        @Override
        void layout(int left, int y) {
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
        }
    }

    /** Buttons sharing the list width evenly, e.g. Create and Cancel. */
    static final class ButtonsRow extends SettingsTab.Row {
        private final List<Widget> widgets;
        private final Map<Widget, Supplier<String>> disabledTips;

        ButtonsRow(List<Widget> widgets, Map<Widget, Supplier<String>> disabledTips) {
            super(SettingsTab.ROW_HEIGHT);
            this.widgets = widgets;
            this.disabledTips = disabledTips;
        }

        @Override
        void layout(int left, int y) {
            int count = widgets.size();
            int each = (SettingsTab.CONTAINER_WIDTH - GAP * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                Widget widget = widgets.get(i);
                widget.width = each;
                widget.x = left + i * (each + GAP);
                widget.y = y + height / 2 - widget.height / 2;
            }
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
            for (Widget widget : widgets) widget.render(mc, mouseX, mouseY);
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }

        @Override
        String tooltip(int mouseX, int mouseY) {
            return widgetTooltip(widgets, disabledTips, mouseX, mouseY);
        }
    }

    /** Draws something in front of a label and returns the indent. */
    interface Marker {
        int draw(Minecraft mc, int x, int centerY);
    }

    /**
     * Marker, label, optional right-aligned detail text and controls from the right. The label hover shows
     * {@code labelTooltip}; a disabled control shows the reason registered for it.
     */
    static final class EntryRow extends SettingsTab.Row {
        private final Marker marker;
        private final Supplier<String> label;
        private final Supplier<String> detail;
        private final int detailColor;
        private final List<Widget> widgets;
        private final Map<Widget, Supplier<String>> disabledTips;
        private final Supplier<String> labelTooltip;
        private int left;
        private int y;
        private int labelX;
        private int labelWidth;
        private int labelTextWidth;
        private int labelTextY;

        EntryRow(Marker marker, Supplier<String> label, Supplier<String> detail, int detailColor, List<Widget> widgets,
                 Map<Widget, Supplier<String>> disabledTips, Supplier<String> labelTooltip) {
            super(SettingsTab.ROW_HEIGHT);
            this.marker = marker;
            this.label = label;
            this.detail = detail;
            this.detailColor = detailColor;
            this.widgets = widgets;
            this.disabledTips = disabledTips == null ? new IdentityHashMap<Widget, Supplier<String>>() : disabledTips;
            this.labelTooltip = labelTooltip;
        }

        /** From the right: the controls, the leftmost listed first in reading order, 4 px apart. */
        @Override
        void layout(int left, int y) {
            this.left = left;
            this.y = y;
            int cursor = left + SettingsTab.CONTAINER_WIDTH;
            for (int i = widgets.size() - 1; i >= 0; i--) {
                Widget widget = widgets.get(i);
                widget.x = cursor - widget.width;
                widget.y = y + height / 2 - widget.height / 2;
                cursor -= widget.width + GAP;
            }
            labelWidth = cursor;
        }

        @Override
        void render(Minecraft mc, int mouseX, int mouseY) {
            FontRenderer font = mc.fontRenderer;
            int centerY = y + height / 2;
            labelX = left + (marker == null ? 0 : marker.draw(mc, left, centerY));
            int right = labelWidth;
            String detailText = detail == null ? null : detail.get();
            if (detailText != null && !detailText.isEmpty()) {
                int detailX = right - font.getStringWidth(detailText);
                font.drawStringWithShadow(detailText, detailX, centerY - font.FONT_HEIGHT / 2, detailColor);
                right = detailX - GAP * 2;
            }
            String text = Widget.fit(font, label.get(), right - labelX);
            labelTextY = centerY - font.FONT_HEIGHT / 2;
            labelTextWidth = font.getStringWidth(text);
            font.drawStringWithShadow(text, labelX, labelTextY, 0xFFFFFF);
            for (Widget widget : widgets) widget.render(mc, mouseX, mouseY);
        }

        @Override
        List<Widget> widgets() {
            return widgets;
        }

        @Override
        String tooltip(int mouseX, int mouseY) {
            String tip = widgetTooltip(widgets, disabledTips, mouseX, mouseY);
            if (tip != null) return tip;
            if (labelTooltip != null && SettingsTab.isOverLabel(mouseX, mouseY, labelX, labelTextY, labelTextWidth,
                    Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT)) {
                return labelTooltip.get();
            }
            return null;
        }
    }

    /** The widget's own tooltip, or the reason for a disabled widget. */
    static String widgetTooltip(List<Widget> widgets, Map<Widget, Supplier<String>> disabledTips, int mouseX,
                                int mouseY) {
        for (Widget widget : widgets) {
            if (!widget.isMouseOver(mouseX, mouseY)) continue;
            String own = widget.tooltip(mouseX, mouseY);
            if (own != null) return own;
            Supplier<String> reason = disabledTips == null ? null : disabledTips.get(widget);
            if (reason != null && !widget.active) return reason.get();
            return null;
        }
        return null;
    }

    /** A small padlock for password protected groups. */
    static void drawLock(int x, int centerY) {
        int gold = 0xFFE0B040;
        Gui.drawRect(x + 1, centerY - 1, x + 8, centerY + 5, gold);
        Gui.drawRect(x + 2, centerY - 5, x + 3, centerY - 1, gold);
        Gui.drawRect(x + 6, centerY - 5, x + 7, centerY - 1, gold);
        Gui.drawRect(x + 2, centerY - 5, x + 7, centerY - 4, gold);
        Gui.drawRect(x + 4, centerY + 1, x + 5, centerY + 3, 0xFF3A2A00);
    }
}
