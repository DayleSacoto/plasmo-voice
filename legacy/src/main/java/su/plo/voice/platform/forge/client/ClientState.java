package su.plo.voice.platform.forge.client;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.resources.I18n;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.audio.MicrophoneTest;
import su.plo.voice.platform.forge.client.audio.SourcePlayer;
import su.plo.voice.platform.forge.client.audio.VoiceLeveler;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.platform.forge.client.hud.HudOptions;
import su.plo.voice.platform.forge.client.hud.GroupHudModel;
import su.plo.voice.platform.forge.client.hud.GroupHudPosition;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.audio.source.SourceInfo;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

/**
 * Client settings (upstream VoiceClientConfig) plus the current connection. Setters run on the client thread;
 * the capture and playback threads read the volatile fields. Connection state is never persisted.
 */
@SideOnly(Side.CLIENT)
public final class ClientState {
    @Getter
    private static final ClientState instance = new ClientState();

    // voice.disabled / voice.microphone_disabled
    @Getter
    private volatile boolean voiceDisabled;
    @Getter
    private volatile boolean microphoneMuted;
    /** Upstream voice.activation_threshold in dB (-60..0). */
    @Getter
    private volatile double activationThreshold = ClientSettingsFile.DEFAULT_THRESHOLD;
    /** Empty selects the system default device, like upstream. */
    @Getter
    private volatile String inputDevice = "";
    @Getter
    private volatile String outputDevice = "";
    /** Upstream voice.stereo_capture and voice.disable_input_device. */
    @Getter
    @Setter
    private volatile boolean stereoCapture;
    @Getter
    @Setter
    private volatile boolean inputDeviceDisabled;
    /**
     * Upstream voice.use_javax_input: capture through Java Sound instead of OpenAL. Config file only (upstream shows it
     * in the Cloth Config screen); forced on macOS, and it turns stereo capture off.
     */
    @Getter
    @Setter
    private volatile boolean useJavaxInput;
    /**
     * debug.enabled: Plasmo Voice client diagnostics in the game log. Config file only, like upstream's debug; the
     * global switch is applied once at startup.
     */
    @Getter
    @Setter
    private volatile boolean debug;
    /** Upstream voice.microphone_volume and voice.volume, 0..2. */
    @Getter
    private volatile double microphoneVolume = 1D;
    @Getter
    private volatile double volume = 1D;
    /** Proximity activation config: upstream default is push-to-talk. */
    @Getter
    private volatile CaptureActivation.Type activationType = CaptureActivation.Type.PUSH_TO_TALK;
    /** Upstream ConfigClientActivation.configToggle: true turns voice activation off. */
    @Getter
    private volatile boolean activationToggled;
    /**
     * Upstream ConfigClientActivation of every other activation, by activation name (not the id: the activation
     * need not be in the current config to keep its settings). Absent means push-to-talk, not toggled.
     */
    private final Map<String, ActivationSettings> activationSettings = new ConcurrentHashMap<>();
    /** Upstream Servers: chosen activation distance per server id and activation id. */
    private final Map<UUID, Map<UUID, Integer>> distancesByServer = new ConcurrentHashMap<>();

