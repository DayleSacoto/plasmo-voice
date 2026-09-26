package su.plo.voice.platform.forge.client.connection;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.google.common.io.ByteStreams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.udp.PacketUdp;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.bothbound.PingPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import static org.junit.Assert.*;

/** Upstream Netty semantics: a bad datagram or a failed write loses that datagram, the channel keeps working. */
public class UdpClientRobustnessTest {
    private static final Logger LOGGER = LogManager.getLogger("test");
    private static final String WORKER = "plasmo-voice-udp-client";

    @Test
    public void keepAliveTimeoutsMatchUpstream() {
        assertEquals(7_000L, UdpClient.SOFT_TIMEOUT_MS);
        assertEquals(30_000L, UdpClient.HARD_TIMEOUT_MS);
    }

    @Test
    public void onlySocketErrorsOnAnOpenSocketAreRecoverable() {
        assertTrue(UdpClient.recoverable(new PortUnreachableException(), false));
        assertTrue(UdpClient.recoverable(new SocketException("Network is unreachable"), false));
        assertFalse(UdpClient.recoverable(new SocketException("Socket closed"), true));
        assertFalse(UdpClient.recoverable(new IOException("unexpected"), false));
    }

    @Test
    public void malformedDatagramsAndFailingAudioHandlersDoNotStopTheWorker() throws Exception {
        try (DatagramSocket server = stub()) {
            UUID secret = UUID.randomUUID();
            AtomicInteger handled = new AtomicInteger();
            AtomicInteger stopped = new AtomicInteger();
            ClientConnectionState.UdpState udp = new ClientConnectionState(sent -> {}).replaceUdp();
            UdpClient client = new UdpClient(LOGGER, secret, "127.0.0.1", server.getLocalPort(), udp, packet -> {
                handled.incrementAndGet();
                throw new IllegalStateException("audio handler failure");
            }, closed -> stopped.incrementAndGet());
            client.start();
            try {
                SocketAddress address = receive(server).getSocketAddress(); // bootstrap ping
                send(server, new byte[] {1, 2, 3}, address);
                send(server, new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 99}, address);
                send(server, PacketUdpCodec.encodeThrowing(new SourceAudioPacket(1L, (byte) 1, new byte[] {1},
                        UUID.randomUUID(), (short) 16), secret), address);
                await(() -> handled.get() == 1);
                send(server, PacketUdpCodec.encodeThrowing(new PingPacket(), secret), address);
                await(udp::isConfirmed);
                assertNotNull(awaitPingReply(server));
                assertEquals(0, stopped.get());
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void failedSendKeepsTheConnection() throws Exception {
        VoiceDebug.CLIENT.setEnabled(true);
        try (DatagramSocket server = stub()) {
            UUID secret = UUID.randomUUID();
            AtomicInteger stopped = new AtomicInteger();
            ClientConnectionState.UdpState udp = new ClientConnectionState(sent -> {}).replaceUdp();
            UdpClient client = new UdpClient(LOGGER, secret, "127.0.0.1", server.getLocalPort(), udp, packet -> {},
                    closed -> stopped.incrementAndGet());
            client.start();
            try {
                SocketAddress address = receive(server).getSocketAddress();
                send(server, PacketUdpCodec.encodeThrowing(new PingPacket(), secret), address);
                await(udp::isConfirmed);
                assertNotNull(awaitPingReply(server));

                // Larger than any datagram: the send fails like a transient route error would.
                client.send(new PlayerAudioPacket(1L, new byte[70_000], VoiceActivation.PROXIMITY_ID, (short) 16, false));
                assertEquals(1L, client.getStats().failedTx.get());

                send(server, PacketUdpCodec.encodeThrowing(new PingPacket(), secret), address);
                assertNotNull(awaitPingReply(server));
                assertTrue(udp.isConfirmed());
                assertEquals(0, stopped.get());
            } finally {
                client.close();
            }
        } finally {
            VoiceDebug.CLIENT.setEnabled(false);
        }
    }

    /** Upstream UdpClientClosedEvent: the connection is told when the client ends by itself, not after close(). */
    @Test
    public void stoppedListenerRunsOnlyWhenTheWorkerEndsByItself() throws Exception {
        AtomicInteger stopped = new AtomicInteger();
        ClientConnectionState state = new ClientConnectionState(sent -> {});
        // A wildcard remote cannot be used: the worker ends on its own.
        UdpClient broken = new UdpClient(LOGGER, UUID.randomUUID(), "0.0.0.0", 5_000, state.replaceUdp(), packet -> {},
                closed -> stopped.incrementAndGet());
        broken.start();
        await(() -> stopped.get() == 1);

        try (DatagramSocket server = stub()) {
            UdpClient client = new UdpClient(LOGGER, UUID.randomUUID(), "127.0.0.1", server.getLocalPort(),
                    state.replaceUdp(), packet -> {}, closed -> stopped.incrementAndGet());
            client.start();
            receive(server);
            client.close();
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.getName().equals(WORKER)));
            assertEquals(1, stopped.get());
        }
    }

    private static DatagramSocket stub() throws SocketException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static DatagramPacket receive(DatagramSocket socket) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[65_507], 65_507);
        socket.receive(packet);
        return packet;
    }

    /** The next ping reply (no server address), skipping bootstrap pings. */
    private static PingPacket awaitPingReply(DatagramSocket socket) throws IOException {
        while (true) {
            DatagramPacket datagram = receive(socket);
            PacketUdp packet = PacketUdpCodec.decodeThrowing(ByteStreams.newDataInput(
                    Arrays.copyOf(datagram.getData(), datagram.getLength())), PacketDirection.SERVER);
            if (packet.getPacketUntyped() instanceof PingPacket) {
                PingPacket ping = (PingPacket) packet.getPacketUntyped();
                if (ping.getServerIp() == null) return ping;
            }
        }
    }

    private static void send(DatagramSocket socket, byte[] data, SocketAddress to) throws IOException {
        socket.send(new DatagramPacket(data, data.length, to));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not reached in time", System.currentTimeMillis() < deadline);
            Thread.sleep(10L);
        }
    }
}
