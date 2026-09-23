package su.plo.voice.platform.forge.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** Upstream IconButton inside a settings row: reset and activation toggle icons. */
@SideOnly(Side.CLIENT)
final class IconWidget extends Widget {
    private final Supplier<ResourceLocation> icon;
    private final Runnable onPress;
    private final BooleanSupplier enabled;
    private final Supplier<String> tooltip;

    IconWidget(Supplier<ResourceLocation> icon, Runnable onPress, BooleanSupplier enabled, Supplier<String> tooltip) {
        super(20, 20);
        this.icon = icon;
        this.onPress = onPress;
        this.enabled = enabled;
        this.tooltip = tooltip;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        active = enabled.getAsBoolean();
        drawButtonBackground(mc, x, y, width, buttonState(mouseX, mouseY));
        mc.getTextureManager().bindTexture(icon.get());
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        float shadow = active ? 0.25F : 0.16F;
        GL11.glColor4f(shadow, shadow, shadow, 1F);
        func_146110_a(x + 2, y + 3, 0F, 0F, 16, 16, 16F, 16F);
        float color = active ? 1F : 0.63F;
        GL11.glColor4f(color, color, color, 1F);
        func_146110_a(x + 2, y + 2, 0F, 0F, 16, 16, 16F, 16F);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        playClick(Minecraft.getMinecraft());
        onPress.run();
        return true;
    }

    @Override
    String tooltip(int mouseX, int mouseY) {
        return tooltip == null ? null : tooltip.get();
    }
}
