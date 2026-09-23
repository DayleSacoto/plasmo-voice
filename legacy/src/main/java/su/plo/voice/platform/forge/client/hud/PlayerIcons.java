package su.plo.voice.platform.forge.client.hud;

import java.util.Set;
import java.util.UUID;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.lwjgl.opengl.GL11;
import su.plo.slib.api.position.Pos3d;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.PlayerVolumeAction;
import su.plo.voice.platform.forge.client.audio.ClientVoiceSources;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.audio.source.SourceInfo;
import su.plo.voice.proto.data.config.PlayerIconConfig;
import su.plo.voice.proto.data.config.PlayerIconVisibility;
import su.plo.voice.proto.data.player.VoicePlayerInfo;

/**
 * Upstream EntityIconStateExtractor and EntityIconRenderer for players: the voice state icon above the name tag,
 * drawn like the vanilla 1.7.10 name tag (see-through pass, then the depth-tested pass unless sneaking).
 */
@SideOnly(Side.CLIENT)
public final class PlayerIcons {
    private static final ResourceLocation NOT_INSTALLED_ICON = icon("headset_not_installed");
    private static final ResourceLocation CLIENT_MUTED_ICON = icon("speaker_disabled");
    private static final ResourceLocation SERVER_MUTED_ICON = icon("speaker_muted");
    private static final ResourceLocation VOICE_DISABLED_ICON = icon("headset_disabled");
    private static final float SCALE = 0.025F;
    private static final int SEE_THROUGH_ALPHA = 40;

    private final ClientState state;
    private final PlayerVolumeAction volumeAction;

    public PlayerIcons(ClientState state, PlayerVolumeAction volumeAction) {
        this.state = state;
        this.volumeAction = volumeAction;
    }

    @SubscribeEvent
    public void onSpecials(RenderLivingEvent.Specials.Post event) {
        if (!(event.entity instanceof EntityOtherPlayerMP)) return;
        EntityPlayer player = (EntityPlayer) event.entity;
        Minecraft mc = Minecraft.getMinecraft();
        ClientConnectionState connection = state.getConnection();
        if (mc.thePlayer == null || connection == null || !connection.isConnected() || !connection.isUdpConfirmed()) return;
        ClientConfig config = connection.getConfig();
        if (config == null || isHidden(mc) || player.isInvisibleToPlayer(mc.thePlayer) || isFakePlayer(mc, player)) return;
        double distanceSq = player.getDistanceSqToEntity(mc.renderViewEntity);
        if (distanceSq > 4096D) return;

        UUID playerId = player.getUniqueID();
        String percent = volumeAction.isShown(playerId)
                ? Math.round(state.getSourceVolume(ClientState.playerVolumeKey(playerId)) * 100D) + "%"
                : null;
        PlayerIconConfig iconConfig = config.getPacket().getPlayerIconConfig();
        ResourceLocation icon = playerIcon(config, connection.getPlayer(playerId), playerId, iconConfig.getIconVisibility());
        if (icon == null && percent == null) return;

        Pos3d offset = iconConfig.getIconOffset();
        double y = event.y + player.height + offset.getY();
        if (isNameShown(mc, player, distanceSq)) {
            y += 0.3D;
            if (distanceSq < 100D && player.getWorldScoreboard().func_96539_a(2) != null) y += 0.3D;
        }
        boolean sneaking = player.isSneaking();
        double x = event.x + offset.getX();
        double z = event.z + offset.getZ();
        if (percent != null) {
            renderPercent(mc, percent, x, y, z, sneaking);
            y += 0.3D;
        }
        if (icon != null) renderIcon(mc, icon, x, y, z, sneaking);
    }

    /** Upstream getPlayerIcon: installation, client mute, server mute, voice disabled, then the talking line. */
    private ResourceLocation playerIcon(ClientConfig config, VoicePlayerInfo info, UUID playerId, Set<PlayerIconVisibility> visibility) {
        if (info == null) {
            ResourceLocation sourceIcon = sourceIcon(config, playerId, visibility);
            if (sourceIcon != null) return sourceIcon;
            return visibility.contains(PlayerIconVisibility.HIDE_NOT_INSTALLED) ? null : NOT_INSTALLED_ICON;
        }
        if (state.isSourceMuted(ClientState.playerVolumeKey(playerId))) {
            return visibility.contains(PlayerIconVisibility.HIDE_CLIENT_MUTED) ? null : CLIENT_MUTED_ICON;
        }
        if (info.isMuted()) {
            return visibility.contains(PlayerIconVisibility.HIDE_SERVER_MUTED) ? null : SERVER_MUTED_ICON;
        }
        if (info.isVoiceDisabled()) {
            return visibility.contains(PlayerIconVisibility.HIDE_VOICE_CHAT_DISABLED) ? null : VOICE_DISABLED_ICON;
        }
        return sourceIcon(config, playerId, visibility);
    }

