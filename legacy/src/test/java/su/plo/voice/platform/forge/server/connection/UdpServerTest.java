package su.plo.voice.platform.forge.server.connection;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.junit.Test;
import com.google.common.io.ByteStreams;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.bothbound.PingPacket;

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

    /**
     * Upstream NettyUdpKeepAlive writes asynchronously: a peer that cannot be reached loses its ping, the others keep
     * theirs. Sending to port 0 always fails synchronously, which stands in for an unreachable peer.
     */
    @Test
    public void oneUnreachablePeerDoesNotStopKeepAliveForOthers() throws Exception {
        try (UdpServer server = new UdpServer(LogManager.getLogger("test"), "127.0.0.1", 0, "127.0.0.1", 0, 60_000);
             DatagramSocket healthy = new DatagramSocket(null);
             DatagramSocket broken = new DatagramSocket(null)) {
            healthy.bind(new InetSocketAddress("127.0.0.1", 0));
            broken.bind(new InetSocketAddress("127.0.0.1", 0));
            healthy.setSoTimeout(10_000);
            server.start();
            UdpServer.Session a = server.createSession(UUID.randomUUID());
            UdpServer.Session b = server.createSession(UUID.randomUUID());
            ConnectionPacket connection = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (connection == null && System.nanoTime() < deadline) {
                connection = server.connectionPacket(a);
                if (connection == null) Thread.sleep(10L);
            }
            assertNotNull(connection);
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", connection.getPort());
            ping(healthy, a, target);
            ping(broken, b, target);
            while (!a.isAuthenticated() || !b.isAuthenticated()) Thread.sleep(10L);

            assertPing(healthy); // first keep-alive
            Field remote = UdpServer.Session.class.getDeclaredField("remoteAddress");
            remote.setAccessible(true);
            synchronized (b) {
                remote.set(b, new InetSocketAddress("127.0.0.1", 0));
            }
            // Pings come every 2.5-4 s: by the second one here the broken peer has failed at least once.
            assertPing(healthy);
            assertPing(healthy);
            assertTrue(a.isActive());
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.getName().equals("plasmo-voice-udp-server") && thread.isAlive()));
        }
    }

    private static void ping(DatagramSocket from, UdpServer.Session session, InetSocketAddress to) throws Exception {
        byte[] data = PacketUdpCodec.encodeThrowing(new PingPacket("127.0.0.1", to.getPort()), session.getSecret());
        from.send(new DatagramPacket(data, data.length, to));
    }

    private static void assertPing(DatagramSocket socket) throws Exception {
        DatagramPacket datagram = new DatagramPacket(new byte[2048], 2048);
        socket.receive(datagram);
        assertTrue(PacketUdpCodec.decodeThrowing(ByteStreams.newDataInput(
                Arrays.copyOf(datagram.getData(), datagram.getLength())), PacketDirection.CLIENT)
                .getPacketUntyped() instanceof PingPacket);
    }
}
