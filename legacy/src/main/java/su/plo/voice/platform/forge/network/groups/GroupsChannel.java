package su.plo.voice.platform.forge.network.groups;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.INetHandlerPlayServer;
import org.apache.logging.log4j.Logger;

/**
 * FML transport for the Groups GUI ({@link GroupsProtocol#CHANNEL}). Serverbound actions are queued from netty and
 * delivered on the server thread (END of the server tick, like {@code VoiceChannel}); clientbound messages are
 * delivered on the client game thread.
 *
 * <p>Groups is an optional client capability: a client announces it with a {@link GroupsHello} before its voice
 * handshake reply, recorded here on the netty thread per connection ({@link #capabilities()}). Nothing is ever sent
 * to a player whose current connection did not announce a supported protocol, and actions from such a connection
 * are dropped, so clients from before Groups never see this channel.
 */
public final class GroupsChannel {
    private static final int MAX_CLIENTBOUND = 0x1FFF9A;

    private static volatile GroupsChannel instance;

    private final Logger logger;
    private final FMLEventChannel channel;
    private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(256);
    /** Actions one connection may have waiting; above the service's 8/s limit, so honest clients never hit it. */
    private final ConnectionQuota<NetworkManager> quota = new ConnectionQuota<>(16);
    private final GroupsCapabilities<NetworkManager> capabilities = new GroupsCapabilities<>();
    private volatile BiConsumer<EntityPlayerMP, Integer> helloListener = (player, protocol) -> {};
    private volatile BiConsumer<EntityPlayerMP, GroupsAction> serverListener = (player, action) -> {};
    private volatile Consumer<Object> clientListener = message -> {};

    public GroupsChannel(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(GroupsProtocol.CHANNEL);
        Server server = new Server();
        channel.register(server);
        FMLCommonHandler.instance().bus().register(server);
        if (FMLCommonHandler.instance().getSide().isClient()) channel.register(new Client());
        instance = this;
    }

    /** The channel created during mod init, or null before that (and in unit tests). */
    public static GroupsChannel get() {
        return instance;
    }

    /** Called on the server thread with an already decoded, still untrusted action. */
    public void setServerListener(BiConsumer<EntityPlayerMP, GroupsAction> listener) {
        serverListener = Objects.requireNonNull(listener);
    }

    /** Called on the server thread when a connection became Groups-capable, with the agreed protocol. */
    public void setHelloListener(BiConsumer<EntityPlayerMP, Integer> listener) {
        helloListener = Objects.requireNonNull(listener);
    }

    /** Session-only Groups support of the connected players; the one check every Groups send goes through. */
    public GroupsCapabilities<NetworkManager> capabilities() {
        return capabilities;
    }

    /** Server thread, on logout: that connection's capability is gone. */
    public void disconnected(EntityPlayerMP player) {
        NetworkManager connection = player.playerNetServerHandler == null ? null : player.playerNetServerHandler.netManager;
        capabilities.remove(player.getUniqueID(), connection);
    }

    /** Called on the client thread with a {@link GroupsState} or {@link GroupsResult}. */
    public void setClientListener(Consumer<Object> listener) {
        clientListener = Objects.requireNonNull(listener);
    }

    /** Server thread. */
    public void sendState(EntityPlayerMP player, byte[] encodedState) {
        send(player, encodedState);
    }

    /** Server thread. */
    public void sendResult(EntityPlayerMP player, GroupsResult result) {
        send(player, GroupsProtocol.encode(result));
    }

    private void send(EntityPlayerMP player, byte[] data) {
        if (!capabilities.supports(player.getUniqueID())) {
            logger.debug("{} did not announce Groups support; Groups payload not sent", player.getCommandSenderName());
            return;
        }
        if (data.length > MAX_CLIENTBOUND) {
            logger.warn("Groups payload too large ({} bytes); not sent", data.length);
            return;
        }
        channel.sendTo(new FMLProxyPacket(Unpooled.wrappedBuffer(data), GroupsProtocol.CHANNEL), player);
    }

    /** Client game thread, while connected. */
    @SideOnly(Side.CLIENT)
    public void sendHello() {
        byte[] data = GroupsProtocol.encode(new GroupsHello(GroupsCapabilities.MIN_PROTOCOL, GroupsCapabilities.MAX_PROTOCOL));
        channel.sendToServer(new FMLProxyPacket(Unpooled.wrappedBuffer(data), GroupsProtocol.CHANNEL));
    }

