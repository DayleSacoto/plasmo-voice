package su.plo.voice.platform.forge.server.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.io.ByteStreams;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.Logger;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.udp.PacketUdp;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.bothbound.PingPacket;

public final class UdpServer implements AutoCloseable {
    private static final long KEEP_ALIVE_TICK_MS = 100L;
    private final Logger logger;
    private final String bindHost;
    private final int bindPort;
    private final String advertisedHost;
    private final int advertisedPort;
    private final int keepAliveTimeoutMs;
    private final Map<UUID, Session> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Session> bySecret = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile DatagramSocket socket;
    private volatile InetSocketAddress boundAddress;
    private Thread worker;

    public UdpServer(Logger logger, String bindHost, int bindPort, String advertisedHost, int advertisedPort,
                     int keepAliveTimeoutMs) {
        if (bindHost == null || bindHost.isEmpty() || advertisedHost == null || advertisedHost.isEmpty()
                || advertisedHost.equals("::")
                || bindPort < 0 || bindPort > 65535 || advertisedPort < 0 || advertisedPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP bind/advertised endpoint");
        }
        this.logger = logger;
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        this.advertisedHost = advertisedHost;
        this.advertisedPort = advertisedPort;
        this.keepAliveTimeoutMs = keepAliveTimeoutMs;
    }

    public static UdpServer create(Logger logger, ServerSettings settings, int minecraftPort) {
        return new UdpServer(logger, settings.getHostIp(), settings.bindPort(minecraftPort),
                settings.advertisedIp(), settings.advertisedPort(), settings.getKeepAliveTimeoutMs());
    }

    public void start() {
        if (worker != null || closed) throw new IllegalStateException("UDP server cannot be reused");
        worker = new Thread(this::run, "plasmo-voice-udp-server");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized Session createSession(UUID playerId) {
        if (closed) throw new IllegalStateException("UDP server is closed");
        return byPlayer.computeIfAbsent(playerId, id -> {
            Session session = new Session(id, UUID.randomUUID());
            bySecret.put(session.secret, session);
            logger.info("UDP session created for player {}; awaiting initial ping", id);
            return session;
        });
    }

    Session getSession(UUID secret) {
        return bySecret.get(secret);
    }

    public ConnectionPacket connectionPacket(Session session) {
        InetSocketAddress address = boundAddress;
        if (closed || address == null || !session.active || bySecret.get(session.secret) != session) return null;
        return new ConnectionPacket(session.secret, advertisedHost,
                advertisedPort == 0 ? address.getPort() : advertisedPort);
    }

    public void removeSession(UUID playerId) {
        Session session = byPlayer.get(playerId);
        if (session != null) removeSession(session);
    }

    private void removeSession(Session session) {
        synchronized (session) {
            session.active = false;
            bySecret.remove(session.secret, session);
            byPlayer.remove(session.playerId, session);
        }
    }

    private void run() {
        try (DatagramSocket endpoint = new DatagramSocket(null)) {
            socket = endpoint;
            if (closed) return;
            endpoint.bind(new InetSocketAddress(bindHost, bindPort));
            endpoint.setSoTimeout(100);
            boundAddress = (InetSocketAddress) endpoint.getLocalSocketAddress();
            logger.info("UDP endpoint bound: {}; advertised: {}:{} (bootstrap only)", boundAddress,
                    advertisedHost, advertisedPort == 0 ? boundAddress.getPort() : advertisedPort);
            byte[] buffer = new byte[65507];
            long lastKeepAlive = 0L;
            while (!closed) {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    endpoint.receive(datagram);
                    receive(datagram);
                } catch (SocketTimeoutException ignored) {
                }
                // Upstream NettyUdpKeepAlive ticks every 100 ms instead of after every datagram.
                long now = System.currentTimeMillis();
                if (now - lastKeepAlive >= KEEP_ALIVE_TICK_MS) {
                    lastKeepAlive = now;
                    keepAlive(endpoint, now);
                }
            }
        } catch (Exception e) {
            if (!closed) logger.warn("UDP server stopped unexpectedly", e);
        } finally {
            invalidateSessions();
            logger.info("UDP server endpoint closed");
        }
    }

    private void receive(DatagramPacket datagram) {
        try {
            PacketUdp packet = PacketUdpCodec.decodeThrowing(
                    ByteStreams.newDataInput(Arrays.copyOf(datagram.getData(), datagram.getLength())),
                    PacketDirection.SERVER);
            Session session = bySecret.get(packet.getSecret());
            if (session == null || packet.getPacketClass() != PingPacket.class) return;
            PingPacket ping = (PingPacket) packet.getPacketUntyped();
            synchronized (session) {
                if (!session.active) return;
                session.remoteAddress = (InetSocketAddress) datagram.getSocketAddress();
                session.lastReceived = System.currentTimeMillis();
                if (!session.authenticated) {
                    if (ping.getServerIp() != null) {
                        session.connectionAddress = InetSocketAddress.createUnresolved(
                                ping.getServerIp(), ping.getServerPort());
                    }
                    session.authenticated = true;
                    logger.info("Initial UDP ping authenticated for player {}; voice configuration pending", session.playerId);
                } else if (!session.replyConfirmed && session.sentKeepAlive != 0L) {
                    session.replyConfirmed = true;
                    logger.info("UDP ping reply received for player {}; bootstrap exchange complete", session.playerId);
                }
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException ignored) {
            // Untrusted datagrams are dropped without a per-packet stack trace.
        }
    }

    private void keepAlive(DatagramSocket endpoint, long now) throws IOException {
        for (Session session : bySecret.values()) {
            synchronized (session) {
                if (!session.active || !session.authenticated) continue;
                if (now - session.lastReceived > keepAliveTimeoutMs) {
                    removeSession(session);
                    logger.info("UDP session timed out for player {}", session.playerId);
                } else if (now - session.sentKeepAlive >= 1_000L) {
                    byte[] data = PacketUdpCodec.encodeThrowing(new PingPacket(), session.secret);
                    endpoint.send(new DatagramPacket(data, data.length, session.remoteAddress));
                    session.sentKeepAlive = now + ThreadLocalRandom.current().nextInt(1500, 3000);
                }
            }
        }
    }

    @Override
    public void close() {
        invalidateSessions();
        DatagramSocket endpoint = socket;
        if (endpoint != null) endpoint.close();
        if (worker != null) {
            try {
                worker.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) logger.warn("UDP worker is still finishing address resolution");
            else logger.info("UDP server worker stopped; sessions cleared");
        }
    }

    private synchronized void invalidateSessions() {
        closed = true;
        boundAddress = null;
        byPlayer.values().forEach(this::removeSession);
    }

    @Getter
    @RequiredArgsConstructor
    public static final class Session {
        private final UUID playerId;
        private final UUID secret;
        private volatile boolean active = true;
        private volatile boolean authenticated;
        private boolean replyConfirmed;
        private InetSocketAddress remoteAddress;
        private InetSocketAddress connectionAddress;
        private long lastReceived;
        private long sentKeepAlive;
    }
}