    /** Upstream overlay config. */
    @Getter
    @Setter
    private volatile boolean showActivationIcon = true;
    @Getter
    @Setter
    private volatile HudOptions.IconPosition activationIconPosition = HudOptions.IconPosition.BOTTOM_CENTER;
    @Getter
    @Setter
    private volatile boolean overlayEnabled = true;
    @Getter
    @Setter
    private volatile HudOptions.OverlayPosition overlayPosition = HudOptions.OverlayPosition.TOP_LEFT;
    @Getter
    @Setter
    private volatile HudOptions.OverlayStyle overlayStyle = HudOptions.OverlayStyle.NAME_SKIN;
    /** Legacy extension: the Group HUD (group name and online members); position in scaled GUI pixels, see GroupHudPosition. */
    @Getter
    @Setter
    private volatile boolean groupHudEnabled = true;
    @Getter
    private volatile GroupHudPosition.Anchor groupHudAnchor = GroupHudPosition.DEFAULT_ANCHOR;
    @Getter
    private volatile int groupHudOffsetX = GroupHudPosition.DEFAULT_OFFSET_X;
    @Getter
    private volatile int groupHudOffsetY = GroupHudPosition.DEFAULT_OFFSET_Y;
    /** Group Overlay style, independent of the generic overlay style. */
    @Getter
    private volatile HudOptions.OverlayStyle groupHudStyle = HudOptions.OverlayStyle.NAME_SKIN;
    /** Group Overlay panel opacity in percent, 0..100. */
    @Getter
    private volatile int groupHudBackgroundOpacity = GroupHudModel.DEFAULT_OPACITY;
    /** Upstream overlay.source_states by source line name; lines without players default to OFF. */
    private final Map<String, HudOptions.OverlaySourceState> overlaySourceStates = new ConcurrentHashMap<>();

    /** Upstream overlay.show_source_icons: 0 hidden together with the HUD, 1 always, 2 never. */
    @Getter
    @Setter
    private volatile int showSourceIcons;

    /** Upstream voice.noise_suppression (RNNoise), voice.sound_occlusion, voice.directional_sources and voice.hrtf. */
    @Getter
    @Setter
    private volatile boolean noiseSuppression;
    @Getter
    @Setter
    private volatile boolean soundOcclusion;
    @Getter
    @Setter
    private volatile boolean directionalSources;
    @Getter
    @Setter
    private volatile boolean hrtf;
    /** Legacy extension, off by default: automatic per-player voice leveling towards a speech level in dBFS. */
    @Getter
    @Setter
    private volatile boolean voiceLeveling;
    @Getter
    private volatile double voiceLevelingTarget = VoiceLeveler.DEFAULT_TARGET_DB;
    /** Legacy extension: updates.check_for_updates, one GitHub release check per game session. */
    @Getter
    @Setter
    private volatile boolean checkForUpdates = true;
    /** Runtime: upstream disables the noise suppression entry when RNNoise cannot load on this platform. */
    @Getter
    @Setter
    private volatile boolean noiseSuppressionAvailable = true;
    /** Runtime: upstream DeviceManager input device error, shown next to the microphone dropdown. */
    @Getter
    @Setter
    private volatile boolean inputDeviceFailed;

    /** Upstream advanced config; the options that only apply to addon source types are left out. */
    @Getter
    @Setter
    private volatile boolean visualizeVoiceDistance = true;
    @Getter
    @Setter
    private volatile boolean visualizeVoiceDistanceOnJoin;
    @Getter
    @Setter
    private volatile boolean panning = true;
    @Getter
    @Setter
    private volatile boolean exponentialVolumeSlider = true;
    @Getter
    @Setter
    private volatile boolean exponentialDistanceGain = true;
    /** Upstream advanced.directional_sources_angle, 100..360 degrees. */
    @Getter
    private volatile int directionalSourcesAngle = 145;
    @Getter
    @Setter
    private volatile boolean adaptiveJitterBuffer;
    /** Config-file only upstream (Cloth Config menu): jitter_packet_delay 0..16, al_playback_buffers 1..32. */
    @Getter
    private volatile int jitterPacketDelay = 3;
    @Getter
    private volatile int alPlaybackBuffers = 5;
    @Getter
    @Setter
    private volatile boolean cameraSoundListener = true;

    /** Upstream voice.volumes: volume (0..2) and mute by source line name or {@link #playerVolumeKey}; defaults are not stored. */
    private final Map<String, Double> volumes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> mutes = new ConcurrentHashMap<>();

    /** Upstream MicrophoneTestController; runtime only. */
    @Getter
    private final MicrophoneTest microphoneTest = new MicrophoneTest();

    /** Upstream key_bindings. */
    @Getter
    private final VoiceHotkeys hotkeys = new VoiceHotkeys();

    /** Runtime: the ids of the capture activations that are sending, which the HUD shows with an activation icon. */
    @Getter
    private volatile Set<UUID> activeActivations = Collections.emptySet();

