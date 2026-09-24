package su.plo.voice.platform.forge.client.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.opengl.GL11;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.ClientVoiceSources;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.SourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;

/** Upstream HudIconRenderer and OverlayRenderer, drawn after the vanilla HUD at the current GUI scale. */
@SideOnly(Side.CLIENT)
public final class VoiceHud extends Gui {
    private static final ResourceLocation MICROPHONE_DISCONNECTED_ICON = icon("microphone_disconnected");
    private static final ResourceLocation MICROPHONE_MUTED_ICON = icon("microphone_muted");
    private static final ResourceLocation MICROPHONE_DISABLED_ICON = icon("microphone_disabled");
    private static final ResourceLocation SPEAKER_DISABLED_ICON = icon("speaker_disabled");
    private static final int ENTRY_HEIGHT = 16;
    private static final int MAX_NAME_LENGTH = 40;
    private static final int BACKGROUND_COLOR = 0x40000000;

    private final ClientState state;

    public VoiceHud(ClientState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getMinecraft();
        ClientConnectionState connection = state.getConnection();
        if (mc.thePlayer == null || mc.gameSettings.hideGUI || connection == null || !connection.isConnected()
                || connection.getConfig() == null || connection.getUdp() == null) return;

        int width = event.resolution.getScaledWidth();
        int height = event.resolution.getScaledHeight();
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        if (state.isShowActivationIcon()) renderActivationIcon(mc, connection, width, height);
        if (state.isOverlayEnabled()) renderOverlay(mc, connection, width, height);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glPopMatrix();
    }

    private void renderActivationIcon(Minecraft mc, ClientConnectionState connection, int width, int height) {
        ResourceLocation icon = activationIcon(connection,
                connection.localPlayerId(mc.thePlayer.getCommandSenderName(), mc.thePlayer.getUniqueID()));
        if (icon == null) return;
        HudOptions.IconPosition position = state.getActivationIconPosition();
        // Upstream moves the bottom center icon out of the way of the creative hotbar.
        int offsetY = position == HudOptions.IconPosition.BOTTOM_CENTER && mc.playerController != null
                && mc.playerController.isInCreativeMode() ? -10 : 0;
        mc.getTextureManager().bindTexture(icon);
        func_146110_a(position.iconX(width), position.iconY(height) + offsetY, 0F, 0F, 16, 16, 16F, 16F);
    }

    /** Upstream order: timed out, voice disabled, server mute, microphone muted, then the talking activation. */
    private ResourceLocation activationIcon(ClientConnectionState connection, UUID selfId) {
        if (connection.getUdp().isTimedOut()) return MICROPHONE_DISCONNECTED_ICON;
        if (state.isVoiceDisabled()) return SPEAKER_DISABLED_ICON;
        for (VoicePlayerInfo player : connection.getPlayers()) {
            if (player.getPlayerId().equals(selfId) && player.isMuted()) return MICROPHONE_MUTED_ICON;
        }
        if (state.isMicrophoneMuted()) return MICROPHONE_DISABLED_ICON;
        if (!state.isActivationActive()) return null;
        VoiceActivation proximity = connection.getConfig().activation(VoiceActivation.PROXIMITY_ID);
        return proximity == null ? null : new ResourceLocation(proximity.getIcon());
    }

    private void renderOverlay(Minecraft mc, ClientConnectionState connection, int width, int height) {
        ClientVoiceSources sources = connection.getSources();
        if (sources == null || mc.theWorld == null) return;
        List<SourceInfo> audible = sources.audible();
        List<VoiceSourceLine> lines = new ArrayList<>(connection.getConfig().getPacket().getSourceLines());
        lines.sort(Comparator.comparingInt(VoiceSourceLine::getWeight).reversed());

        int index = 0;
        for (VoiceSourceLine line : lines) {
            HudOptions.OverlaySourceState lineState = state.getOverlaySourceState(line.getName());
            if (lineState == HudOptions.OverlaySourceState.OFF || lineState == HudOptions.OverlaySourceState.NEVER) continue;
            for (SourceInfo source : audible) {
                if (!line.getId().equals(source.getLineId())) continue;
                renderEntry(mc, line, source, connection, index++, width, height);
            }
        }
    }

    /** Upstream OverlayRenderer.renderEntry: player head, name on a translucent background, line icon. */
    private void renderEntry(Minecraft mc, VoiceSourceLine line, SourceInfo source, ClientConnectionState connection,
                             int index, int width, int height) {
        FontRenderer font = mc.fontRenderer;
        HudOptions.OverlayPosition position = state.getOverlayPosition();
        HudOptions.OverlayStyle style = state.getOverlayStyle();
        int x = position.startX(width);
        int y = position.startY(height);
        y += position.isBottom() ? -(ENTRY_HEIGHT + 1) * (index + 1) : (ENTRY_HEIGHT + 1) * index;

        if (style.hasSkin() && source instanceof PlayerSourceInfo) {
            if (position.isRight()) x -= 16;
            drawHead(mc, ((PlayerSourceInfo) source).getPlayerInfo().getPlayerId(), x, y, 16);
            if (!position.isRight()) x += 16 + 1;
        }

        String name = sourceName(source, line, connection);
        if (style.hasName() && !name.trim().isEmpty()) {
            int textWidth = font.getStringWidth(name) + 8;
            if (position.isRight()) x -= textWidth + 1;
            drawRect(x, y, x + textWidth, y + ENTRY_HEIGHT, BACKGROUND_COLOR);
            font.drawString(name, x + 4, y + 4, 0xFFFFFF);
            if (!position.isRight()) x += textWidth + 1;
        }

        if (position.isRight()) x -= 16 + 1;
        drawRect(x, y, x + 16, y + ENTRY_HEIGHT, BACKGROUND_COLOR);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(new ResourceLocation(line.getIcon()));
        func_146110_a(x, y, 0F, 0F, 16, 16, 16F, 16F);
    }

    /**
     * ponytail: the skin of a player loaded in the world, Steve otherwise; 1.7.10 has no profile cache
     * for players out of render range. Load skins by profile if the overlay needs distant sources.
     */
    public static void drawHead(Minecraft mc, UUID playerId, int x, int y, int size) {
        EntityPlayer player = mc.theWorld.func_152378_a(playerId);
        ResourceLocation skin = player instanceof AbstractClientPlayer
                ? ((AbstractClientPlayer) player).getLocationSkin()
                : AbstractClientPlayer.locationStevePng;
        mc.getTextureManager().bindTexture(skin);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        func_152125_a(x, y, 8F, 8F, 8, 8, size, size, 64F, 32F);
        func_152125_a(x, y, 40F, 8F, 8, 8, size, size, 64F, 32F);
    }

    /** Upstream getSourceSenderName: the source name, the voice player's nick, or the line name. */
    private static String sourceName(SourceInfo source, VoiceSourceLine line, ClientConnectionState connection) {
        if (source.getName() != null) {
            String name = source.getName();
            return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) + "..." : name;
        }
        if (source instanceof PlayerSourceInfo) {
            UUID playerId = ((PlayerSourceInfo) source).getPlayerInfo().getPlayerId();
            for (VoicePlayerInfo player : connection.getPlayers()) {
                if (player.getPlayerId().equals(playerId)) return player.getPlayerNick();
            }
            return ((PlayerSourceInfo) source).getPlayerInfo().getPlayerNick();
        }
        return connection.translate(line.getTranslation());
    }

    private static ResourceLocation icon(String name) {
        return new ResourceLocation("plasmovoice", "textures/icons/" + name + ".png");
    }
}
