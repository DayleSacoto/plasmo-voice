package su.plo.voice.platform.forge.client.connection;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import su.plo.voice.platform.forge.server.connection.UdpServer;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;

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
            UdpClient client = new UdpClient(LOGGER, packet.getSecret(), packet.getIp(), packet.getPort(), udp, received -> {});
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

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not reached in time", System.currentTimeMillis() < deadline);
            Thread.sleep(10L);
        }
    }
}
