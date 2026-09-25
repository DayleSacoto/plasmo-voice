package su.plo.voice.platform.forge.network;

import java.util.concurrent.ArrayBlockingQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayServer;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.proto.packets.PacketDirection;

public final class ServerChannelHandler {
    private final VoiceChannel channel;
    private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(1024);

    ServerChannelHandler(VoiceChannel channel) {
        this.channel = channel;
    }

    @SubscribeEvent
    public void receive(FMLNetworkEvent.ServerCustomPacketEvent event) {
        NetworkManager connection = event.manager;
        INetHandlerPlayServer handler = event.handler;
        try {
            VoiceChannel.readPayload(event.packet, PacketDirection.SERVER).ifPresent(packet -> {
                if (VoiceDebug.SERVER.enabled()) {
                    VoiceDebug.SERVER.log(Category.TCP, "RX {} from {}", packet.getClass().getSimpleName(), sender(handler));
                }
                if (!pending.offer(() -> {
                    if (connection.isChannelOpen() && connection.getNetHandler() == handler
                            && handler instanceof NetHandlerPlayServer) {
                        channel.deliverToServer(((NetHandlerPlayServer) handler).playerEntity, packet);
                    }
                })) {
                    channel.logger().debug("Voice server packet queue is full");
                    VoiceDebug.SERVER.warn(Category.TCP, "packet queue full; dropped {} from {}",
                            packet.getClass().getSimpleName(), sender(handler));
                }
            });
        } catch (Exception e) {
            channel.logger().debug("Failed to decode serverbound voice packet", e);
            VoiceDebug.SERVER.error(Category.TCP, "failed to decode a serverbound voice packet from {}", e, sender(handler));
        }
    }

    private static String sender(INetHandlerPlayServer handler) {
        return handler instanceof NetHandlerPlayServer && ((NetHandlerPlayServer) handler).playerEntity != null
                ? ((NetHandlerPlayServer) handler).playerEntity.getCommandSenderName() : "unknown";
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (int i = 0; i < 256; i++) {
            Runnable task = pending.poll();
            if (task == null) break;
            try {
                task.run();
            } catch (Exception e) {
                channel.logger().warn("Failed to handle serverbound voice packet", e);
            }
        }
    }

    void clear() {
        pending.clear();
    }
}
