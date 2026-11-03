package su.plo.voice.platform.forge.client.hud;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Player heads for the voice GUI and HUD: the face plus the hat layer from the skin the world renderer uses for the
 * player (Steve when the player is not loaded), optionally dimmed and with a white speaking outline drawn just
 * outside the head. Every GL change is undone before returning.
 */
public final class PlayerHeads {
    /** Outline thickness in GUI pixels, drawn outside the head so it never covers the skin. */
    public static final int OUTLINE = 1;
    static final int OUTLINE_COLOR = 0xFFFFFFFF;
    /** Colour multiplier of a dimmed (offline) head. */
    static final float DIMMED = 0.4F;

    private PlayerHeads() {
    }

    /** How a member's head is drawn; pure, so GroupsTab and the HUD share and test one rule. */
    public static final class Style {
        public final boolean dimmed;
        public final boolean outlined;

        private Style(boolean dimmed, boolean outlined) {
            this.dimmed = dimmed;
            this.outlined = outlined;
        }

        /**
         * @param groupSpeaking speaking through group voice right now (proximity speech must not be passed here)
         * @return offline heads are dimmed and never outlined; an online group speaker is outlined
         */
        public static Style of(boolean online, boolean groupSpeaking) {
            return new Style(!online, online && groupSpeaking);
        }
    }

    /**
     * A Groups member head: the skin is remembered by {@code nickname} for the session, so it stays after the player
     * unloads, leaves the range or goes offline (see {@link PlayerSkins}); a null nickname draws the default head.
     */
    public static void draw(Minecraft mc, UUID playerId, String nickname, int x, int y, int size, Style style) {
        draw(mc, PlayerSkins.skin(mc, playerId, nickname), x, y, size, style.dimmed, style.outlined);
    }

    private static void draw(Minecraft mc, ResourceLocation skin, int x, int y, int size, boolean dimmed,
                             boolean outlined) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        mc.getTextureManager().bindTexture(skin);
        // GTNH SimpleSkinBackport turns every player skin into a 64x64 texture, vanilla 1.7.10 skins are 64x32:
        // the head sits at the same pixels in both, so its UVs come from the bound texture's real size.
        float height = VoiceHud.skinTextureHeight(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT));
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        float shade = dimmed ? DIMMED : 1F;
        GL11.glColor4f(shade, shade, shade, 1F);
        Gui.func_152125_a(x, y, 8F, 8F, 8, 8, size, size, 64F, height);
        Gui.func_152125_a(x, y, 40F, 8F, 8, 8, size, size, 64F, height);
        if (outlined) {
            Gui.drawRect(x - OUTLINE, y - OUTLINE, x + size + OUTLINE, y, OUTLINE_COLOR);
            Gui.drawRect(x - OUTLINE, y + size, x + size + OUTLINE, y + size + OUTLINE, OUTLINE_COLOR);
            Gui.drawRect(x - OUTLINE, y, x, y + size, OUTLINE_COLOR);
            Gui.drawRect(x + size, y, x + size + OUTLINE, y + size, OUTLINE_COLOR);
        }
        GL11.glPopAttrib();
    }
}
