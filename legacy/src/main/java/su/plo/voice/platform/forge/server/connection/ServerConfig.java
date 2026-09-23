package su.plo.voice.platform.forge.server.connection;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;

import lombok.Getter;
import su.plo.voice.proto.data.audio.capture.CaptureInfo;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.codec.CodecInfo;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.config.PlayerIconConfig;
import su.plo.voice.proto.data.encryption.EncryptionInfo;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

/** Configuration and key shared by all connections in one server lifecycle. */
public final class ServerConfig {
    @Getter
    private final ServerSettings settings;
    @Getter
    private final UUID serverId;
    private final byte[] aesKey;
    @Getter
    private final CaptureInfo captureInfo;
    /** Upstream ProximityServerActivation: the built-in proximity activation and its source line. */
    @Getter
    private final VoiceActivation proximityActivation;
    @Getter
    private final VoiceSourceLine proximityLine = new VoiceSourceLine(
            VoiceSourceLine.PROXIMITY_NAME,
            "pv.activation.proximity",
            "plasmovoice:textures/icons/speaker.png",
            1.0,
            1,
            null);

    public ServerConfig() throws GeneralSecurityException {
        this(ServerSettings.defaults());
    }

    /** The AES key is new for every server lifecycle, like upstream (it is not stored in the config file). */
    public ServerConfig(ServerSettings settings) throws GeneralSecurityException {
        this.settings = settings;
        this.serverId = settings.getServerId();
        this.captureInfo = new CaptureInfo(settings.getSampleRate(), settings.getMtuSize(), new CodecInfo("opus",
                Map.of("mode", settings.getOpusMode(), "bitrate", String.valueOf(settings.getOpusBitrate()))));
        this.proximityActivation = new VoiceActivation(
                VoiceActivation.PROXIMITY_NAME,
                "pv.activation.proximity",
                "plasmovoice:textures/icons/microphone.png",
                settings.getProximityDistances(),
                settings.getProximityDefaultDistance(),
                true,
                false,
                true,
                null,
                1);
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        aesKey = generator.generateKey().getEncoded();
    }

    public ConfigPacket createPacket(PublicKey publicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return new ConfigPacket(serverId, captureInfo,
                new EncryptionInfo("AES/CBC/PKCS5Padding", cipher.doFinal(aesKey)),
                Collections.singleton(proximityLine),
                Collections.singleton(proximityActivation),
                synchronizedPermissions(),
                new PlayerIconConfig());
    }

    /** Keeps distances only for activations this server registered, like upstream PlayerChannelHandler. */
    Map<UUID, Integer> knownActivationDistances(Map<UUID, Integer> distances) {
        Map<UUID, Integer> known = new HashMap<>();
        Integer distance = distances.get(proximityActivation.getId());
        if (distance != null) known.put(proximityActivation.getId(), distance);
        return known;
    }

    /** Upstream synchronizes "pv.allow_freecam" (default TRUE); 1.7.10 has no permission API, so defaults apply. */
    private static Map<String, Boolean> synchronizedPermissions() {
        return Collections.singletonMap("pv.allow_freecam", true);
    }
}
