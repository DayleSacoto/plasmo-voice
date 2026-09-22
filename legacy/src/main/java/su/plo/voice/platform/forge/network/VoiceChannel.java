package su.plo.voice.platform.forge.network;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import com.google.common.io.ByteStreams;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.Logger;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.tcp.PacketTcpCodec;

public final class VoiceChannel {
    public static final String NAME = "plasmo:voice/v2";

    private final Logger logger;
    private final FMLEventChannel channel;
    private final ServerChannelHandler serverHandler;
    private volatile BiConsumer<EntityPlayerMP, Packet<?>> serverListener = (player, packet) -> {};
    private volatile BiConsumer<NetworkManager, Packet<?>> clientListener = (connection, packet) -> {};

    public VoiceChannel(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(NAME);
        serverHandler = new ServerChannelHandler(this);
        channel.register(serverHandler);
        FMLCommonHandler.instance().bus().register(serverHandler);
        logger.info("Voice channel registered: {}; server handler registered; codec: {}",
                NAME, PacketTcpCodec.class.getName());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientChannelHandler clientHandler = new ClientChannelHandler(this);
            channel.register(clientHandler);
            FMLCommonHandler.instance().bus().register(clientHandler);
            logger.info("Voice client handler registered: {}", NAME);
        }
    }

    public void setServerListener(BiConsumer<EntityPlayerMP, Packet<?>> listener) {
        serverListener = Objects.requireNonNull(listener);
    }

    public void setClientListener(BiConsumer<NetworkManager, Packet<?>> listener) {
        clientListener = Objects.requireNonNull(listener);
    }

    /** Call on the client game thread while connected. */
    @SideOnly(Side.CLIENT)
    public void sendToServer(Packet<?> packet) {
        channel.sendToServer(createPayload(packet, PacketDirection.SERVER));
    }

    /** Call on the server game thread for a connected player. */
    public void sendToPlayer(EntityPlayerMP player, Packet<?> packet) {
        channel.sendTo(createPayload(packet, PacketDirection.CLIENT), Objects.requireNonNull(player));
    }

    public void clearServer() {
        serverHandler.clear();
    }

    void deliverToServer(EntityPlayerMP player, Packet<?> packet) {
        serverListener.accept(player, packet);
    }

    void deliverToClient(NetworkManager connection, Packet<?> packet) {
        clientListener.accept(connection, packet);
    }

    Logger logger() {
        return logger;
    }

    static FMLProxyPacket createPayload(Packet<?> packet, PacketDirection destination) {
        byte[] data = PacketTcpCodec.encode(Objects.requireNonNull(packet));
        if (data == null) throw new IllegalArgumentException("Unregistered Plasmo Voice packet");
        checkLength(data.length, destination);
        // FMLProxyPacket sends payload.array(), so use an exact-sized, unsliced heap buffer.
        return new FMLProxyPacket(Unpooled.wrappedBuffer(data), NAME);
    }

    static Optional<Packet<?>> readPayload(FMLProxyPacket packet, PacketDirection destination) throws IOException {
        if (!NAME.equals(packet.channel())) return Optional.empty();
        ByteBuf buffer = packet.payload();
        checkLength(buffer.readableBytes(), destination);
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
        return PacketTcpCodec.decode(ByteStreams.newDataInput(data), destination).map(decoded -> decoded);
    }

    private static void checkLength(int length, PacketDirection destination) {
        int maximum;
        if (destination == PacketDirection.SERVER) maximum = 32766;
        else if (destination == PacketDirection.CLIENT) maximum = 0x1FFF9A;
        else throw new IllegalArgumentException("A concrete packet destination is required");
        if (length < 1 || length > maximum) {
            throw new IllegalArgumentException("Invalid custom payload size: " + length);
        }
    }
}
