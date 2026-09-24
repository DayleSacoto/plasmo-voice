package su.plo.voice.platform.forge.client.gui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.opengl.GL11;

/** Upstream AbstractSlider: value 0..1, snapped by the owner, drawn with the vanilla knob. */
@SideOnly(Side.CLIENT)
final class SliderWidget extends Widget {
    private final DoubleSupplier value;
    private final DoubleConsumer onChange;
    private final DoubleUnaryOperator snap;
    private final Supplier<String> text;
    private boolean dragging;
    /** Upstream ActivationThresholdWidget microphone level (0..1) drawn behind the knob; null for other sliders. */
    DoubleSupplier level;

    SliderWidget(int width, DoubleSupplier value, DoubleConsumer onChange, DoubleUnaryOperator snap, Supplier<String> text) {
        super(width, 20);
        this.value = value;
        this.onChange = onChange;
        this.snap = snap;
        this.text = text;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        drawButtonBackground(mc, x, y, width, 0);
        if (level != null) drawLevel(mc, level.getAsDouble());
        double current = Math.max(0D, Math.min(1D, value.getAsDouble()));
        drawKnob(x + (int) (current * (width - 8)), y);
        drawCenteredString(mc.fontRenderer, text.get(), x + width / 2, y + (height - 8) / 2,
                dragging ? TEXT_HOVERED : textColor(mouseX, mouseY));
    }

    /** Upstream renderMicrophoneValue: the slider texture tinted green, yellow above 0.7 and red above 0.95. */
    private void drawLevel(Minecraft mc, double value) {
        int levelWidth = (int) ((width - 2) * Math.max(0D, Math.min(1D, value)));
        if (levelWidth <= 0) return;
        int color = value > 0.95D ? 0xFF0000 : value > 0.7D ? 0xFFFF00 : 0x00FF00;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        ScaledResolution scaled = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = scaled.getScaleFactor();
        GL11.glScissor((x + 1) * scale, mc.displayHeight - (y + height - 1) * scale, levelWidth * scale, (height - 2) * scale);
        drawButtonBackground(mc, x, y, width, 0, color);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        dragging = true;
        mouseDragged(mouseX, mouseY);
        return true;
    }

    @Override
    void mouseDragged(int mouseX, int mouseY) {
        if (!dragging) return;
        double raw = (mouseX - (x + 4)) / (double) (width - 8);
        onChange.accept(snap.applyAsDouble(Math.max(0D, Math.min(1D, raw))));
    }

    @Override
    void mouseReleased(int mouseX, int mouseY, int button) {
        if (dragging) playClick(Minecraft.getMinecraft());
        dragging = false;
    }
}
