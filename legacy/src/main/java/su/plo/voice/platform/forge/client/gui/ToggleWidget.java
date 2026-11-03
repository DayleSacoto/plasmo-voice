package su.plo.voice.platform.forge.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import su.plo.voice.platform.forge.client.lang.VoiceLang;

/** Upstream ToggleButton: slider background with the knob on the left (off) or right (on). */
@SideOnly(Side.CLIENT)
final class ToggleWidget extends Widget {
    private final BooleanSupplier value;
    private final Consumer<Boolean> onChange;

    ToggleWidget(int width, BooleanSupplier value, Consumer<Boolean> onChange) {
        super(width, 20);
        this.value = value;
        this.onChange = onChange;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        boolean on = value.getAsBoolean();
        // vanilla GuiOptionSlider: disabled background row, knob drawn in mouseDragged, normal button text colors
        VanillaButton.draw(mc, this, isMouseOver(mouseX, mouseY), 0,
                VoiceLang.format(on ? "gui.plasmovoice.toggle.on" : "gui.plasmovoice.toggle.off"), 0,
                () -> drawKnob(on ? x + width - 8 : x, y), null);
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        playClick(Minecraft.getMinecraft());
        onChange.accept(!value.getAsBoolean());
        return true;
    }
}
