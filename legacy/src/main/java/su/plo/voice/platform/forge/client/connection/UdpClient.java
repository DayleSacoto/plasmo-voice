package su.plo.voice.platform.forge.client.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.common.io.ByteStreams;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;
import su.plo.voice.platform.forge.debug.DebugInterval;
import su.plo.voice.platform.forge.debug.SilenceMonitor;
import su.plo.voice.platform.forge.debug.UdpStats;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.platform.forge.debug.WorkerWatch;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.udp.PacketUdp;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.bothbound.PingPacket;
import su.plo.voice.proto.packets.udp.clientbound.SelfAudioInfoPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

@SideOnly(Side.CLIENT)
public final class UdpClient implements AutoCloseable {
    /** Upstream NettyUdpClientHandler MAX_SOFT_KEEP_ALIVE_TIMEOUT. */
    static final long SOFT_TIMEOUT_MS = 7_000L;
    /** Upstream NettyUdpClientHandler MAX_KEEP_ALIVE_TIMEOUT. */
    static final long HARD_TIMEOUT_MS = 30_000L;
    /** Pause after a failed receive so a persistent socket error cannot spin the worker. */
    static final long RECEIVE_ERROR_BACKOFF_MS = 10L;
    private static final VoiceDebug DEBUG = VoiceDebug.CLIENT;
    /** Diagnostic endpoint numbers; local metadata only, never sent. */
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final long DEBUG_INTERVAL_MS = 5_000L;
    private final long generation = GENERATIONS.incrementAndGet();
    private final UdpStats stats = new UdpStats();
    // Diagnostics of the UDP worker, used only while debug logging is enabled.
    private final SilenceMonitor rxSilence = new SilenceMonitor(DEBUG_INTERVAL_MS, DEBUG_INTERVAL_MS);
    private final SilenceMonitor pingSilence = new SilenceMonitor(DEBUG_INTERVAL_MS, DEBUG_INTERVAL_MS);
    private final DebugInterval snapshots = new DebugInterval(DEBUG_INTERVAL_MS);
    private final WorkerWatch watch = new WorkerWatch("UDP client generation " + generation, 2_000L);
    private final Logger logger;
    private final UUID secret;
    private final String host;
    private final int port;
    private final Thread worker;
    private volatile DatagramSocket socket;
    private final ClientConnectionState.UdpState state;
    private volatile boolean closed;
    private final Consumer<Packet<?>> audioListener;
    private final Consumer<UdpClient> stoppedListener;
    /** Worker only. */
    private long receiveFailures;

