package su.plo.voice.platform.forge.server.connection;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import javax.crypto.Cipher;

import com.google.common.io.ByteStreams;
import org.junit.Test;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.tcp.PacketTcpCodec;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ClientPacketTcpHandler;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.encryption.EncryptionInfo;

import static org.junit.Assert.*;

public class VoiceConfigTest {
    @Test
    public void upstreamPacketRoundTripKeepsLifecycleKeyAndConfig() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair first = generator.generateKeyPair();
        KeyPair second = generator.generateKeyPair();
        ServerConfig server = new ServerConfig();
        ClientConfig a = decode(server.createPacket(first.getPublic()), first);
        ClientConfig b = decode(server.createPacket(second.getPublic()), second);
        assertEquals(a.getPacket().getServerId(), b.getPacket().getServerId());
        assertArrayEquals(a.getAesKey().getEncoded(), b.getAesKey().getEncoded());
        assertEquals(16, a.getAesKey().getEncoded().length);
        assertNotNull(a.getPacket().getEncryption());
        assertEquals("AES/CBC/PKCS5Padding", a.getPacket().getEncryption().getAlgorithm());
        assertEquals(48000, a.getPacket().getCaptureInfo().getSampleRate());
        assertEquals(1024, a.getPacket().getCaptureInfo().getMtuSize());
        assertEquals("opus", a.getPacket().getCaptureInfo().getEncoderInfo().getName());
        assertEquals("VOIP", a.getPacket().getCaptureInfo().getEncoderInfo().getParams().get("mode"));
        assertEquals("-1000", a.getPacket().getCaptureInfo().getEncoderInfo().getParams().get("bitrate"));
        assertEquals(java.util.Collections.singletonMap("pv.allow_freecam", true), a.getPacket().getPermissions());
        assertNotNull(a.getPacket().getPlayerIconConfig());
        ClientConfig next = decode(new ServerConfig().createPacket(first.getPublic()), first);
        assertNotEquals(a.getPacket().getServerId(), next.getPacket().getServerId());
        assertFalse(Arrays.equals(a.getAesKey().getEncoded(), next.getAesKey().getEncoded()));
        assertThrows(java.security.GeneralSecurityException.class,
                () -> decode(server.createPacket(first.getPublic()), second));
    }

    @Test
    public void proximityLineAndActivationMatchUpstreamDefaults() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ServerConfig server = new ServerConfig();
        ConfigPacket packet = decode(server.createPacket(pair.getPublic()), pair).getPacket();

        VoiceSourceLine line = packet.getSourceLines().iterator().next();
        assertEquals(1, packet.getSourceLines().size());
        assertEquals(VoiceSourceLine.PROXIMITY_ID, line.getId());
        assertEquals("pv.activation.proximity", line.getTranslation());
        assertEquals("plasmovoice:textures/icons/speaker.png", line.getIcon());
        assertEquals(1.0, line.getDefaultVolume(), 0.0);
        assertEquals(1, line.getWeight());
        assertFalse(line.hasPlayers());

        VoiceActivation activation = packet.getActivations().iterator().next();
        assertEquals(1, packet.getActivations().size());
        assertEquals(VoiceActivation.PROXIMITY_ID, activation.getId());
        assertEquals("pv.activation.proximity", activation.getTranslation());
        assertEquals("plasmovoice:textures/icons/microphone.png", activation.getIcon());
        assertEquals(Arrays.asList(8, 16, 32), activation.getDistances());
        assertEquals(16, activation.getDefaultDistance());
        assertTrue(activation.isProximity());
        assertTrue(activation.isTransitive());
        assertFalse(activation.isStereoSupported());
        assertFalse(activation.getEncoderInfo().isPresent());
        assertEquals(1, activation.getWeight());

        ClientConfig client = decode(server.createPacket(pair.getPublic()), pair);
        assertEquals(java.util.Collections.singletonMap(VoiceActivation.PROXIMITY_ID, 16), client.activationDistances());

        java.util.Map<java.util.UUID, Integer> sent = new java.util.HashMap<>();
        sent.put(VoiceActivation.PROXIMITY_ID, 32);
        sent.put(java.util.UUID.randomUUID(), 8); // unknown activations are ignored
        assertEquals(java.util.Collections.singletonMap(VoiceActivation.PROXIMITY_ID, 32),
                server.knownActivationDistances(sent));
    }

    @Test
    public void rejectsUnsupportedEncryptionAndInvalidKeyLength() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ConfigPacket original = new ServerConfig().createPacket(pair.getPublic());
        ConfigPacket unsupported = withEncryption(original, new EncryptionInfo("unknown", new byte[] {1}));
        assertThrows(java.security.GeneralSecurityException.class, () -> decode(unsupported, pair));
        Cipher rsa = Cipher.getInstance("RSA");
        rsa.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        ConfigPacket invalid = withEncryption(original,
                new EncryptionInfo("AES/CBC/PKCS5Padding", rsa.doFinal(new byte[15])));
        assertThrows(java.security.GeneralSecurityException.class, () -> decode(invalid, pair));
        assertNull(decode(withEncryption(original, null), pair).getAesKey());
    }

    private ConfigPacket withEncryption(ConfigPacket original, EncryptionInfo encryption) {
        return new ConfigPacket(original.getServerId(), original.getCaptureInfo(), encryption,
                java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                original.getPermissions(), original.getPlayerIconConfig());
    }

    private ClientConfig decode(ConfigPacket packet, KeyPair keyPair) throws Exception {
        byte[] bytes = PacketTcpCodec.encode(packet);
        assertNotNull(bytes);
        assertEquals(3, bytes[0]);
        ConfigPacket decoded = (ConfigPacket) PacketTcpCodec.<ClientPacketTcpHandler>decode(
                ByteStreams.newDataInput(bytes), PacketDirection.CLIENT).get();
        return ClientConfig.decode(decoded, keyPair.getPrivate());
    }
}
