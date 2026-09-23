package su.plo.voice.platform.forge.client.gui;

import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** 20x20 vanilla button with a 16x16 icon and the upstream drop shadow. */
@SideOnly(Side.CLIENT)
final class IconButton extends GuiButton {
    private final Supplier<ResourceLocation> icon;

    IconButton(int id, int x, int y, Supplier<ResourceLocation> icon) {
        super(id, x, y, 20, 20, "");
        this.icon = icon;
    }

    boolean isHovered() {
        return visible && field_146123_n;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        super.drawButton(mc, mouseX, mouseY);

        mc.getTextureManager().bindTexture(icon.get());
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        float shadow = enabled ? 0.25F : 0.16F;
        GL11.glColor4f(shadow, shadow, shadow, 1.0F);
        func_146110_a(xPosition + 2, yPosition + 3, 0.0F, 0.0F, 16, 16, 16.0F, 16.0F);
        float color = enabled ? 1.0F : 0.63F;
        GL11.glColor4f(color, color, color, 1.0F);
        func_146110_a(xPosition + 2, yPosition + 2, 0.0F, 0.0F, 16, 16, 16.0F, 16.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