    /** Client game thread, while connected. */
    @SideOnly(Side.CLIENT)
    public void sendToServer(GroupsAction action) {
        byte[] data = GroupsProtocol.encode(action);
        if (data.length > GroupsProtocol.MAX_SERVERBOUND) throw new IllegalArgumentException("Groups action too large");
        channel.sendToServer(new FMLProxyPacket(Unpooled.wrappedBuffer(data), GroupsProtocol.CHANNEL));
    }

    public void clearServer() {
        pending.clear();
        quota.clear();
        capabilities.clear();
    }

    private static byte[] bytes(FMLProxyPacket packet) {
        ByteBuf buffer = packet.payload();
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
        return data;
    }

    public final class Server {
        @SubscribeEvent
        public void receive(FMLNetworkEvent.ServerCustomPacketEvent event) {
            if (!GroupsProtocol.CHANNEL.equals(event.packet.channel())) return;
            NetworkManager connection = event.manager;
            INetHandlerPlayServer handler = event.handler;
            if (!(handler instanceof NetHandlerPlayServer)) return;
            EntityPlayerMP player = ((NetHandlerPlayServer) handler).playerEntity;
            Object decoded;
            try {
                byte[] data = bytes(event.packet);
                if (data.length > GroupsProtocol.MAX_SERVERBOUND) throw new IOException("Groups action too large");
                decoded = GroupsProtocol.decode(data);
                if (!(decoded instanceof GroupsAction) && !(decoded instanceof GroupsHello)) {
                    throw new IOException("Unexpected serverbound groups payload");
                }
            } catch (Exception e) {
                logger.debug("Dropped an invalid groups action", e);
                return;
            }
            if (decoded instanceof GroupsHello) {
                hello(connection, handler, player, (GroupsHello) decoded);
                return;
            }
            GroupsAction action = (GroupsAction) decoded;
            if (!capabilities.supports(player.getUniqueID())) {
                logger.debug("Groups action from a connection without Groups support; dropped {}", action);
                return;
            }
            if (!quota.tryAcquire(connection)) {
                logger.debug("Too many queued groups actions from one connection; dropped {}", action);
                return;
            }
            if (!pending.offer(() -> {
                try {
                    if (connection.isChannelOpen() && connection.getNetHandler() == handler) {
                        serverListener.accept(player, action);
                    }
                } finally {
                    quota.release(connection);
                }
            })) {
                quota.release(connection);
                logger.debug("Groups action queue is full; dropped {}", action);
            }
        }

        /**
         * Netty thread: recorded at once, so the voice PlayerInfoPacket that follows on this connection always finds
         * it; the server thread hears about it on the next tick.
         */
        private void hello(NetworkManager connection, INetHandlerPlayServer handler, EntityPlayerMP player,
                           GroupsHello hello) {
            int protocol = capabilities.announce(player.getUniqueID(), connection, hello.minProtocol, hello.maxProtocol);
            if (protocol == 0) {
                if (!capabilities.supports(player.getUniqueID())) {
                    logger.debug("{} announced no Groups protocol this server speaks: {}",
                            player.getCommandSenderName(), hello);
                }
                return;
            }
            if (!pending.offer(() -> {
                if (connection.isChannelOpen() && connection.getNetHandler() == handler) {
                    helloListener.accept(player, protocol);
                }
            })) {
                logger.warn("Groups queue is full; Groups activation of {} was dropped", player.getCommandSenderName());
            }
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            for (int i = 0; i < 64; i++) {
                Runnable task = pending.poll();
                if (task == null) break;
                try {
                    task.run();
                } catch (Exception e) {
                    logger.warn("Failed to handle a groups action", e);
                }
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public final class Client {
        @SubscribeEvent
        public void receive(FMLNetworkEvent.ClientCustomPacketEvent event) {
            if (!GroupsProtocol.CHANNEL.equals(event.packet.channel())) return;
            NetworkManager connection = event.manager;
            INetHandlerPlayClient handler = event.handler;
            Object decoded;
            try {
                decoded = GroupsProtocol.decode(bytes(event.packet));
                if (decoded instanceof GroupsAction || decoded instanceof GroupsHello) {
                    throw new IOException("Unexpected clientbound groups payload");
                }
            } catch (Exception e) {
                logger.debug("Dropped an invalid groups message", e);
                return;
            }
            Minecraft.getMinecraft().func_152344_a(() -> {
                if (!connection.isChannelOpen() || Minecraft.getMinecraft().getNetHandler() != handler) return;
                try {
                    clientListener.accept(decoded);
                } catch (Exception e) {
                    logger.warn("Failed to handle a groups message", e);
                }
            });
        }
    }
}
