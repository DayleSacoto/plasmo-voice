package su.plo.voice.platform.forge.client.connection;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import su.plo.voice.platform.forge.encryption.AesEncryption;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.encryption.EncryptionInfo;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

/** Accepted protocol configuration; does not initialize codecs or audio devices. */
@SideOnly(Side.CLIENT)
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClientConfig {
    private final ConfigPacket packet;
    private final SecretKeySpec aesKey;
    /** Upstream ServerInfo encryption for audio frames; null when the server sends unencrypted audio. */
    private final AesEncryption encryption;

    /** Upstream VoiceClientActivationManager.register: the allowed distance for each server activation. */
    public Map<UUID, Integer> activationDistances() {
        return activationDistances(activationId -> null);
    }

    /** Uses the player's stored choice for this server when the server still allows it (upstream Server config). */
    public Map<UUID, Integer> activationDistances(Function<UUID, Integer> stored) {
        Map<UUID, Integer> distances = new LinkedHashMap<>();
        for (VoiceActivation activation : packet.getActivations()) {
            distances.put(activation.getId(), allowedDistance(activation, stored.apply(activation.getId())));
        }
        return distances;
    }

    /** Upstream VoiceClientActivationManager order: heaviest first, equal weights by name ignoring case. */
    public static final Comparator<VoiceActivation> ACTIVATION_ORDER =
            Comparator.comparingInt(VoiceActivation::getWeight).reversed()
                    .thenComparing(VoiceActivation::getName, String.CASE_INSENSITIVE_ORDER);

    /** Every activation but the proximity one (the parent), in {@link #ACTIVATION_ORDER}. */
    public List<VoiceActivation> otherActivations() {
        return otherActivations(packet.getActivations());
    }

    public static List<VoiceActivation> otherActivations(Collection<VoiceActivation> activations) {
        List<VoiceActivation> others = new ArrayList<>();
        for (VoiceActivation activation : activations) {
            if (!activation.getId().equals(VoiceActivation.PROXIMITY_ID)) others.add(activation);
        }
        others.sort(ACTIVATION_ORDER);
        return others;
    }

    public VoiceActivation activation(UUID activationId) {
        for (VoiceActivation activation : packet.getActivations()) {
            if (activation.getId().equals(activationId)) return activation;
        }
        return null;
    }

    public VoiceSourceLine sourceLine(UUID lineId) {
        for (VoiceSourceLine line : packet.getSourceLines()) {
            if (line.getId().equals(lineId)) return line;
        }
        return null;
    }

    public static int allowedDistance(VoiceActivation activation, Integer stored) {
        return activation.calculateAllowedDistance(stored == null ? activation.getDefaultDistance() : stored);
    }

    public static ClientConfig decode(ConfigPacket packet, PrivateKey privateKey) throws GeneralSecurityException {
        EncryptionInfo encryption = packet.getEncryption();
        if (encryption == null) return new ClientConfig(packet, null, null);
        if (!"AES/CBC/PKCS5Padding".equals(encryption.getAlgorithm())) {
            throw new GeneralSecurityException("Unsupported encryption algorithm");
        }
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] key = cipher.doFinal(encryption.getData());
        try {
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new GeneralSecurityException("Invalid AES key length");
            }
            return new ClientConfig(packet, new SecretKeySpec(key, "AES"), new AesEncryption(key));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
