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
        ClientConfig a = decode(server.createPacket(first.getPublic(), true), first);
        ClientConfig b = decode(server.createPacket(second.getPublic(), true), second);
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
        ClientConfig next = decode(new ServerConfig().createPacket(first.getPublic(), true), first);
        assertNotEquals(a.getPacket().getServerId(), next.getPacket().getServerId());
        assertFalse(Arrays.equals(a.getAesKey().getEncoded(), next.getAesKey().getEncoded()));
        assertThrows(java.security.GeneralSecurityException.class,
                () -> decode(server.createPacket(first.getPublic(), true), second));
    }

    @Test
    public void proximityLineAndActivationMatchUpstreamDefaults() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ServerConfig server = new ServerConfig();
        ConfigPacket packet = decode(server.createPacket(pair.getPublic(), true), pair).getPacket();

        VoiceSourceLine line = packet.getSourceLines().stream()
                .filter(it -> it.getId().equals(VoiceSourceLine.PROXIMITY_ID)).findFirst().orElse(null);
        assertNotNull(line);
        assertEquals(2, packet.getSourceLines().size());
        assertEquals("pv.activation.proximity", line.getTranslation());
        assertEquals("plasmovoice:textures/icons/speaker.png", line.getIcon());
        assertEquals(1.0, line.getDefaultVolume(), 0.0);
        assertEquals(1, line.getWeight());
        assertFalse(line.hasPlayers());

        VoiceActivation activation = packet.getActivations().stream()
                .filter(it -> it.getId().equals(VoiceActivation.PROXIMITY_ID)).findFirst().orElse(null);
        assertNotNull(activation);
        assertEquals(2, packet.getActivations().size());
        assertEquals("pv.activation.proximity", activation.getTranslation());
        assertEquals("plasmovoice:textures/icons/microphone.png", activation.getIcon());
        assertEquals(Arrays.asList(8, 16, 32), activation.getDistances());
        assertEquals(16, activation.getDefaultDistance());
        assertTrue(activation.isProximity());
        assertTrue(activation.isTransitive());
        assertFalse(activation.isStereoSupported());
        assertFalse(activation.getEncoderInfo().isPresent());
        assertEquals(1, activation.getWeight());

        ClientConfig client = decode(server.createPacket(pair.getPublic(), true), pair);
        assertEquals(Integer.valueOf(16), client.activationDistances().get(VoiceActivation.PROXIMITY_ID));

        java.util.Map<java.util.UUID, Integer> sent = new java.util.HashMap<>();
        sent.put(VoiceActivation.PROXIMITY_ID, 32);
        sent.put(java.util.UUID.randomUUID(), 8); // unknown activations are ignored
        assertEquals(java.util.Collections.singletonMap(VoiceActivation.PROXIMITY_ID, 32),
                server.knownActivationDistances(sent));
    }

    /** pv-addon-groups GroupsAddon: activation and source line "groups", weight 10, no distances. */
    @Test
    public void groupsLineAndActivationMatchUpstreamAddon() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ServerConfig server = new ServerConfig();
        ConfigPacket packet = decode(server.createPacket(pair.getPublic(), true), pair).getPacket();

        VoiceSourceLine line = packet.getSourceLines().stream()
                .filter(it -> it.getName().equals("groups")).findFirst().orElse(null);
        assertNotNull(line);
        assertEquals(server.getGroupsLine().getId(), line.getId());
        assertEquals("pv.activation.groups", line.getTranslation());
        assertEquals("plasmovoice:textures/icons/speaker_group.png", line.getIcon());
        assertEquals(10, line.getWeight());
        assertFalse(line.hasPlayers());

        VoiceActivation activation = packet.getActivations().stream()
                .filter(it -> it.getName().equals("groups")).findFirst().orElse(null);
        assertNotNull(activation);
        assertEquals(server.getGroupsActivation().getId(), activation.getId());
        assertEquals("pv.activation.groups", activation.getTranslation());
        assertEquals("plasmovoice:textures/icons/microphone_group.png", activation.getIcon());
        assertTrue(activation.getDistances().isEmpty());
        assertFalse(activation.isProximity());
        assertTrue(activation.isTransitive());
        assertFalse(activation.isStereoSupported());
        assertEquals(10, activation.getWeight());
    }

    /**
     * A client that never announced Groups gets the normal voice config, the same lifecycle key and proximity, but
     * no Groups activation or source line: nothing it does not know, and no Groups key to press.
     */
    @Test
    public void olderClientsGetTheNormalConfigWithoutGroups() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ServerConfig server = new ServerConfig();
        ClientConfig groups = decode(server.createPacket(pair.getPublic(), true), pair);
        ClientConfig older = decode(server.createPacket(pair.getPublic(), false), pair);

        assertNotNull(server.getGroupsActivation());
        assertArrayEquals(groups.getAesKey().getEncoded(), older.getAesKey().getEncoded());
        assertEquals(groups.getPacket().getServerId(), older.getPacket().getServerId());
        assertEquals(Arrays.asList(server.getProximityActivation().getId()), ids(older.getPacket().getActivations()));
        assertEquals(Arrays.asList(server.getProximityLine().getId()), lineIds(older.getPacket().getSourceLines()));
        assertEquals(set(server.getProximityActivation().getId(), server.getGroupsActivation().getId()),
                new java.util.HashSet<>(ids(groups.getPacket().getActivations())));
        assertEquals(set(server.getProximityLine().getId(), server.getGroupsLine().getId()),
                new java.util.HashSet<>(lineIds(groups.getPacket().getSourceLines())));
    }

    private static java.util.Set<java.util.UUID> set(java.util.UUID... ids) {
        return new java.util.HashSet<>(Arrays.asList(ids));
    }

    private static java.util.List<java.util.UUID> ids(java.util.Collection<VoiceActivation> activations) {
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (VoiceActivation activation : activations) ids.add(activation.getId());
        return ids;
    }

    private static java.util.List<java.util.UUID> lineIds(java.util.Collection<VoiceSourceLine> lines) {
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (VoiceSourceLine line : lines) ids.add(line.getId());
        return ids;
    }

    @Test
    public void rejectsUnsupportedEncryptionAndInvalidKeyLength() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ConfigPacket original = new ServerConfig().createPacket(pair.getPublic(), true);
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