    /** Runtime push-to-talk state of the proximity activation from {@link VoiceControls}; read by the capture thread. */
    @Getter
    @Setter
    private volatile boolean pushToTalkPressed;
    /** Runtime push-to-talk state of the other activations, by name. */
    private final Set<String> pushToTalkPressedActivations = ConcurrentHashMap.newKeySet();
    @Getter
    private ClientConnectionState connection;
    @Setter
    private File settingsFile;

    public void setVoiceDisabled(boolean voiceDisabled) {
        if (this.voiceDisabled == voiceDisabled) return;
        this.voiceDisabled = voiceDisabled;
        syncState();
    }

    public void setMicrophoneMuted(boolean microphoneMuted) {
        if (this.microphoneMuted == microphoneMuted) return;
        this.microphoneMuted = microphoneMuted;
        syncState();
    }

    /** Stores the Group HUD position; a missing anchor and huge offsets are repaired, see GroupHudPosition.sanitize. */
    public void setGroupHudPlacement(GroupHudPosition.Anchor anchor, int offsetX, int offsetY) {
        GroupHudPosition.Placement placement = GroupHudPosition.sanitize(anchor, offsetX, offsetY);
        this.groupHudAnchor = placement.anchor;
        this.groupHudOffsetX = placement.offsetX;
        this.groupHudOffsetY = placement.offsetY;
    }

    public void setGroupHudPlacement(GroupHudPosition.Placement placement) {
        setGroupHudPlacement(placement.anchor, placement.offsetX, placement.offsetY);
    }

    public GroupHudPosition.Placement getGroupHudPlacement() {
        return new GroupHudPosition.Placement(groupHudAnchor, groupHudOffsetX, groupHudOffsetY);
    }

    public void resetGroupHudPlacement() {
        setGroupHudPlacement(GroupHudPosition.DEFAULT);
    }

    public void setGroupHudStyle(HudOptions.OverlayStyle style) {
        this.groupHudStyle = style == null ? HudOptions.OverlayStyle.NAME_SKIN : style;
    }

    public void setGroupHudBackgroundOpacity(int opacityPercent) {
        this.groupHudBackgroundOpacity = GroupHudModel.clampOpacity(opacityPercent);
    }

    public void setDirectionalSourcesAngle(int angle) {
        this.directionalSourcesAngle = Math.max(100, Math.min(360, angle));
    }

    public void setJitterPacketDelay(int delay) {
        this.jitterPacketDelay = Math.max(0, Math.min(16, delay));
    }

    public void setAlPlaybackBuffers(int buffers) {
        this.alPlaybackBuffers = Math.max(1, Math.min(32, buffers));
    }

    public void setActivationThreshold(double activationThreshold) {
        this.activationThreshold = clamp(activationThreshold, -60D, 0D);
    }

    public void setInputDevice(String inputDevice) {
        this.inputDevice = inputDevice == null ? "" : inputDevice;
    }

    public void setOutputDevice(String outputDevice) {
        this.outputDevice = outputDevice == null ? "" : outputDevice;
    }

    public void setMicrophoneVolume(double microphoneVolume) {
        this.microphoneVolume = clamp(microphoneVolume, 0D, 2D);
    }

    public void setVolume(double volume) {
        this.volume = clamp(volume, 0D, 2D);
    }

    public void setVoiceLevelingTarget(double target) {
        this.voiceLevelingTarget = clamp(target, VoiceLeveler.MIN_TARGET_DB, VoiceLeveler.MAX_TARGET_DB);
    }

    /** The proximity activation can never inherit (upstream VoiceClientActivationManager.register falls back to push-to-talk). */
    public void setActivationType(CaptureActivation.Type activationType) {
        this.activationType = activationType == CaptureActivation.Type.INHERIT
                ? CaptureActivation.Type.PUSH_TO_TALK : activationType;
    }

    public void setActivationToggled(boolean activationToggled) {
        this.activationToggled = activationToggled;
    }

