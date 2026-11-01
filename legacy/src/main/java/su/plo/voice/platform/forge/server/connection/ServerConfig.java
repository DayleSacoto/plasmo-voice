package su.plo.voice.platform.forge.server.connection;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;

import lombok.Getter;
import su.plo.slib.api.position.Pos3d;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;
import su.plo.voice.proto.data.audio.capture.CaptureInfo;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.codec.CodecInfo;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.config.PlayerIconConfig;
import su.plo.voice.proto.data.encryption.EncryptionInfo;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

/** Configuration and key shared by all connections in one server lifecycle. */
public final class ServerConfig {
    public static final String GROUPS_NAME = "groups";

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
    /**
     * pv-addon-groups GroupsAddon: a non-proximity, transitive, mono activation without distances and its source
     * line; null while groups are disabled.
     */
    @Getter
    private final VoiceActivation groupsActivation;
    @Getter
    private final VoiceSourceLine groupsLine;

    public ServerConfig() throws GeneralSecurityException {
        this(ServerSettings.defaults());
    }

    /** The AES key is new for every server lifecycle, like upstream (it is not stored in the config file). */
    public ServerConfig(ServerSettings settings) throws GeneralSecurityException {
        this(settings, null);
    }

    /** Upstream reload keeps the AES key of the running server. */
    public ServerConfig(ServerSettings settings, ServerConfig previous) throws GeneralSecurityException {
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
        GroupsSettings groups = settings.getGroups();
        if (groups.isEnabled()) {
            this.groupsActivation = new VoiceActivation(GROUPS_NAME, "pv.activation.groups",
                    "plasmovoice:textures/icons/microphone_group.png", Collections.emptyList(), 0,
                    false, false, true, null, groups.getActivationWeight());
            this.groupsLine = new VoiceSourceLine(GROUPS_NAME, "pv.activation.groups",
                    "plasmovoice:textures/icons/speaker_group.png", 1.0, groups.getSourceLineWeight(), null);
        } else {
            this.groupsActivation = null;
            this.groupsLine = null;
        }
        if (previous != null) {
            aesKey = previous.aesKey;
        } else {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(128);
            aesKey = generator.generateKey().getEncoded();
        }
    }

    public ConfigPacket createPacket(PublicKey publicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return new ConfigPacket(serverId, captureInfo,
                new EncryptionInfo("AES/CBC/PKCS5Padding", cipher.doFinal(aesKey)),
                sourceLines(),
                activations(),
                synchronizedPermissions(),
                new PlayerIconConfig(settings.getPlayerIconVisibility(), new Pos3d(0D, settings.getPlayerIconYOffset(), 0D)));
    }

    /** Keeps distances only for activations this server registered, like upstream PlayerChannelHandler. */
    Map<UUID, Integer> knownActivationDistances(Map<UUID, Integer> distances) {
        Map<UUID, Integer> known = new HashMap<>();
        for (VoiceActivation activation : activations()) {
            Integer distance = distances.get(activation.getId());
            if (distance != null) known.put(activation.getId(), distance);
        }
        return known;
    }

    private Set<VoiceSourceLine> sourceLines() {
        Set<VoiceSourceLine> lines = new LinkedHashSet<>();
        lines.add(proximityLine);
        if (groupsLine != null) lines.add(groupsLine);
        return lines;
    }

    private Set<VoiceActivation> activations() {
        Set<VoiceActivation> activations = new LinkedHashSet<>();
        activations.add(proximityActivation);
        if (groupsActivation != null) activations.add(groupsActivation);
        return activations;
    }

    /** Upstream synchronizes "pv.allow_freecam" (default TRUE); 1.7.10 has no permission API, so defaults apply. */
    private static Map<String, Boolean> synchronizedPermissions() {
        return Collections.singletonMap("pv.allow_freecam", true);
    }
}
