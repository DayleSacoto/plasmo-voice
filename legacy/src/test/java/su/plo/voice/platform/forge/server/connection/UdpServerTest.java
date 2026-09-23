package su.plo.voice.platform.forge.server.connection;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.junit.Test;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;

import static org.junit.Assert.*;

public class UdpServerTest {
    @Test
    public void sessionsBelongToPlayersAndExpireOnLogout() {
        UdpServer server = new UdpServer(LogManager.getLogger("test"), "127.0.0.1", 0, "127.0.0.1", 0, 15_000);
        UUID player = UUID.randomUUID();
        UdpServer.Session first = server.createSession(player);
        assertSame(first, server.createSession(player));
        assertSame(first, server.getSession(first.getSecret()));
        assertNull(server.getSession(UUID.randomUUID()));
        assertFalse(first.isAuthenticated());
        assertNull(server.connectionPacket(first));

        server.removeSession(player);
        assertFalse(first.isActive());
        assertNull(server.getSession(first.getSecret()));
        UdpServer.Session second = server.createSession(player);
        assertNotEquals(first.getSecret(), second.getSecret());
        server.close();
        assertFalse(second.isActive());
        assertNull(server.getSession(second.getSecret()));
    }

    @Test
    public void wildcardSentinelPreservesHostAndUsesBoundPort() throws Exception {
        try (UdpServer server = new UdpServer(LogManager.getLogger("test"), "0.0.0.0", 0, "0.0.0.0", 0, 15_000)) {
            UdpServer.Session session = server.createSession(UUID.randomUUID());
            server.start();
            ConnectionPacket packet = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (packet == null && System.nanoTime() < deadline) {
                packet = server.connectionPacket(session);
                if (packet == null) Thread.sleep(10L);
            }
            assertNotNull("UDP endpoint did not bind", packet);
            assertEquals("0.0.0.0", packet.getIp());
            assertTrue(packet.getPort() > 0);
            Field field = UdpServer.class.getDeclaredField("boundAddress");
            field.setAccessible(true);
            InetSocketAddress bound = (InetSocketAddress) field.get(server);
            assertTrue(bound.getAddress().isAnyLocalAddress());
            assertEquals(bound.getPort(), packet.getPort());
        }
    }

    @Test
    public void emptyHostsAndUnsupportedIpv6SentinelAreRejected() {
        for (String host : new String[] {null, ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new UdpServer(LogManager.getLogger("test"), host, 0, "0.0.0.0", 0, 15_000));
            assertThrows(IllegalArgumentException.class,
                    () -> new UdpServer(LogManager.getLogger("test"), "0.0.0.0", 0, host, 0, 15_000));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new UdpServer(LogManager.getLogger("test"), "0.0.0.0", 0, "::", 0, 15_000));
    }
}
