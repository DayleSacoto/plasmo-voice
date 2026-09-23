package su.plo.voice.platform.forge.client;

import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import lombok.Setter;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

/** In-memory client settings. All methods run on the client game thread. */
@SideOnly(Side.CLIENT)
public final class ClientState {
    @Getter
    private static final ClientState instance = new ClientState();
    // Read by the capture thread.
    @Getter
    private volatile boolean voiceDisabled;
    @Getter
    private volatile boolean microphoneMuted;
    /** Upstream default: the proximity activation is push-to-talk. */
    @Getter
    @Setter
    private volatile CaptureActivation.Type activationType = CaptureActivation.Type.PUSH_TO_TALK;
    @Getter
    @Setter
    private volatile boolean pushToTalkPressed;
    /** Upstream voice activation threshold in dB (-60..0). */
    @Getter
    @Setter
    private volatile double activationThreshold = -30D;
    @Getter
    private ClientConnectionState connection;

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
}
