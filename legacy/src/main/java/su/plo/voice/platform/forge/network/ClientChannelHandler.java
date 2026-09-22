package su.plo.voice.platform.forge.network;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayClient;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.platform.forge.client.connection.ClientConnection;

@SideOnly(Side.CLIENT)
public final class ClientChannelHandler {
    private final VoiceChannel channel;
    private ClientConnection connection;
    ClientChannelHandler(VoiceChannel channel) {
        this.channel = channel;
    }

    @SubscribeEvent
    public void receive(FMLNetworkEvent.ClientCustomPacketEvent event) {
        NetworkManager connection = event.manager;
        INetHandlerPlayClient handler = event.handler;
        try {
            VoiceChannel.readPayload(event.packet, PacketDirection.CLIENT).ifPresent(packet ->
                    Minecraft.getMinecraft().func_152344_a(() -> {
                        if (!connection.isChannelOpen() || Minecraft.getMinecraft().getNetHandler() != handler) {
                            return;
                        }
                        try {
                            if (this.connection == null || this.connection.getConnection() != connection) {
                                ClientConnection clientConnection = new ClientConnection(channel, connection);
                                clientConnection.generateKeyPair();
                                this.connection = clientConnection;

                                channel.logger().info("Voice client connection initialized");
                            }

                            channel.deliverToClient(connection, packet);
                            this.connection.handle(packet);
                        } catch (Exception e) {
                            channel.logger().warn("Failed to handle clientbound voice packet", e);
                        }
                    }));
        } catch (Exception e) {
            channel.logger().debug("Failed to decode clientbound voice packet", e);
        }
    }
}
