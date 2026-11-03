package su.plo.voice.platform.forge.network.groups;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.GroupsClientSupport;

import static org.junit.Assert.*;

/** Groups as an optional, session-only client capability. */
public class GroupsCapabilitiesTest {
    private final GroupsCapabilities<Object> capabilities = new GroupsCapabilities<>();
    private final UUID player = UUID.randomUUID();
    private final Object connection = new Object();

    @Test
    public void aClientThatNeverAnnouncesIsNotGroupsCapable() {
        assertFalse(capabilities.supports(player));
        assertEquals(0, capabilities.protocol(player));
        assertFalse(capabilities.supports(null));
    }

    @Test
    public void theHandshakeMarksAModernClientAsSupported() {
        assertEquals(1, capabilities.announce(player, connection, 1, 1));
        assertTrue(capabilities.supports(player));
        assertEquals(1, capabilities.protocol(player));
        // announced again before every voice handshake: nothing new, and nothing is reset
        assertEquals(0, capabilities.announce(player, connection, 1, 1));
        assertTrue(capabilities.supports(player));
    }

    @Test
    public void protocolsAreNegotiatedIndependentlyOfTheVoiceVersion() {
        assertEquals(1, GroupsCapabilities.negotiate(1, 1));
        // a future client that still speaks v1 gets v1 from this server
        assertEquals(1, GroupsCapabilities.negotiate(1, 3));
        // a future client that dropped v1 is treated like a client without Groups
        assertEquals(0, GroupsCapabilities.negotiate(2, 3));
        assertEquals(0, GroupsCapabilities.negotiate(0, 0));
        assertEquals(0, GroupsCapabilities.negotiate(2, 1));

        assertEquals(0, capabilities.announce(player, connection, 2, 3));
        assertFalse(capabilities.supports(player));
    }

    @Test
    public void capabilityStateClearsOnDisconnect() {
        capabilities.announce(player, connection, 1, 1);
        capabilities.remove(player, connection);
        assertFalse(capabilities.supports(player));
    }

    @Test
    public void reconnectingWithAGroupsClientRestoresIt() {
        capabilities.announce(player, connection, 1, 1);
        capabilities.remove(player, connection);

        Object second = new Object();
        assertEquals(1, capabilities.announce(player, second, 1, 1));
        assertTrue(capabilities.supports(player));
        // the late logout of the old connection does not clear the new one
        capabilities.remove(player, connection);
        assertTrue(capabilities.supports(player));
    }

    @Test
    public void reconnectingWithAnOlderClientLeavesGroupsOff() {
        capabilities.announce(player, connection, 1, 1);
        capabilities.remove(player, connection);
        // the older client never announces on its new connection
        assertFalse(capabilities.supports(player));
        assertEquals(GroupsClientSupport.OUTDATED, GroupsClientSupport.of(capabilities.supports(player), false));
    }

    @Test
    public void clearForgetsEveryone() {
        capabilities.announce(player, connection, 1, 1);
        capabilities.clear();
        assertFalse(capabilities.supports(player));
    }

    /**
     * The client sends the hello before its PlayerInfoPacket on the same connection and the server records it on the
     * netty thread, so by the time the voice handshake is handled the answer is final. Before that the player is
     * "connecting", which never produces an "outdated" message.
     */
    @Test
    public void handshakeOrderingNeverCallsAModernClientOutdated() {
        // logged in, voice handshake not answered yet, hello not here yet
        assertEquals(GroupsClientSupport.CONNECTING, GroupsClientSupport.of(capabilities.supports(player), true));
        // the hello arrives first on the wire...
        capabilities.announce(player, connection, 1, 1);
        assertEquals(GroupsClientSupport.SUPPORTED, GroupsClientSupport.of(capabilities.supports(player), true));
        // ...then the PlayerInfoPacket ends the wait
        assertEquals(GroupsClientSupport.SUPPORTED, GroupsClientSupport.of(capabilities.supports(player), false));

        // an older client answers the handshake without any hello
        UUID older = UUID.randomUUID();
        assertEquals(GroupsClientSupport.CONNECTING, GroupsClientSupport.of(capabilities.supports(older), true));
        assertEquals(GroupsClientSupport.OUTDATED, GroupsClientSupport.of(capabilities.supports(older), false));
    }

    @Test
    public void helloFrameIsFixedAndRoundTrips() throws IOException {
        byte[] data = GroupsProtocol.encode(new GroupsHello(1, 1));
        assertArrayEquals(new byte[] {1, GroupsProtocol.KIND_HELLO, 0, 1, 0, 1}, data);
        GroupsHello hello = (GroupsHello) GroupsProtocol.decode(data);
        assertEquals(1, hello.minProtocol);
        assertEquals(1, hello.maxProtocol);

        // a future client may append fields; this server still reads its range
        byte[] longer = Arrays.copyOf(data, data.length + 3);
        longer[5] = 4;
        GroupsHello future = (GroupsHello) GroupsProtocol.decode(longer);
        assertEquals(4, future.maxProtocol);
    }

    @Test(expected = IOException.class)
    public void truncatedHelloIsRejected() throws IOException {
        GroupsProtocol.decode(new byte[] {1, GroupsProtocol.KIND_HELLO, 0});
    }
}