    /** Upstream getHighestActivatedSourceLine: the icon of the heaviest line the player is talking in. */
    private ResourceLocation sourceIcon(ClientConfig config, UUID playerId, Set<PlayerIconVisibility> visibility) {
        ClientVoiceSources sources = state.getConnection().getSources();
        if (sources == null || visibility.contains(PlayerIconVisibility.HIDE_SOURCE_ICON)) return null;
        VoiceSourceLine highest = null;
        for (SourceInfo source : sources.activated(playerId)) {
            VoiceSourceLine line = config.sourceLine(source.getLineId());
            if (line != null && (highest == null || line.getWeight() > highest.getWeight())) highest = line;
        }
        return highest == null ? null : new ResourceLocation(highest.getIcon());
    }

    /** Upstream show_source_icons: 0 follows the HUD (F1), 1 always, 2 never. */
    private boolean isHidden(Minecraft mc) {
        int showIcons = state.getShowSourceIcons();
        return showIcons == 2 || (showIcons == 0 && mc.gameSettings.hideGUI);
    }

    /** Upstream skips players missing from the tab list (NPCs); 1.7.10 lists players by name. */
    private static boolean isFakePlayer(Minecraft mc, EntityPlayer player) {
        for (GuiPlayerInfo info : mc.getNetHandler().playerInfoList) {
            if (info.name.equals(player.getCommandSenderName())) return false;
        }
        return true;
    }

    /** RendererLivingEntity.passSpecialRender draws the name tag under the same conditions. */
    private static boolean isNameShown(Minecraft mc, EntityPlayer player, double distanceSq) {
        float range = player.isSneaking() ? 32F : 64F;
        return Minecraft.isGuiEnabled() && player.riddenByEntity == null && distanceSq < range * range;
    }

    private static void renderPercent(Minecraft mc, String text, double x, double y, double z, boolean sneaking) {
        FontRenderer font = mc.fontRenderer;
        begin(x, y, z, sneaking);
        int halfWidth = font.getStringWidth(text) / 2;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_F(0F, 0F, 0F, 0.25F);
        tessellator.addVertex(-halfWidth - 1, -1D, 0D);
        tessellator.addVertex(-halfWidth - 1, 8D, 0D);
        tessellator.addVertex(halfWidth + 1, 8D, 0D);
        tessellator.addVertex(halfWidth + 1, -1D, 0D);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        font.drawString(text, -halfWidth, 0, 0x21FFFFFF);
        if (!sneaking) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            font.drawString(text, -halfWidth, 0, -1);
        }
        end();
    }

    private static void renderIcon(Minecraft mc, ResourceLocation icon, double x, double y, double z, boolean sneaking) {
        begin(x, y, z, sneaking);
        GL11.glTranslatef(-5F, 0F, 0F);
        mc.getTextureManager().bindTexture(icon);
        quad(SEE_THROUGH_ALPHA);
        if (!sneaking) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            quad(255);
        }
        end();
    }

    /** Upstream translateEntityMatrix: 0.5 above the attachment, facing the camera, 0.025 per pixel. */
    private static void begin(double x, double y, double z, boolean sneaking) {
        RenderManager renderManager = RenderManager.instance;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y + 0.5D, z);
        GL11.glNormal3f(0F, 1F, 0F);
        GL11.glRotatef(-renderManager.playerViewY, 0F, 1F, 0F);
        GL11.glRotatef(renderManager.playerViewX, 1F, 0F, 0F);
        GL11.glScalef(-SCALE, -SCALE, SCALE);
        GL11.glTranslatef(0F, -1F, 0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(false);
        // Sneaking hides the icon behind blocks, like the vanilla sneaking name tag.
        if (!sneaking) GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
    }

    private static void end() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glPopMatrix();
    }

    private static void quad(int alpha) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(255, 255, 255, alpha);
        tessellator.addVertexWithUV(0D, 0D, 0D, 0D, 0D);
        tessellator.addVertexWithUV(0D, 10D, 0D, 0D, 1D);
        tessellator.addVertexWithUV(10D, 10D, 0D, 1D, 1D);
        tessellator.addVertexWithUV(10D, 0D, 0D, 1D, 0D);
        tessellator.draw();
    }

    private static ResourceLocation icon(String name) {
        return new ResourceLocation("plasmovoice", "textures/icons/" + name + ".png");
    }
}
