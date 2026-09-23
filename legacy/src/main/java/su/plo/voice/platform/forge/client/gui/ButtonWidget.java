package su.plo.voice.platform.forge.client.gui;

import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;

/** Upstream Button inside a settings row, e.g. the HUD position choosers. */
@SideOnly(Side.CLIENT)
final class ButtonWidget extends Widget {
    private final Supplier<String> text;
    private final Runnable onPress;

    ButtonWidget(int width, Supplier<String> text, Runnable onPress) {
        super(width, 20);
        this.text = text;
        this.onPress = onPress;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        drawButtonBackground(mc, x, y, width, buttonState(mouseX, mouseY));
        drawCenteredString(mc.fontRenderer, fit(mc.fontRenderer, text.get(), width - 8), x + width / 2,
                y + (height - 8) / 2, textColor(mouseX, mouseY));
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        playClick(Minecraft.getMinecraft());
        onPress.run();
        return true;
    }
}
