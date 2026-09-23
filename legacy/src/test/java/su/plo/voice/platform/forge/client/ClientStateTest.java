package su.plo.voice.platform.forge.client;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.data.audio.capture.CaptureInfo;
import su.plo.voice.proto.data.config.PlayerIconConfig;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;

import static org.junit.Assert.*;

public class ClientStateTest {
    @Test
    public void settingsSurviveConnectionReplacementAndReachPlayerInfo() throws Exception {
        ClientState client = new ClientState();
        assertFalse(client.isVoiceDisabled());
        assertFalse(client.isMicrophoneMuted());
        assertFalse(client.isConnected());
        ClientConnectionState first = client.openConnection(packet -> {});
        client.setVoiceDisabled(true);
        client.setMicrophoneMuted(true);
        first.replaceUdp().opened(new InetSocketAddress("127.0.0.1", 24454));
        first.acceptConfig(config());
        assertTrue(first.isConfigured());
        assertFalse(first.isUdpConfirmed()); // Config may arrive before the client's first ping reply.
        first.getUdp().confirm();
        assertTrue(first.isUdpConfirmed());
        first.close();
        assertFalse(client.isConnected());
        assertFalse(first.hasUdpEndpoint());
        assertFalse(first.isUdpConfirmed());
        assertFalse(first.isConfigured());
        ClientConnectionState second = client.openConnection(packet -> {});
        assertNotSame(first, second);
        assertTrue(second.isConnected());
        assertFalse(second.hasUdpEndpoint());
        assertFalse(second.isUdpConfirmed());
        assertFalse(second.isConfigured());
        PlayerInfoPacket packet = client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1, 2});
        assertTrue(packet.isVoiceDisabled());
        assertTrue(packet.isMicrophoneMuted());
        client.setMicrophoneMuted(false);
        assertFalse(client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1}).isMicrophoneMuted());
        first.close(); // A stale close must not clear the new connection.
        assertTrue(client.isConnected());
        second.close();
        assertTrue(client.isVoiceDisabled());
    }

    @Test
    public void replacementUdpCannotInheritOrReviveOldFlags() throws Exception {
        ClientConnectionState connection = new ClientState().openConnection(packet -> {});
        ClientConnectionState.UdpState old = connection.replaceUdp();
        old.opened(new InetSocketAddress("127.0.0.1", 24454));
        old.confirm();
        connection.acceptConfig(config());
        ClientConnectionState.UdpState next = connection.replaceUdp();
        assertFalse(connection.isConfigured());
        old.opened(new InetSocketAddress("127.0.0.1", 24454));
        old.confirm();
        assertNull(old.getRemoteAddress());
        assertFalse(old.isConfirmed());
        assertFalse(connection.hasUdpEndpoint());
        next.opened(new InetSocketAddress("127.0.0.1", 24455));
        next.confirm();
        assertTrue(connection.isUdpConfirmed());
        assertFalse(connection.isConfigured()); // Ping does not imply config acceptance.
        next.close();
        assertFalse(connection.hasUdpEndpoint());
        assertFalse(connection.isUdpConfirmed());
    }

    @Test
    public void voiceAvailableNeedsConnectionConfigAndUdpEndpoint() throws Exception {
        ClientState client = new ClientState();
        assertFalse(client.isVoiceAvailable());
        ClientConnectionState connection = client.openConnection(packet -> {});
        ClientConnectionState.UdpState udp = connection.replaceUdp();
        assertFalse(client.isVoiceAvailable());
        udp.opened(new InetSocketAddress("127.0.0.1", 24454));
        assertFalse(client.isVoiceAvailable()); // endpoint without server config
        connection.acceptConfig(config());
        assertTrue(client.isVoiceAvailable());
        udp.close(); // worker timed out; config alone is not enough
        assertTrue(udp.isClosed());
        assertFalse(client.isVoiceAvailable());
        connection.close();
        assertFalse(client.isVoiceAvailable());
    }

    private ClientConfig config() throws Exception {
        return ClientConfig.decode(new ConfigPacket(UUID.randomUUID(), new CaptureInfo(48000, 1024, null),
                null, Collections.emptySet(), Collections.emptySet(), Collections.emptyMap(), new PlayerIconConfig()), null);
    }
}
