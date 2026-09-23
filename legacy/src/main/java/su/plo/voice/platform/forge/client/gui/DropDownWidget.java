package su.plo.voice.platform.forge.client.gui;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/** Upstream DropDownWidget: a button that opens a scrollable list of choices under (or above) itself. */
@SideOnly(Side.CLIENT)
final class DropDownWidget extends Widget {
    private static final int ELEMENT_HEIGHT = 16;
    private static final int MAX_VISIBLE = 5;

    private final Supplier<String> text;
    private final List<String> elements;
    private final IntConsumer onSelect;
    private boolean open;
    private int scroll;
    private int screenHeight;

    DropDownWidget(int width, Supplier<String> text, List<String> elements, IntConsumer onSelect) {
        super(width, 20);
        this.text = text;
        this.elements = elements;
        this.onSelect = onSelect;
        this.active = !elements.isEmpty();
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        FontRenderer font = mc.fontRenderer;
        drawButtonBackground(mc, x, y, width, open ? 2 : buttonState(mouseX, mouseY));
        int color = textColor(mouseX, mouseY);
        drawString(font, fit(font, text.get(), width - 20), x + 5, y + (height - 8) / 2, color);
        drawString(font, open ? "▲" : "▼", x + width - 12, y + (height - 8) / 2, color);
    }

    /** Drawn after every row so the open list covers the rows below it. */
    void renderList(Minecraft mc, int mouseX, int mouseY, int screenHeight) {
        if (!open) return;
        this.screenHeight = screenHeight;
        FontRenderer font = mc.fontRenderer;
        int top = listTop();
        int visible = visibleCount();
        drawRect(x, top, x + width, top + visible * ELEMENT_HEIGHT, 0xFF000000);
        drawRect(x + 1, top + 1, x + width - 1, top + visible * ELEMENT_HEIGHT - 1, 0xFF202020);
        for (int i = 0; i < visible; i++) {
            int index = scroll + i;
            int elementY = top + i * ELEMENT_HEIGHT;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= elementY && mouseY < elementY + ELEMENT_HEIGHT;
            if (hovered) drawRect(x + 1, elementY + 1, x + width - 1, elementY + ELEMENT_HEIGHT - 1, 0xFF505050);
            drawString(font, fit(font, elements.get(index), width - 10), x + 5, elementY + (ELEMENT_HEIGHT - 8) / 2,
                    hovered ? TEXT_HOVERED : TEXT);
        }
    }

    /** The full name of a hovered element that did not fit. */
    String listTooltip(Minecraft mc, int mouseX, int mouseY) {
        int index = elementAt(mouseX, mouseY);
        if (index < 0) return null;
        String element = elements.get(index);
        return mc.fontRenderer.getStringWidth(element) > width - 10 ? element : null;
    }

    boolean isOverList(int mouseX, int mouseY) {
        int top = listTop();
        return open && mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + visibleCount() * ELEMENT_HEIGHT;
    }

    void scrollList(int direction) {
        scroll = Math.max(0, Math.min(elements.size() - visibleCount(), scroll + direction));
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (open) {
            int index = elementAt(mouseX, mouseY);
            open = false;
            if (index >= 0 && button == 0) {
                playClick(Minecraft.getMinecraft());
                onSelect.accept(index);
            }
            return index >= 0 || isMouseOver(mouseX, mouseY);
        }
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        playClick(Minecraft.getMinecraft());
        open = true;
        scroll = 0;
        return true;
    }

    @Override
    String tooltip(int mouseX, int mouseY) {
        String current = text.get();
        return Minecraft.getMinecraft().fontRenderer.getStringWidth(current) > width - 20 ? current : null;
    }

    private int elementAt(int mouseX, int mouseY) {
        if (!isOverList(mouseX, mouseY)) return -1;
        int index = scroll + (mouseY - listTop()) / ELEMENT_HEIGHT;
        return index < elements.size() ? index : -1;
    }

    private int visibleCount() {
        return Math.min(MAX_VISIBLE, elements.size());
    }

    private int listTop() {
        int below = y + height;
        int listHeight = visibleCount() * ELEMENT_HEIGHT;
        return screenHeight > 0 && below + listHeight > screenHeight ? y - listHeight : below;
    }
}
