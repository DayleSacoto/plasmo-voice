package su.plo.voice.platform.forge.server.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.io.ByteStreams;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.Value;
import org.apache.logging.log4j.Logger;
import su.plo.voice.platform.forge.debug.DebugInterval;
import su.plo.voice.platform.forge.debug.SilenceMonitor;
import su.plo.voice.platform.forge.debug.UdpStats;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.platform.forge.debug.WorkerWatch;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.udp.PacketUdp;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.bothbound.PingPacket;
import su.plo.voice.proto.packets.udp.clientbound.SelfAudioInfoPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

public final class UdpServer implements AutoCloseable {
    private static final long KEEP_ALIVE_TICK_MS = 100L;
    /** Pause after a failed receive so a persistent socket error cannot spin the worker. */
    private static final long RECEIVE_ERROR_BACKOFF_MS = 10L;
    private static final VoiceDebug DEBUG = VoiceDebug.SERVER;
    private static final AtomicLong GENERATIONS = new AtomicLong();
    // Diagnostics of the UDP worker, used only while debug logging is enabled.
    private final DebugInterval snapshots = new DebugInterval(5_000L);
    private final WorkerWatch watch = new WorkerWatch("UDP server", 2_000L);
    private long unknownSecret;
    private long malformed;
    private long receiveFailures;
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
    private volatile VoiceActivation proximityActivation;
    /** Upstream voice.max_extra_audio_broadcast_distance. */
    @Setter
    private volatile int maxExtraBroadcastDistance = 16;
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
        UdpServer server = new UdpServer(logger, settings.getHostIp(), settings.bindPort(minecraftPort),
                settings.advertisedIp(), settings.advertisedPort(), settings.getKeepAliveTimeoutMs());
        server.setMaxExtraBroadcastDistance(settings.getMaxExtraAudioBroadcastDistance());
        return server;
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
            session.generation = GENERATIONS.incrementAndGet();
            bySecret.put(session.secret, session);
            logger.debug("UDP session created for player {}; awaiting initial ping", id);
            if (DEBUG.enabled()) {
                DEBUG.log(Category.UDP, "session created: uuid={}, generation={}, awaiting bootstrap ping, thread={}",
                        id, session.generation, VoiceDebug.thread());
            }
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
            if (DEBUG.enabled() && session.active) {
                DEBUG.log(Category.UDP, "session removed: {}, thread={}", session.describe(), VoiceDebug.thread());
            }
            session.active = false;
            bySecret.remove(session.secret, session);
            byPlayer.remove(session.playerId, session);
        }
    }

    private void run() {
        if (DEBUG.enabled()) DEBUG.log(Category.THREAD, "UDP server worker started: thread={}", VoiceDebug.thread());
        Throwable failure = null;
        try (DatagramSocket endpoint = new DatagramSocket(null)) {
            socket = endpoint;
            if (closed) return;
            endpoint.bind(new InetSocketAddress(bindHost, bindPort));
            endpoint.setSoTimeout(100);
            boundAddress = (InetSocketAddress) endpoint.getLocalSocketAddress();
            logger.info("UDP server is started on {}; advertised: {}:{}", boundAddress,
                    advertisedHost, advertisedPort == 0 ? boundAddress.getPort() : advertisedPort);
            if (DEBUG.enabled()) {
                DEBUG.log(Category.UDP, "UDP server bound: configured={}:{}, bound={}, advertised={}:{}, keepAliveTimeout={}ms",
                        bindHost, bindPort, boundAddress, advertisedHost,
                        advertisedPort == 0 ? boundAddress.getPort() : advertisedPort, keepAliveTimeoutMs);
            }
            byte[] buffer = new byte[65507];
            long lastKeepAlive = 0L;
            while (!closed) {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    endpoint.receive(datagram);
                    receive(endpoint, datagram);
                } catch (SocketTimeoutException ignored) {
                } catch (SocketException e) {
                    // Upstream NioDatagramChannel keeps reading after a SocketException; only close ends the worker.
                    if (closed || endpoint.isClosed()) throw e;
                    receiveFailed(e);
                    Thread.sleep(RECEIVE_ERROR_BACKOFF_MS);
                }
                // Upstream NettyUdpKeepAlive ticks every 100 ms instead of after every datagram.
                long now = System.currentTimeMillis();
                if (DEBUG.enabled()) watch.beat(now);
                if (now - lastKeepAlive >= KEEP_ALIVE_TICK_MS) {
                    lastKeepAlive = now;
                    keepAlive(endpoint, now);
                    if (DEBUG.enabled()) diagnostics(now);
                }
            }
        } catch (Exception e) {
            failure = e;
            if (!closed) logger.warn("UDP server stopped unexpectedly", e);
        } catch (Error e) {
            failure = e;
            throw e;
        } finally {
            boolean closedByServer = closed;
            invalidateSessions();
            logger.info("UDP server endpoint closed");
            if (DEBUG.enabled()) {
                if (failure != null && !closedByServer) {
                    DEBUG.error(Category.THREAD, "UDP server worker stopped unexpectedly: unknownSecret={}, malformed={}, "
                            + "receiveFailures={}", failure, unknownSecret, malformed, receiveFailures);
                } else {
                    DEBUG.log(Category.THREAD, "UDP server worker stopped: closed by server, unknownSecret={}, malformed={}",
                            unknownSecret, malformed);
                }
            }
        }
    }

    private void receive(DatagramSocket endpoint, DatagramPacket datagram) {
        try {
            PacketUdp packet = PacketUdpCodec.decodeThrowing(
                    ByteStreams.newDataInput(Arrays.copyOf(datagram.getData(), datagram.getLength())),
                    PacketDirection.SERVER);
            Session session = bySecret.get(packet.getSecret());
            if (session == null) {
                if (DEBUG.enabled()) unknownSecret++;
                return;
            }
            if (packet.getPacketClass() == PlayerAudioPacket.class) {
                PlayerAudioPacket audio = (PlayerAudioPacket) packet.getPacketUntyped();
                synchronized (session) {
                    if (!session.active || !session.authenticated) {
                        if (DEBUG.enabled()) session.stats.ignored.incrementAndGet();
                        return;
                    }
                    if (DEBUG.enabled()) session.received(UdpStats.Kind.AUDIO, datagram);
                    session.remoteAddress = (InetSocketAddress) datagram.getSocketAddress();
                    session.lastReceived = System.currentTimeMillis();
                }
                routeAudio(endpoint, session, audio);
                return;
            }
            if (packet.getPacketClass() != PingPacket.class) {
                if (DEBUG.enabled()) session.stats.ignored.incrementAndGet();
                return;
            }
            PingPacket ping = (PingPacket) packet.getPacketUntyped();
            synchronized (session) {
                if (!session.active) {
                    if (DEBUG.enabled()) session.stats.ignored.incrementAndGet();
                    return;
                }
                if (DEBUG.enabled()) session.received(UdpStats.Kind.PING, datagram);
                session.remoteAddress = (InetSocketAddress) datagram.getSocketAddress();
                session.lastReceived = System.currentTimeMillis();
                if (!session.authenticated) {
                    if (ping.getServerIp() != null) {
                        session.connectionAddress = InetSocketAddress.createUnresolved(
                                ping.getServerIp(), ping.getServerPort());
                    }
                    session.authenticated = true;
                    logger.debug("Initial UDP ping authenticated for player {}", session.playerId);
                    if (DEBUG.enabled()) {
                        session.silence.reset(session.lastReceived);
                        DEBUG.log(Category.UDP, "UDP authentication success: {}, connectionAddress={}",
                                session.describe(), session.connectionAddress);
                    }
                } else if (!session.replyConfirmed && session.sentKeepAlive != 0L) {
                    session.replyConfirmed = true;
                    logger.debug("UDP ping reply received for player {}", session.playerId);
                }
                if (DEBUG.enabled()) {
                    DEBUG.log(Category.KEEPALIVE, "ping received: player={}, generation={}, remote={}, pingRx={}",
                            session.name(), session.generation, session.remoteAddress, session.stats.pingRx.get());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Untrusted datagrams are dropped without a per-packet stack trace, like upstream's decoder does.
            if (DEBUG.enabled()) malformed++;
        }
    }

    /** The first failure with its stack trace, then every 250th. */
    private void receiveFailed(SocketException e) {
        long failures = ++receiveFailures;
        logger.debug("Voice UDP receive failed", e);
        if (DEBUG.enabled() && (failures == 1 || failures % 250 == 0)) {
            DEBUG.error(Category.UDP, "UDP receive failed: receiveFailures={}", e, failures);
        }
    }

    /** Server thread, while debug logging is enabled: reports a blocked UDP worker, which cannot log itself. */
    public void checkWorker(long now) {
        watch.check(DEBUG, worker, closed, now);
    }

    public void setProximityActivation(VoiceActivation activation) {
        this.proximityActivation = activation;
    }

    /**
     * Upstream NettyUdpServerConnection (server mute, microphone mute), VoiceServerActivationManager.onPlayerSpeak
     * and ProximityServerActivationHelper, run on the UDP worker.
     */
    private void routeAudio(DatagramSocket endpoint, Session speaker, PlayerAudioPacket audio) {
        Presence presence = speaker.presence;
        VoiceActivation activation = proximityActivation;
        if (presence == null || !presence.isVoiceConnected() || presence.isServerMuted() || presence.isMicrophoneMuted()
                || activation == null || !activation.getId().equals(audio.getActivationId())) {
            if (DEBUG.enabled()) speaker.rejected(presence, activation, audio);
            return;
        }

        short distance = (short) activation.calculateAllowedDistance(audio.getDistance());
        speaker.lastDistance = distance;
        // A late frame right after the activation ended does not start it again.
        long sequenceNumber = audio.getSequenceNumber();
        long lastEnd = speaker.lastActivationEnd;
        boolean wasActive = speaker.activationActive;
        if (sequenceNumber > lastEnd || Math.abs(sequenceNumber - lastEnd) > 10) speaker.activationActive = true;
        int extra = maxExtraBroadcastDistance;
        // Audio stays encrypted: every client shares the lifecycle AES key, the server only relays it.
        SourceAudioPacket packet = new SourceAudioPacket(audio.getSequenceNumber(), speaker.sourceState,
                audio.getData(), speaker.sourceId, distance);
        int recipients = 0;
        for (Session listener : bySecret.values()) {
            if (isListener(speaker, listener, distance, extra)) {
                send(endpoint, packet, listener);
                recipients++;
            }
        }
        send(endpoint, new SelfAudioInfoPacket(speaker.sourceId, audio.getSequenceNumber(), null, distance), speaker);
        if (DEBUG.enabled()) {
            speaker.audioAccepted++;
            speaker.recipientSends += recipients;
            if (recipients == 0) speaker.noRecipient++;
            else speaker.audioForwarded++;
            if (!wasActive && speaker.activationActive) {
                DEBUG.log(Category.AUDIO, "audio stream started: player={}, generation={}, sequence={}, distance={}, recipients={}",
                        speaker.name(), speaker.generation, sequenceNumber, distance, recipients);
            }
        }
    }

    /** Upstream VoiceServerProximitySource listeners: not the speaker, voice enabled, same world, in range. */
    static boolean isListener(Session speaker, Session listener, short distance, int maxExtraDistance) {
        if (listener == speaker || !listener.active || !listener.authenticated) return false;
        Presence from = speaker.presence;
        Presence to = listener.presence;
        if (from == null || to == null || !to.isVoiceConnected() || to.isVoiceDisabled()
                || from.getDimension() != to.getDimension()) return false;
        double range = Math.min(distance + maxExtraDistance, distance * 2);
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private void send(DatagramSocket endpoint, Packet<?> packet, Session to) {
        InetSocketAddress address = to.remoteAddress; // written only by this worker
        if (address == null) return;
        try {
            byte[] data = PacketUdpCodec.encodeThrowing(packet, to.secret);
            endpoint.send(new DatagramPacket(data, data.length, address));
            if (DEBUG.enabled()) to.stats.sent(packet instanceof SourceAudioPacket ? UdpStats.Kind.AUDIO : UdpStats.Kind.OTHER,
                    System.currentTimeMillis());
        } catch (IOException e) {
            // An unreachable peer loses the datagram, like any UDP packet.
            if (DEBUG.enabled()) to.sendFailed(packet, e);
        }
    }

    /**
     * Upstream NettyUdpKeepAlive.tick. Its pings are asynchronous writes: a peer that cannot be reached loses the
     * datagram and times out on its own, the other sessions are not affected.
     */
    private void keepAlive(DatagramSocket endpoint, long now) {
        for (Session session : bySecret.values()) {
            synchronized (session) {
                if (!session.active || !session.authenticated) continue;
                if (now - session.lastReceived > keepAliveTimeoutMs) {
                    if (DEBUG.enabled()) {
                        DEBUG.warn(Category.KEEPALIVE, "UDP TIMEOUT: {}, lastReceivedAge={}ms, timeout={}ms, "
                                        + "nextPingIn={}ms, replyConfirmed={}, {}",
                                session.describe(), now - session.lastReceived, keepAliveTimeoutMs,
                                session.sentKeepAlive + 1_000L - now, session.replyConfirmed, session.stats.snapshot(now));
                    }
                    removeSession(session);
                    logger.info("UDP session timed out for player {}", session.playerId);
                } else if (now - session.sentKeepAlive >= 1_000L) {
                    session.sentKeepAlive = now + ThreadLocalRandom.current().nextInt(1500, 3000);
                    try {
                        byte[] data = PacketUdpCodec.encodeThrowing(new PingPacket(), session.secret);
                        endpoint.send(new DatagramPacket(data, data.length, session.remoteAddress));
                    } catch (IOException e) {
                        logger.debug("Failed to send a voice UDP ping to {}", session.playerId, e);
                        if (DEBUG.enabled()) session.sendFailed(new PingPacket(), e);
                        continue;
                    }
                    if (DEBUG.enabled()) {
                        session.stats.sent(UdpStats.Kind.PING, now);
                        DEBUG.log(Category.KEEPALIVE, "ping sent: player={}, generation={}, remote={}, pingTx={}, lastReceivedAge={}ms",
                                session.name(), session.generation, session.remoteAddress, session.stats.pingTx.get(),
                                now - session.lastReceived);
                    }
                }
            }
        }
    }

    /** UDP worker, every keep-alive tick while debug logging is enabled; never changes a session. */
    private void diagnostics(long now) {
        boolean snapshot = snapshots.due(now);
        if (snapshot) {
            DEBUG.log(Category.UDP, "server snapshot: sessions={}, bound={}, unknownSecret={}, malformed={}, receiveFailures={}",
                    bySecret.size(), boundAddress, unknownSecret, malformed, receiveFailures);
        }
        for (Session session : bySecret.values()) {
            if (!session.active || !session.authenticated) continue;
            switch (session.silence.check(now)) {
                case SILENT:
                case STILL_SILENT:
                    DEBUG.warn(Category.KEEPALIVE, "inbound UDP silence: {}, age={}ms, lastReceivedAge={}ms, timeout={}ms, {}",
                            session.describe(), session.silence.age(now), now - session.lastReceived, keepAliveTimeoutMs,
                            session.stats.snapshot(now));
                    break;
                case RECOVERED:
                    DEBUG.log(Category.KEEPALIVE, "inbound UDP recovered after {}ms: {}", session.silence.age(now), session.describe());
                    break;
                default:
                    break;
            }
            if (snapshot) {
                DEBUG.log(Category.UDP, "session snapshot: {}, lastReceivedAge={}ms, {}, audioAccepted={}, audioRejected={}, "
                                + "audioForwarded={}, recipientSends={}, noRecipient={}",
                        session.describe(), now - session.lastReceived, session.stats.snapshot(now), session.audioAccepted,
                        session.audioRejected, session.audioForwarded, session.recipientSends, session.noRecipient);
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
        /** Proximity source of this player; a new session is a new source, like upstream on UDP reconnect. */
        private final UUID sourceId = UUID.randomUUID();
        private final byte sourceState = 1;
        /** Published by the server thread every tick, read by the UDP worker. */
        @Setter
        private volatile Presence presence;
        /** Distance of the last routed frame; -1 until the player speaks. */
        private volatile short lastDistance = -1;
        private final AtomicBoolean sourceInfoDirty = new AtomicBoolean(true);
        /** Upstream activeActivations: set by routed audio, cleared by PlayerAudioEndPacket on the server thread. */
        private volatile boolean activationActive;
        /** Upstream lastActivationSequenceNumber. */
        private volatile long lastActivationEnd;

        // Diagnostics only, updated while debug logging is enabled; the routing counters belong to the UDP worker.
        /** Local session number for correlating logs; never sent. */
        private volatile long generation;
        /** Player name for log lines, set by the server thread. */
        @Setter
        private volatile String playerName;
        private final UdpStats stats = new UdpStats();
        private final SilenceMonitor silence = new SilenceMonitor(5_000L, 5_000L);
        private long audioAccepted;
        private long audioRejected;
        private long audioForwarded;
        private long recipientSends;
        private long noRecipient;

        String name() {
            String name = playerName;
            return name != null ? name : playerId.toString();
        }

        /** Identity and endpoint for log lines; no secrets. */
        String describe() {
            return "player=" + name() + ", uuid=" + playerId + ", generation=" + generation + ", remote=" + remoteAddress
                    + ", authenticated=" + authenticated;
        }

        /** UDP worker, under the session lock, before the remote address is updated. */
        private void received(UdpStats.Kind kind, DatagramPacket datagram) {
            long now = System.currentTimeMillis();
            stats.received(kind, now);
            silence.traffic(now);
            InetSocketAddress from = (InetSocketAddress) datagram.getSocketAddress();
            if (remoteAddress != null && !remoteAddress.equals(from)) {
                DEBUG.log(Category.UDP, "remote endpoint changed: player={}, generation={}, from={}, to={}",
                        name(), generation, remoteAddress, from);
            }
        }

        private void rejected(Presence presence, VoiceActivation activation, PlayerAudioPacket audio) {
            long rejected = ++audioRejected;
            if (rejected == 1 || rejected % 250 == 0) {
                String reason = presence == null || !presence.isVoiceConnected() ? "voice not connected"
                        : presence.isServerMuted() ? "server muted"
                        : presence.isMicrophoneMuted() ? "microphone muted"
                        : activation == null ? "no proximity activation" : "unknown activation " + audio.getActivationId();
                DEBUG.log(Category.AUDIO, "audio rejected: player={}, generation={}, reason={}, rejected={}",
                        name(), generation, reason, rejected);
            }
        }

        /** The first failure with its stack trace, then every 250th. */
        private void sendFailed(Packet<?> packet, IOException e) {
            long failures = stats.failedTx.incrementAndGet();
            if (failures == 1 || failures % 250 == 0) {
                DEBUG.error(Category.UDP, "UDP send failed: player={}, generation={}, packet={}, remote={}, failedTx={}", e,
                        name(), generation, packet.getClass().getSimpleName(), remoteAddress, failures);
            }
        }

        /** Server thread: ends the activation once; false when it was not active. */
        boolean endActivation(long sequenceNumber) {
            if (!activationActive) return false;
            activationActive = false;
            lastActivationEnd = sequenceNumber;
            return true;
        }
    }

    @Value
    public static class Presence {
        boolean voiceConnected;
        boolean voiceDisabled;
        boolean microphoneMuted;
        /** Upstream MuteManager: a server mute drops the player's audio. */
        boolean serverMuted;
        int dimension;
        double x;
        double y;
        double z;
    }
}
