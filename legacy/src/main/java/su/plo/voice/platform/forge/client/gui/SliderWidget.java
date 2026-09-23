package su.plo.voice.platform.forge.client.gui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;

/** Upstream AbstractSlider: value 0..1, snapped by the owner, drawn with the vanilla knob. */
@SideOnly(Side.CLIENT)
final class SliderWidget extends Widget {
    private final DoubleSupplier value;
    private final DoubleConsumer onChange;
    private final DoubleUnaryOperator snap;
    private final Supplier<String> text;
    private boolean dragging;

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
        double current = Math.max(0D, Math.min(1D, value.getAsDouble()));
        drawKnob(x + (int) (current * (width - 8)), y);
        drawCenteredString(mc.fontRenderer, text.get(), x + width / 2, y + (height - 8) / 2,
                dragging ? TEXT_HOVERED : textColor(mouseX, mouseY));
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
