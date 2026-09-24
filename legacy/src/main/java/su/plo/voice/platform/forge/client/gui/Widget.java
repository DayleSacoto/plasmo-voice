package su.plo.voice.platform.forge.client.gui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** Settings widget drawn with the vanilla 1.7.10 button textures; its row positions it every frame. */
@SideOnly(Side.CLIENT)
abstract class Widget extends Gui {
    static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    static final int TEXT = 0xE0E0E0;
    static final int TEXT_HOVERED = 0xFFFFA0;
    static final int TEXT_DISABLED = 0xA0A0A0;

    int x;
    int y;
    int width;
    final int height;
    boolean active = true;

    Widget(int width, int height) {
        this.width = width;
        this.height = height;
    }

    abstract void render(Minecraft mc, int mouseX, int mouseY);

    boolean mouseClicked(int mouseX, int mouseY, int button) {
        return false;
    }

    void mouseReleased(int mouseX, int mouseY, int button) {
    }

    void mouseDragged(int mouseX, int mouseY) {
    }

    boolean keyTyped(char typedChar, int keyCode) {
        return false;
    }

    /** Tooltip for the widget itself, e.g. a device name that did not fit; null for none. */
    String tooltip(int mouseX, int mouseY) {
        return null;
    }

    boolean isMouseOver(int mouseX, int mouseY) {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    /** Vanilla GuiButton background: state 0 disabled, 1 normal, 2 hovered. */
    void drawButtonBackground(Minecraft mc, int x, int y, int width, int state) {
        drawButtonBackground(mc, x, y, width, state, 0xFFFFFF);
    }

    /** The vanilla button texture tinted with an RGB color. */
    void drawButtonBackground(Minecraft mc, int x, int y, int width, int state, int color) {
        mc.getTextureManager().bindTexture(WIDGETS);
        GL11.glColor4f(((color >> 16) & 0xFF) / 255F, ((color >> 8) & 0xFF) / 255F, (color & 0xFF) / 255F, 1F);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glBlendFunc(770, 771);
        drawTexturedModalRect(x, y, 0, 46 + state * 20, width / 2, height);
        drawTexturedModalRect(x + width / 2, y, 200 - width / 2, 46 + state * 20, width / 2, height);
    }

    /** Vanilla slider knob (GuiOptionSlider). */
    void drawKnob(int knobX, int knobY) {
        drawTexturedModalRect(knobX, knobY, 0, 66, 4, 20);
        drawTexturedModalRect(knobX + 4, knobY, 196, 66, 4, 20);
    }

    int buttonState(int mouseX, int mouseY) {
        if (!active) return 0;
        return isMouseOver(mouseX, mouseY) ? 2 : 1;
    }

    int textColor(int mouseX, int mouseY) {
        if (!active) return TEXT_DISABLED;
        return isMouseOver(mouseX, mouseY) ? TEXT_HOVERED : TEXT;
    }

    static String fit(FontRenderer font, String text, int width) {
        if (font.getStringWidth(text) <= width) return text;
        return font.trimStringToWidth(text, width - font.getStringWidth("...")) + "...";
    }

    static void playClick(Minecraft mc) {
        mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1F));
    }
}
