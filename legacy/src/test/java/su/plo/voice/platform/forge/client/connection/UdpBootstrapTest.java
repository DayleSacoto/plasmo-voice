package su.plo.voice.platform.forge.client.connection;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import su.plo.voice.platform.forge.server.connection.UdpServer;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;

import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import static org.junit.Assert.*;

public class UdpBootstrapTest {
    private static final Logger LOGGER = LogManager.getLogger("test");

    @Test
    public void realLoopbackBootstrapAndNonBlockingClose() throws Exception {
        try (UdpServer server = new UdpServer(LOGGER, "127.0.0.1", 0, "127.0.0.1", 0, 15_000)) {
            server.start();
            UdpServer.Session session = server.createSession(UUID.randomUUID());
            await(() -> server.connectionPacket(session) != null);
            ConnectionPacket packet = server.connectionPacket(session);

            ClientConnectionState state = new ClientConnectionState(sent -> {});
            ClientConnectionState.UdpState udp = state.replaceUdp();
            UdpClient client = new UdpClient(LOGGER, packet.getSecret(), packet.getIp(), packet.getPort(), udp, received -> {}, stopped -> {});
            client.start();

            await(session::isAuthenticated); // client ping reached the server
            await(udp::isConfirmed); // server keep-alive ping reached the client

            long started = System.nanoTime();
            client.close();
            long closeMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue("close() blocked for " + closeMillis + " ms", closeMillis < 100);
            assertTrue(udp.isClosed());
            assertFalse(udp.isConfirmed());
            await(() -> Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.getName().equals("plasmo-voice-udp-client")));
        }
    }

    @Test
    public void endpointGenerationsIncrease() {
        ClientConnectionState state = new ClientConnectionState(sent -> {});
        UdpClient first = new UdpClient(LOGGER, UUID.randomUUID(), "127.0.0.1", 1, state.replaceUdp(), packet -> {}, stopped -> {});
        UdpClient second = new UdpClient(LOGGER, UUID.randomUUID(), "127.0.0.1", 1, state.replaceUdp(), packet -> {}, stopped -> {});
        assertTrue(second.getGeneration() > first.getGeneration());
    }

    /** Diagnostics only observe: bootstrap, keep-alive and the server timeout behave the same with debug enabled. */
    @Test
    public void diagnosticsDoNotChangeKeepAliveOrTimeout() throws Exception {
        long withoutDebug = bootstrapAndTimeOut(false);
        VoiceDebug.CLIENT.setEnabled(true);
        VoiceDebug.SERVER.setEnabled(true);
        try {
            long withDebug = bootstrapAndTimeOut(true);
            assertEquals("timeout with debug " + withDebug + " ms, without " + withoutDebug + " ms",
                    withoutDebug, withDebug, 400D);
        } finally {
            VoiceDebug.CLIENT.setEnabled(false);
            VoiceDebug.SERVER.setEnabled(false);
        }
    }

    /**
     * Milliseconds from the client's last datagram to the server timing its session out (1 second timeout). Audio
     * refreshes the session like upstream's lastReceivedPacketTimestamp, so it stays alive until the client stops.
     */
    private static long bootstrapAndTimeOut(boolean debug) throws Exception {
        try (UdpServer server = new UdpServer(LOGGER, "127.0.0.1", 0, "127.0.0.1", 0, 1_000)) {
            server.start();
            UdpServer.Session session = server.createSession(UUID.randomUUID());
            await(() -> server.connectionPacket(session) != null);
            ConnectionPacket packet = server.connectionPacket(session);
            ClientConnectionState.UdpState udp = new ClientConnectionState(sent -> {}).replaceUdp();
            UdpClient client = new UdpClient(LOGGER, packet.getSecret(), packet.getIp(), packet.getPort(), udp, received -> {}, stopped -> {});
            client.start();
            await(session::isAuthenticated);
            await(udp::isConfirmed);
            for (int frame = 0; frame < 75; frame++) {
                client.send(new PlayerAudioPacket(frame, new byte[] {1, 2, 3}, VoiceActivation.PROXIMITY_ID, (short) 16, false));
                Thread.sleep(20L);
            }
            // 1.5 s of audio kept the session alive past its 1 second timeout.
            assertTrue(session.isActive());
            assertTrue(udp.isConfirmed());
            // Counters only run while debug logging is enabled.
            assertEquals(debug, client.getStats().audioTx.get() == 75);
            assertEquals(debug, session.getStats().audioRx.get() > 0);

            long lastSent = System.currentTimeMillis();
            client.close();
            await(() -> !session.isActive());
            long elapsed = System.currentTimeMillis() - lastSent;
            assertTrue("timed out after " + elapsed + " ms", elapsed >= 900L && elapsed <= 1_600L);
            return elapsed;
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not reached in time", System.currentTimeMillis() < deadline);
            Thread.sleep(10L);
        }
    }
}
