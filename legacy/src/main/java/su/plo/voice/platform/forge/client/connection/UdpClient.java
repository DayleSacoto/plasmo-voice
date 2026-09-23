package su.plo.voice.platform.forge.client.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.UUID;

import com.google.common.io.ByteStreams;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.udp.PacketUdp;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.bothbound.PingPacket;

@SideOnly(Side.CLIENT)
public final class UdpClient implements AutoCloseable {
    private final Logger logger;
    private final UUID secret;
    private final String host;
    private final int port;
    private final Thread worker;
    private volatile DatagramSocket socket;
    private final ClientConnectionState.UdpState state;
    private volatile boolean closed;

    public UdpClient(Logger logger, UUID secret, String host, int port, ClientConnectionState.UdpState state) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid UDP remote endpoint");
        }
        this.logger = logger;
        this.secret = secret;
        this.host = host;
        this.port = port;
        this.state = java.util.Objects.requireNonNull(state);
        worker = new Thread(this::run, "plasmo-voice-udp-client");
        worker.setDaemon(true);
    }

    public void start() {
        worker.start();
    }

    public InetSocketAddress getRemoteAddress() {
        return state.getRemoteAddress();
    }

    public boolean isUdpConfirmed() {
        return state.isConfirmed();
    }

    private void run() {
        try (DatagramSocket endpoint = new DatagramSocket(null)) {
            socket = endpoint;
            if (closed) return;
            InetSocketAddress remote = new InetSocketAddress(host, port);
            if (remote.isUnresolved() || remote.getAddress().isAnyLocalAddress()) {
                throw new IllegalArgumentException("UDP remote address is unresolved or wildcard");
            }
            endpoint.connect(remote);
            state.opened(remote);
            endpoint.setSoTimeout(100);
            logger.info("UDP client endpoint opened for {} (bootstrap only)", remote);
            long keepAlive = System.currentTimeMillis();
            long lastAttempt = 0L;
            byte[] buffer = new byte[65507];
            while (!closed) {
                long now = System.currentTimeMillis();
                if (now - keepAlive > 30_000L) {
                    logger.info("UDP bootstrap timed out");
                    break;
                }
                if (!state.isConfirmed() && now - lastAttempt >= 1000L) {
                    send(endpoint, new PingPacket(remote.getHostString(), remote.getPort()));
                    lastAttempt = now;
                }
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    endpoint.receive(datagram);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                try {
                    PacketUdp packet = PacketUdpCodec.decodeThrowing(
                            ByteStreams.newDataInput(Arrays.copyOf(datagram.getData(), datagram.getLength())),
                            PacketDirection.CLIENT);
                    if (!secret.equals(packet.getSecret()) || packet.getPacketClass() != PingPacket.class) continue;
                    packet.getPacketUntyped();
                    keepAlive = System.currentTimeMillis();
                    if (!state.isConfirmed()) logger.info("UDP ping received; bidirectional bootstrap confirmed");
                    state.confirm();
                } catch (IOException | IllegalArgumentException | IllegalStateException ignored) {
                    continue;
                }
                send(endpoint, new PingPacket());
            }
        } catch (Exception e) {
            if (!closed) logger.warn("UDP client stopped unexpectedly", e);
        } finally {
            state.close();
            closed = true;
            logger.info("UDP client endpoint closed");
        }
    }

    private void send(DatagramSocket endpoint, PingPacket ping) throws IOException {
        byte[] data = PacketUdpCodec.encodeThrowing(ping, secret);
        endpoint.send(new DatagramPacket(data, data.length));
    }

    /**
     * Never blocks: callers are on the client game thread. The state is closed first so a worker still
     * resolving the address cannot revive it; closing the socket wakes a blocked receive.
     */
    @Override
    public void close() {
        state.close();
        closed = true;
        DatagramSocket endpoint = socket;
        if (endpoint != null) endpoint.close();
    }
}
