package su.plo.voice.platform.forge.client;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import lombok.Setter;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
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
    /** Upstream Servers: chosen activation distance per server id and activation id. */
    private final Map<UUID, Map<UUID, Integer>> distancesByServer = new ConcurrentHashMap<>();

    /** Upstream key_bindings. */
    @Getter
    private final VoiceHotkeys hotkeys = new VoiceHotkeys();

    /** Runtime push-to-talk state from {@link VoiceControls}; read by the capture thread. */
    @Getter
    @Setter
    private volatile boolean pushToTalkPressed;
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

    public void setActivationType(CaptureActivation.Type activationType) {
        this.activationType = activationType;
    }

    public void setActivationToggled(boolean activationToggled) {
        this.activationToggled = activationToggled;
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

    /** Upstream opens settings only with a live UDP client and accepted server info. */
    public boolean isVoiceAvailable() {
        return isConnected() && connection.isConfigured() && connection.hasUdpEndpoint();
    }

    /** Sends a live PlayerStatePacket if the current connection is ready and the server state is stale. */
    public void syncState() {
        if (connection != null) connection.syncState(voiceDisabled, microphoneMuted);
    }

    public PlayerInfoPacket createPlayerInfo(String minecraftVersion, String modVersion, byte[] publicKey) {
        if (connection != null) connection.stateReported(voiceDisabled, microphoneMuted);
        return new PlayerInfoPacket(minecraftVersion, modVersion, publicKey, voiceDisabled, microphoneMuted);
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
