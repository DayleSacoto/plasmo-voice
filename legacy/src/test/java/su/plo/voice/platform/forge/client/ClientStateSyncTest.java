package su.plo.voice.platform.forge.client;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.data.audio.capture.CaptureInfo;
import su.plo.voice.proto.data.config.PlayerIconConfig;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

import static org.junit.Assert.*;

public class ClientStateSyncTest {
    private final ClientState client = new ClientState();

    @Test
    public void defaultsAreFalse() {
        assertFalse(client.isVoiceDisabled());
        assertFalse(client.isMicrophoneMuted());
    }

    @Test
    public void sameValueDoesNotSend() throws Exception {
        List<PlayerStatePacket> sent = new ArrayList<>();
        ready(sent);
        client.setVoiceDisabled(false);
        client.setMicrophoneMuted(false);
        assertTrue(sent.isEmpty());
        client.setVoiceDisabled(true);
        client.setVoiceDisabled(true);
        assertEquals(1, sent.size());
    }

    @Test
    public void changeSendsOnePacketWithBothValues() throws Exception {
        List<PlayerStatePacket> sent = new ArrayList<>();
        ready(sent);
        client.setVoiceDisabled(true);
        assertState(sent, 1, true, false); // microphoneMuted preserved
        client.setMicrophoneMuted(true);
        assertState(sent, 2, true, true); // voiceDisabled preserved
        client.setVoiceDisabled(false);
        assertState(sent, 3, false, true);
    }

    @Test
    public void notReadyConnectionKeepsSettingLocallyWithoutSending() throws Exception {
        List<PlayerStatePacket> sent = new ArrayList<>();
        client.setVoiceDisabled(true); // no connection at all
        ClientConnectionState connection = client.openConnection(sent::add);
        client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1});
        client.setMicrophoneMuted(true); // PlayerInfo sent, config not yet accepted
        connection.replaceUdp().opened(new InetSocketAddress("127.0.0.1", 24454));
        connection.getUdp().confirm();
        client.setMicrophoneMuted(false);
        client.setMicrophoneMuted(true); // UDP ping alone is not readiness
        assertTrue(sent.isEmpty());
        assertTrue(client.isVoiceDisabled());
        assertTrue(client.isMicrophoneMuted());
    }

    @Test
    public void changeBeforeConfigIsFlushedOnceWhenReady() throws Exception {
        List<PlayerStatePacket> sent = new ArrayList<>();
        ClientConnectionState connection = client.openConnection(sent::add);
        client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1});
        client.setMicrophoneMuted(true);
        connection.acceptConfig(config());
        client.syncState();
        assertState(sent, 1, false, true);
        client.syncState();
        assertEquals(1, sent.size());
    }

    @Test
    public void unchangedStateIsNotResentWhenReady() throws Exception {
        List<PlayerStatePacket> sent = new ArrayList<>();
        client.setVoiceDisabled(true);
        ClientConnectionState connection = client.openConnection(sent::add);
        client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1});
        connection.acceptConfig(config());
        client.syncState();
        assertTrue(sent.isEmpty());
    }

    @Test
    public void staleConnectionDoesNotReceiveUpdatesAndReplacementDoes() throws Exception {
        List<PlayerStatePacket> oldSent = new ArrayList<>();
        List<PlayerStatePacket> newSent = new ArrayList<>();
        ClientConnectionState old = ready(oldSent);
        client.setVoiceDisabled(true);
        client.setMicrophoneMuted(true);
        assertEquals(2, oldSent.size());

        ClientConnectionState replacement = client.openConnection(newSent::add);
        assertFalse(old.isConnected());
        PlayerInfoPacket info = client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1});
        assertTrue(info.isVoiceDisabled()); // settings survive reconnect
        assertTrue(info.isMicrophoneMuted());
        replacement.acceptConfig(config());

        client.setVoiceDisabled(false);
        old.syncState(true, false); // a stale reference cannot send either
        assertEquals(2, oldSent.size());
        assertState(newSent, 1, false, true);
    }

    @Test
    public void closedConnectionStopsSending() throws Exception {
        List<PlayerStatePacket> sent = new ArrayList<>();
        ready(sent).close();
        client.setVoiceDisabled(true);
        assertTrue(sent.isEmpty());
        assertTrue(client.isVoiceDisabled());
    }

    private ClientConnectionState ready(List<PlayerStatePacket> sent) throws Exception {
        ClientConnectionState connection = client.openConnection(sent::add);
        client.createPlayerInfo("1.7.10", "2.1.17", new byte[] {1});
        connection.acceptConfig(config());
        client.syncState();
        return connection;
    }

    private static void assertState(List<PlayerStatePacket> sent, int count, boolean voiceDisabled, boolean microphoneMuted) {
        assertEquals(count, sent.size());
        PlayerStatePacket last = sent.get(count - 1);
        assertEquals(voiceDisabled, last.isVoiceDisabled());
        assertEquals(microphoneMuted, last.isMicrophoneMuted());
    }

    private static ClientConfig config() throws Exception {
        return ClientConfig.decode(new ConfigPacket(UUID.randomUUID(), new CaptureInfo(48000, 1024, null),
                null, Collections.emptySet(), Collections.emptySet(), Collections.emptyMap(), new PlayerIconConfig()), null);
    }
}