    /**
     * audioListener receives SourceAudioPacket/SelfAudioInfoPacket on the UDP worker thread. stoppedListener runs on
     * the worker when it ends by itself (keep-alive timeout, unusable socket), not after {@link #close()}: upstream
     * UdpClientClosedEvent, which closes the voice server connection.
     */
    public UdpClient(Logger logger, UUID secret, String host, int port, ClientConnectionState.UdpState state,
                     Consumer<Packet<?>> audioListener, Consumer<UdpClient> stoppedListener) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid UDP remote endpoint");
        }
        this.logger = logger;
        this.secret = secret;
        this.host = host;
        this.port = port;
        this.state = Objects.requireNonNull(state);
        this.audioListener = Objects.requireNonNull(audioListener);
        this.stoppedListener = Objects.requireNonNull(stoppedListener);
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
        if (DEBUG.enabled()) {
            DEBUG.log(Category.THREAD, "UDP client worker started: generation={}, remote={}:{}, thread={}",
                    generation, host, port, VoiceDebug.thread());
        }
        String exitReason = "closed";
        Throwable failure = null;
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
            logger.debug("UDP client endpoint opened for {}", remote);
            if (DEBUG.enabled()) {
                DEBUG.log(Category.UDP, "endpoint opened: generation={}, local={}, remote={}",
                        generation, endpoint.getLocalSocketAddress(), remote);
            }
            long keepAlive = System.currentTimeMillis();
            long lastAttempt = 0L;
            int bootstrapAttempts = 0;
            byte[] buffer = new byte[65507];
            while (!closed) {
                long now = System.currentTimeMillis();
                state.setTimedOut(state.isConfirmed() && now - keepAlive > SOFT_TIMEOUT_MS);
                if (DEBUG.enabled()) {
                    watch.beat(now);
                    diagnostics(endpoint, now, keepAlive);
                }
                if (now - keepAlive > HARD_TIMEOUT_MS) {
                    logger.warn("UDP timed out");
                    exitReason = "keep-alive timeout: no server ping for " + (now - keepAlive) + " ms";
                    break;
                }
                if (!state.isConfirmed() && now - lastAttempt >= 1000L) {
                    send(endpoint, new PingPacket(remote.getHostString(), remote.getPort()));
                    lastAttempt = now;
                    if (DEBUG.enabled()) {
                        DEBUG.log(Category.UDP, "bootstrap ping sent: generation={}, attempt={}", generation, ++bootstrapAttempts);
                    }
                }
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    endpoint.receive(datagram);
                } catch (SocketTimeoutException ignored) {
                    continue;
                } catch (SocketException e) {
                    // Upstream NioDatagramChannel.closeOnReadError keeps reading after a SocketException
                    // (ICMP port unreachable, a network change); a closed socket ends the worker.
                    if (!recoverable(e, closed || endpoint.isClosed())) throw e;
                    receiveFailed(e);
                    Thread.sleep(RECEIVE_ERROR_BACKOFF_MS);
                    continue;
                }
                try {
                    PacketUdp packet = PacketUdpCodec.decodeThrowing(
                            ByteStreams.newDataInput(Arrays.copyOf(datagram.getData(), datagram.getLength())),
                            PacketDirection.CLIENT);
                    if (!secret.equals(packet.getSecret())) {
                        if (DEBUG.enabled()) stats.ignored.incrementAndGet();
                        continue;
                    }
                    if (packet.getPacketClass() == SourceAudioPacket.class
                            || packet.getPacketClass() == SelfAudioInfoPacket.class) {
                        if (DEBUG.enabled()) received(UdpStats.Kind.AUDIO);
                        deliver(packet.getPacketUntyped());
                        continue;
                    }
                    if (packet.getPacketClass() != PingPacket.class) {
                        if (DEBUG.enabled()) received(UdpStats.Kind.OTHER);
                        continue;
                    }
                    packet.getPacketUntyped();
                    keepAlive = System.currentTimeMillis();
                    if (DEBUG.enabled()) received(UdpStats.Kind.PING);
                    if (!state.isConfirmed()) {
                        logger.debug("UDP ping received; bidirectional bootstrap confirmed");
                        if (DEBUG.enabled()) {
                            DEBUG.log(Category.UDP, "bootstrap confirmed: generation={}, attempts={}, remote={}",
                                    generation, bootstrapAttempts, remote);
                            rxSilence.reset(keepAlive);
                            pingSilence.reset(keepAlive);
                        }
                    }
                    state.confirm();
                } catch (IOException | RuntimeException ignored) {
                    // Upstream's decoder drops a bad datagram and the channel keeps reading.
                    if (DEBUG.enabled()) stats.malformed.incrementAndGet();
                    continue;
                }
                send(endpoint, new PingPacket());
            }
        } catch (Exception e) {
            failure = e;
            exitReason = closed ? "closed" : "exception";
            if (!closed) logger.warn("UDP client stopped unexpectedly", e);
        } catch (Error e) {
            failure = e;
            exitReason = "error";
            throw e;
        } finally {
            boolean closedByClient = closed;
            state.close();
            closed = true;
            if (!closedByClient) {
                try {
                    stoppedListener.accept(this);
                } catch (RuntimeException e) {
                    logger.warn("Failed to close the voice connection after the UDP client stopped", e);
                }
            }
            logger.debug("UDP client endpoint closed");
            if (DEBUG.enabled()) {
                String line = "UDP client worker stopped: generation={}, reason={}, closedByClient={}, {}";
                String snapshot = stats.snapshot(System.currentTimeMillis());
                if (failure != null && !closedByClient) {
                    DEBUG.error(Category.THREAD, line, failure, generation, exitReason, false, snapshot);
                } else {
                    DEBUG.log(Category.THREAD, line, generation, exitReason, closedByClient, snapshot);
                }
            }
        }
    }

    /** UDP worker, while debug logging is enabled: diagnostic-only silence warnings and periodic snapshots. */
    private void diagnostics(DatagramSocket endpoint, long now, long keepAlive) {
        if (state.isConfirmed()) {
            report(rxSilence, "inbound UDP", now, keepAlive);
            report(pingSilence, "server ping", now, keepAlive);
        }
        if (snapshots.due(now)) {
            DEBUG.log(Category.UDP, "snapshot: state={}, generation={}, local={}, remote={}, keepAliveAge={}ms, {}, "
                            + "receiveFailures={}, thread={}", stateName(), generation, endpoint.getLocalSocketAddress(),
                    endpoint.getRemoteSocketAddress(), now - keepAlive, stats.snapshot(now), receiveFailures, VoiceDebug.thread());
        }
    }

    private void report(SilenceMonitor monitor, String traffic, long now, long keepAlive) {
        switch (monitor.check(now)) {
            case SILENT:
                DEBUG.warn(Category.KEEPALIVE, "{} silence detected: age={}ms, state={}, generation={}, keepAliveAge={}ms, {}",
                        traffic, monitor.age(now), stateName(), generation, now - keepAlive, stats.snapshot(now));
                break;
            case STILL_SILENT:
                DEBUG.warn(Category.KEEPALIVE, "{} still silent: age={}ms, state={}, generation={}, keepAliveAge={}ms, {}",
                        traffic, monitor.age(now), stateName(), generation, now - keepAlive, stats.snapshot(now));
                break;
            case RECOVERED:
                DEBUG.log(Category.KEEPALIVE, "{} traffic recovered after {}ms: generation={}", traffic, monitor.age(now), generation);
                break;
            default:
                break;
        }
    }

    private String stateName() {
        if (state.isClosed()) return "CLOSED";
        if (!state.isConfirmed()) return "BOOTSTRAP";
        return state.isTimedOut() ? "SOFT_TIMED_OUT" : "CONNECTED";
    }

    private void received(UdpStats.Kind kind) {
        long now = System.currentTimeMillis();
        stats.received(kind, now);
        rxSilence.traffic(now);
        if (kind == UdpStats.Kind.PING) {
            pingSilence.traffic(now);
            DEBUG.log(Category.UDP, "ping received: generation={}, pingRx={}", generation, stats.pingRx.get());
        }
    }

    /** Thread-safe; used by the capture thread. Packets are dropped until the endpoint is open. */
    public void send(Packet<?> packet) {
        DatagramSocket endpoint = socket;
        if (closed || endpoint == null || !endpoint.isConnected()) return;
        try {
            byte[] data = PacketUdpCodec.encodeThrowing(packet, secret);
            endpoint.send(new DatagramPacket(data, data.length));
            if (DEBUG.enabled()) stats.sent(packet instanceof PlayerAudioPacket ? UdpStats.Kind.AUDIO : UdpStats.Kind.OTHER,
                    System.currentTimeMillis());
        } catch (IOException e) {
            logger.debug("Failed to send voice UDP packet", e);
            if (DEBUG.enabled()) sendFailed(packet, e);
        }
    }

    private void deliver(Packet<?> packet) {
        try {
            audioListener.accept(packet);
        } catch (RuntimeException e) {
            // One bad frame must not stop the UDP worker.
            logger.debug("Failed to handle voice audio packet", e);
            DEBUG.error(Category.UDP, "failed to handle {}: generation={}", e, packet.getClass().getSimpleName(), generation);
        }
    }

    /** Upstream writes with a void promise: a failed datagram is lost, the channel stays open. */
    private void send(DatagramSocket endpoint, PingPacket ping) throws IOException {
        byte[] data = PacketUdpCodec.encodeThrowing(ping, secret);
        try {
            endpoint.send(new DatagramPacket(data, data.length));
        } catch (IOException e) {
            if (endpoint.isClosed()) throw e;
            logger.debug("Failed to send voice UDP ping", e);
            if (DEBUG.enabled()) sendFailed(ping, e);
            return;
        }
        if (DEBUG.enabled()) {
            stats.sent(UdpStats.Kind.PING, System.currentTimeMillis());
            DEBUG.log(Category.UDP, "ping sent: generation={}, pingTx={}", generation, stats.pingTx.get());
        }
    }

    /**
     * A receive error the worker survives: a datagram socket error on an open socket. Other I/O errors and any error
     * after close end the worker.
     */
    static boolean recoverable(IOException e, boolean closed) {
        return !closed && e instanceof SocketException;
    }

    /** The first failure with its stack trace, then every 250th. */
    private void receiveFailed(IOException e) {
        long failures = ++receiveFailures;
        logger.debug("Voice UDP receive failed", e);
        if (DEBUG.enabled() && (failures == 1 || failures % 250 == 0)) {
            DEBUG.error(Category.UDP, "UDP receive failed: generation={}, receiveFailures={}", e, generation, failures);
        }
    }

    /** The first failure with its stack trace, then every 250th, so a dead route does not flood the log. */
    private void sendFailed(Packet<?> packet, IOException e) {
        long failures = stats.failedTx.incrementAndGet();
        if (failures == 1 || failures % 250 == 0) {
            DEBUG.error(Category.UDP, "UDP send failed: packet={}, generation={}, failedTx={}", e,
                    packet.getClass().getSimpleName(), generation, failures);
        }
    }

    /** Client thread, while debug logging is enabled: reports a blocked receive worker, which cannot log itself. */
    public void checkWorker(long now) {
        watch.check(DEBUG, worker, closed, now);
    }

    public long getGeneration() {
        return generation;
    }

    UdpStats getStats() {
        return stats;
    }

    /**
     * Never blocks: callers are on the client game thread. The state is closed first so a worker still
     * resolving the address cannot revive it; closing the socket wakes a blocked receive.
     */
    @Override
    public void close() {
        if (DEBUG.enabled() && !closed) {
            DEBUG.log(Category.UDP, "endpoint close requested: generation={}, thread={}", generation, VoiceDebug.thread());
        }
        state.close();
        closed = true;
        DatagramSocket endpoint = socket;
        if (endpoint != null) endpoint.close();
    }
}
