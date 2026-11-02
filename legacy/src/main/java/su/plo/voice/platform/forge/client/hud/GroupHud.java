package su.plo.voice.platform.forge.client.hud;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.opengl.GL11;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.ClientVoiceSources;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.platform.forge.client.gui.GroupHudEditScreen;
import su.plo.voice.platform.forge.client.groups.ClientGroups;
import su.plo.voice.platform.forge.client.lang.VoiceLang;
import su.plo.voice.platform.forge.network.groups.GroupsState;

/**
 * The Group HUD: a panel with the online members of the current group in the Group Overlay style ([head] name, head
 * or name); a member speaking through group voice gets a white outline around the head, or a white underline under
 * the name in the name style. Drawn after the vanilla HUD like {@link VoiceHud}; the box measures and draws through
 * {@link GroupHudModel}, which the position editor shares. Style and background opacity are the Group Overlay's own
 * settings, independent of the generic overlay.
 */
@SideOnly(Side.CLIENT)
public final class GroupHud {
    private final ClientState state;

    public GroupHud(ClientState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !state.isGroupHudEnabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ClientConnectionState connection = state.getConnection();
        if (mc.thePlayer == null || mc.gameSettings.hideGUI || connection == null || !connection.isConnected()
                || connection.getConfig() == null || connection.getUdp() == null) return;
        if (!ClientGroups.available(connection.getConfig().getPacket().getActivations())) return;
        GroupsState.CurrentGroup group = ClientGroups.get().current();
        if (group == null) return;
        if (mc.currentScreen instanceof GroupHudEditScreen) return;

        List<GroupHudModel.Entry> entries = liveEntries(mc, state, group);

        GroupHudPosition.Anchor anchor = state.getGroupHudAnchor();
        GroupHudModel.Layout layout = layout(mc.fontRenderer, entries, anchor, state.getGroupHudStyle());
        if (layout.rows.isEmpty()) return;
        int width = event.resolution.getScaledWidth();
        int height = event.resolution.getScaledHeight();
        GroupHudPosition.Point origin = GroupHudPosition.origin(anchor, state.getGroupHudOffsetX(),
                state.getGroupHudOffsetY(), layout.width, layout.height, width, height);

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        draw(mc, layout, origin.x, origin.y, state.getGroupHudBackgroundOpacity());
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glPopMatrix();
    }

    /** The online members of the real group with the speaking state of the voice connection; client thread. */
    public static List<GroupHudModel.Entry> liveEntries(Minecraft mc, ClientState state, GroupsState.CurrentGroup group) {
        ClientConnectionState connection = state.getConnection();
        UUID selfId = mc.thePlayer.getUniqueID();
        Set<UUID> speaking = java.util.Collections.emptySet();
        if (connection != null) {
            selfId = connection.localPlayerId(mc.thePlayer.getCommandSenderName(), selfId);
            ClientVoiceSources sources = connection.getSources();
            if (sources != null) speaking = sources.speakingOn(ClientGroups.LINE_ID);
        }
        boolean transmitting = state.getActiveActivations().contains(ClientGroups.ACTIVATION_ID);
        return GroupHudModel.entries(group.members, speaking, selfId, transmitting);
    }

    /** The shared measurement: the box of this group for an anchor (right anchors mirror, bottom ones reverse). */
    public static GroupHudModel.Layout layout(FontRenderer font, List<GroupHudModel.Entry> entries,
                                              GroupHudPosition.Anchor anchor, HudOptions.OverlayStyle style) {
        return GroupHudModel.layout(entries, anchor.isRight(), anchor.isBottom(), style, font::getStringWidth,
                count -> VoiceLang.format("gui.plasmovoice.overlay.group_more", count));
    }

    /**
     * Draws a layout with its top left corner at (x, y): the background panel at {@code opacityPercent} (none at 0),
     * then per row the head (white outline while speaking through group voice) and/or the name (white underline while
     * speaking when there is no head). Heads, names and speaking marks are always fully opaque. Also used by the
     * position editor.
     */
    public static void draw(Minecraft mc, GroupHudModel.Layout layout, int x, int y, int opacityPercent) {
        if (layout.rows.isEmpty()) return;
        FontRenderer font = mc.fontRenderer;
        if (GroupHudModel.clampOpacity(opacityPercent) > 0) {
            Gui.drawRect(x, y, x + layout.width, y + layout.height, GroupHudModel.backgroundColor(opacityPercent));
        }
        boolean heads = layout.style.hasSkin();
        boolean names = layout.style.hasName();
        for (GroupHudModel.Row row : layout.rows) {
            if (row.kind == GroupHudModel.Kind.MORE) {
                font.drawString(row.text, x + row.textX, y + row.textY, 0xFFFFFF);
                continue;
            }
            if (heads) {
                PlayerHeads.draw(mc, row.memberId, x + row.headX, y + row.headY, GroupHudModel.HEAD_SIZE,
                        PlayerHeads.Style.of(true, row.speaking));
            }
            if (names) font.drawString(row.text, x + row.textX, y + row.textY, 0xFFFFFF);
            if (row.underlineY >= 0) {
                int left = x + row.textX;
                Gui.drawRect(left, y + row.underlineY, left + font.getStringWidth(row.text), y + row.underlineY + 1,
                        PlayerHeads.OUTLINE_COLOR);
            }
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
