package su.plo.voice.platform.forge.client.gui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;

/**
 * Draws a settings widget through vanilla {@link GuiButton#drawButton}, so its texture row, hover state and text color
 * come from vanilla's own code, including whatever mods and resource packs hook into it (e.g. dark-mode button text
 * colors). The widget only adds its extras: {@code foreground} is drawn where a vanilla slider draws its knob, and
 * {@code text} replaces the centered label but draws with the color vanilla chose.
 */
@SideOnly(Side.CLIENT)
final class VanillaButton extends GuiButton {
    /** Draws the label with the color vanilla picked for the button's state. */
    interface Text {
        void draw(FontRenderer font, int color);
    }

    private static final VanillaButton BUTTON = new VanillaButton();

    private int state;
    private Runnable foreground;
    private Text text;

    private VanillaButton() {
        super(0, 0, 0, 0, 0, "");
    }

    /**
     * @param hovered     drawn as hovered (vanilla tests the mouse; a dragged slider stays hovered)
     * @param state       background row like {@link #getHoverState} (0 disabled, 1 normal, 2 hovered); -1 for vanilla's
     * @param label       centered label, used when {@code text} is null
     * @param packedColor fixed text color (vanilla packedFGColour), 0 for vanilla's
     * @param foreground  drawn over the background before the text; null for none
     * @param text        custom label placement; null for the centered {@code label}
     */
    static void draw(Minecraft mc, Widget widget, boolean hovered, int state, String label, int packedColor,
                     Runnable foreground, Text text) {
        VanillaButton button = BUTTON;
        button.xPosition = widget.x;
        button.yPosition = widget.y;
        button.width = widget.width;
        button.height = widget.height;
        button.enabled = widget.active;
        button.visible = true;
        button.displayString = label == null ? "" : label;
        button.packedFGColour = packedColor;
        button.state = state;
        button.foreground = foreground;
        button.text = text;
        try {
            button.drawButton(mc, hovered ? widget.x : widget.x - 1, widget.y);
        } finally {
            button.foreground = null;
            button.text = null;
        }
    }

    @Override
    public int getHoverState(boolean mouseOver) {
        return state >= 0 ? state : super.getHoverState(mouseOver);
    }

    @Override
    protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (foreground != null) foreground.run();
    }

    @Override
    public void drawCenteredString(FontRenderer font, String string, int x, int y, int color) {
        if (text != null) {
            text.draw(font, color);
        } else {
            super.drawCenteredString(font, string, x, y, color);
        }
    }
}
