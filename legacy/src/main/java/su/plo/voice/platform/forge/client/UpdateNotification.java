package su.plo.voice.platform.forge.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.update.UpdateChecker;

/** Legacy extension: shows a found backport update once per game session, in chat, when the player is in a world. */
@SideOnly(Side.CLIENT)
public final class UpdateNotification {
    /** Set by the checker thread; taken by the client thread when shown. */
    private volatile UpdateChecker.Release pending;

    static void register(ClientState state) {
        UpdateNotification notification = new UpdateNotification();
        FMLCommonHandler.instance().bus().register(notification);
        UpdateChecker.INSTANCE.start(state.isCheckForUpdates(), release -> notification.pending = release);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        UpdateChecker.Release release = pending;
        if (event.phase != TickEvent.Phase.END || release == null) return;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        pending = null;
        player.addChatMessage(message(I18n.format("message.plasmovoice.update_available", release.label(),
                UpdateChecker.INSTANCE.getCurrent().label()), release.url()));
    }

    /** The text followed by the release page as a link; opening it goes through the vanilla link confirmation. */
    static IChatComponent message(String text, String url) {
        IChatComponent link = new ChatComponentText(url);
        link.getChatStyle().setColor(EnumChatFormatting.AQUA).setUnderlined(true)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        return new ChatComponentText(text + " ").appendSibling(link);
    }
}
