package su.plo.voice.platform.forge.client.gui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** Upstream TabButton: 8x8 tab icon and name; the open tab is drawn inactive with its "_disabled" icon. */
@SideOnly(Side.CLIENT)
final class TabButton extends Widget {
    private final String text;
    private final ResourceLocation icon;
    private final ResourceLocation disabledIcon;

    TabButton(FontRenderer font, String text, String iconName) {
        super(font.getStringWidth(text) + 24, 20);
        this.text = text;
        this.icon = new ResourceLocation("plasmovoice", "textures/icons/tabs/" + iconName + ".png");
        this.disabledIcon = new ResourceLocation("plasmovoice", "textures/icons/tabs/" + iconName + "_disabled.png");
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        drawButtonBackground(mc, x, y, width, buttonState(mouseX, mouseY));
        mc.getTextureManager().bindTexture(active ? icon : disabledIcon);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        float shadow = active ? 0.25F : 0.16F;
        GL11.glColor4f(shadow, shadow, shadow, 1F);
        func_146110_a(x + 7, y + 7, 0F, 0F, 8, 8, 8F, 8F);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        func_146110_a(x + 6, y + 6, 0F, 0F, 8, 8, 8F, 8F);
        mc.fontRenderer.drawStringWithShadow(text, x + 16, y + (height - 8) / 2, active ? 0xFFFFFF : 0xA0A0A0);
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        playClick(Minecraft.getMinecraft());
        return true;
    }
}