    /** Upstream ConfigClientActivation.configType of any activation; the proximity name is the proximity fields. */
    public CaptureActivation.Type getActivationType(String activationName) {
        if (VoiceActivation.PROXIMITY_NAME.equals(activationName)) return activationType;
        ActivationSettings settings = activationSettings.get(activationName);
        return settings == null ? CaptureActivation.Type.PUSH_TO_TALK : settings.type;
    }

    public void setActivationType(String activationName, CaptureActivation.Type type) {
        if (VoiceActivation.PROXIMITY_NAME.equals(activationName)) {
            setActivationType(type);
            return;
        }
        activationSettings.compute(activationName, (name, current) ->
                new ActivationSettings(type, current != null && current.toggled));
    }

    /** Upstream ConfigClientActivation.configToggle of any activation. */
    public boolean isActivationToggled(String activationName) {
        if (VoiceActivation.PROXIMITY_NAME.equals(activationName)) return activationToggled;
        ActivationSettings settings = activationSettings.get(activationName);
        return settings != null && settings.toggled;
    }

    public void setActivationToggled(String activationName, boolean toggled) {
        if (VoiceActivation.PROXIMITY_NAME.equals(activationName)) {
            setActivationToggled(toggled);
            return;
        }
        activationSettings.compute(activationName, (name, current) ->
                new ActivationSettings(current == null ? CaptureActivation.Type.PUSH_TO_TALK : current.type, toggled));
    }

    /** The names of the other activations with a stored type or toggle, for the settings file. */
    Set<String> activationSettingNames() {
        return activationSettings.keySet();
    }

    /** Runtime push-to-talk state of any activation. */
    public boolean isPushToTalkPressed(String activationName) {
        return VoiceActivation.PROXIMITY_NAME.equals(activationName)
                ? pushToTalkPressed : pushToTalkPressedActivations.contains(activationName);
    }

    public void setPushToTalkPressed(String activationName, boolean pressed) {
        if (VoiceActivation.PROXIMITY_NAME.equals(activationName)) {
            pushToTalkPressed = pressed;
        } else if (pressed) {
            pushToTalkPressedActivations.add(activationName);
        } else {
            pushToTalkPressedActivations.remove(activationName);
        }
    }

    public void setActiveActivations(Set<UUID> activeActivations) {
        this.activeActivations = activeActivations;
    }

    /** Some capture activation is sending. */
    public boolean isActivationActive() {
        return !activeActivations.isEmpty();
    }

    /** Null when the player never changed the distance on this server. */
    public Integer getActivationDistance(UUID serverId, UUID activationId) {
        Map<UUID, Integer> distances = distancesByServer.get(serverId);
        return distances == null ? null : distances.get(activationId);
    }

    public void setActivationDistance(UUID serverId, UUID activationId, Integer distance) {
        Map<UUID, Integer> distances = distancesByServer.computeIfAbsent(serverId, id -> new ConcurrentHashMap<>());
        if (distance == null) {
            distances.remove(activationId);
        } else {
            distances.put(activationId, distance);
        }
    }

    /** Stores the distance for the connected server and sends it, like upstream's distance config listener. */
    public void changeActivationDistance(UUID activationId, int distance) {
        if (connection == null || connection.getConfig() == null) return;
        VoiceActivation activation = connection.getConfig().activation(activationId);
        if (activation == null) return;
        UUID serverId = connection.getConfig().getPacket().getServerId();
        // Upstream config listeners fire only on a real change, so dragging a slider sends one packet per step.
        if (ClientConfig.allowedDistance(activation, getActivationDistance(serverId, activationId)) == distance) return;
        // Like upstream, the server default is not stored, so a changed default applies again.
        setActivationDistance(serverId, activationId, activation.getDefaultDistance() == distance ? null : distance);
        connection.sendActivationDistance(activationId, distance);
    }

    public HudOptions.OverlaySourceState getOverlaySourceState(String lineName) {
        return overlaySourceStates.getOrDefault(lineName, HudOptions.OverlaySourceState.OFF);
    }

