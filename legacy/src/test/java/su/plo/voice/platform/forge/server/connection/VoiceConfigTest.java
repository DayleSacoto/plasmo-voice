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
        assertTrue(a.getPacket().getSourceLines().isEmpty());
        assertTrue(a.getPacket().getActivations().isEmpty());
        assertTrue(a.getPacket().getPermissions().isEmpty());
        assertNotNull(a.getPacket().getPlayerIconConfig());
        ClientConfig next = decode(new ServerConfig().createPacket(first.getPublic()), first);
        assertNotEquals(a.getPacket().getServerId(), next.getPacket().getServerId());
        assertFalse(Arrays.equals(a.getAesKey().getEncoded(), next.getAesKey().getEncoded()));
        assertThrows(java.security.GeneralSecurityException.class,
                () -> decode(server.createPacket(first.getPublic()), second));
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