    public void setOverlaySourceState(String lineName, HudOptions.OverlaySourceState state) {
        overlaySourceStates.put(lineName, state);
    }

    Map<String, HudOptions.OverlaySourceState> overlaySourceStates() {
        return overlaySourceStates;
    }

    /** Upstream SourceLineVolumes.getPlayerVolume key. */
    public static String playerVolumeKey(UUID playerId) {
        return "source_" + playerId;
    }

    public double getSourceVolume(String key) {
        return volumes.getOrDefault(key, 1D);
    }

    public void setSourceVolume(String key, double volume) {
        volume = clamp(volume, 0D, 2D);
        if (volume == 1D) {
            volumes.remove(key);
        } else {
            volumes.put(key, volume);
        }
    }

    public boolean isSourceMuted(String key) {
        return mutes.getOrDefault(key, false);
    }

    public void setSourceMuted(String key, boolean muted) {
        if (muted) {
            mutes.put(key, true);
        } else {
            mutes.remove(key);
        }
    }

    /** Upstream BaseClientAudioSource: global volume times the source line and the player volume, before the slider curve. */
    public double volume(ClientConfig config, SourceInfo info) {
        VoiceSourceLine line = config.sourceLine(info.getLineId());
        double lineVolume = line == null ? 1D : getSourceVolume(line.getName());
        return volume * lineVolume * getSourceVolume(sourceKey(info));
    }

    /** Upstream drops the audio of a muted source line and of a player muted in the Volume tab. */
    public boolean isMuted(ClientConfig config, SourceInfo info) {
        VoiceSourceLine line = config.sourceLine(info.getLineId());
        if (line != null && isSourceMuted(line.getName())) return true;
        return SourcePlayer.id(info) != null && isSourceMuted(sourceKey(info));
    }

    /** The player of a player source or of a direct source with a sender (group voice), else the source itself. */
    private static String sourceKey(SourceInfo info) {
        UUID playerId = SourcePlayer.id(info);
        return playerId != null ? playerVolumeKey(playerId) : "source_" + info.getId();
    }

    Map<String, Double> volumes() {
        return volumes;
    }

    Map<String, Boolean> mutes() {
        return mutes;
    }

    Map<UUID, Map<UUID, Integer>> distancesByServer() {
        return distancesByServer;
    }

    /** Writes the settings file; a no-op in tests where no file is set. */
    public void save() {
        if (settingsFile != null) ClientSettingsFile.save(settingsFile, this);
    }

    /** Only the current connection can send; a replaced one is closed and unreachable from here. */
    public ClientConnectionState openConnection(Consumer<? super PlayerStatePacket> stateSender) {
        if (connection != null) connection.close();
        connection = new ClientConnectionState(stateSender);
        return connection;
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    /** Upstream opens settings only with a live, not timed out UDP client and accepted server info. */
    public boolean isVoiceAvailable() {
        return isConnected() && connection.isConfigured() && connection.hasUdpEndpoint()
                && !connection.getUdp().isTimedOut();
    }

    /** A server translation key, e.g. an activation name; the client's resources without a connection. */
    public String translate(String key) {
        return connection != null ? connection.translate(key) : I18n.format(key);
    }

    /** Sends a live PlayerStatePacket if the current connection is ready and the server state is stale. */
    public void syncState() {
        if (connection != null) connection.syncState(voiceDisabled, microphoneMuted);
    }

    public PlayerInfoPacket createPlayerInfo(String minecraftVersion, String modVersion, byte[] publicKey) {
        if (connection != null) connection.stateReported(voiceDisabled, microphoneMuted);
        return new PlayerInfoPacket(minecraftVersion, modVersion, publicKey, voiceDisabled, microphoneMuted);
    }

    /** Immutable, so the capture thread reads a consistent pair. */
    private static final class ActivationSettings {
        final CaptureActivation.Type type;
        final boolean toggled;

        ActivationSettings(CaptureActivation.Type type, boolean toggled) {
            this.type = type;
            this.toggled = toggled;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
