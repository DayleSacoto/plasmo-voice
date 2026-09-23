package su.plo.voice.platform.forge.client.connection;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

/** Independent connection facts; configuration and ping confirmation have no ordering dependency. */
@SideOnly(Side.CLIENT)
public final class ClientConnectionState implements AutoCloseable {
    private final Consumer<? super PlayerStatePacket> stateSender;
    @Getter
    private boolean connected = true;
    @Getter
    private ClientConfig config;
    @Getter
    private UdpState udp;
    // Last voice state the server was told through PlayerInfoPacket or PlayerStatePacket.
    private PlayerStatePacket reportedState;

    public ClientConnectionState(Consumer<? super PlayerStatePacket> stateSender) {
        this.stateSender = Objects.requireNonNull(stateSender);
    }

    public UdpState replaceUdp() {
        if (!connected) throw new IllegalStateException("Connection is closed");
        clearConfig();
        if (udp != null) udp.close();
        udp = new UdpState();
        return udp;
    }

    public boolean hasUdpEndpoint() {
        return udp != null && udp.getRemoteAddress() != null;
    }

    public boolean isUdpConfirmed() {
        return udp != null && udp.isConfirmed();
    }

    public boolean isConfigured() {
        return config != null;
    }

    /**
     * The server sends ConfigPacket only after it authenticated this client's UDP ping,
     * which is also when upstream starts accepting PlayerStatePacket.
     */
    public boolean isStateSyncReady() {
        return connected && config != null;
    }

    public void acceptConfig(ClientConfig config) {
        if (!connected) throw new IllegalStateException("Connection is closed");
        this.config = Objects.requireNonNull(config);
    }

    public void clearConfig() {
        config = null;
    }

    public void stateReported(boolean voiceDisabled, boolean microphoneMuted) {
        reportedState = new PlayerStatePacket(voiceDisabled, microphoneMuted);
    }

    /** Sends the state only when the server accepts it and has not been told this state yet. */
    public void syncState(boolean voiceDisabled, boolean microphoneMuted) {
        if (!isStateSyncReady()) return;
        if (reportedState != null && reportedState.isVoiceDisabled() == voiceDisabled
                && reportedState.isMicrophoneMuted() == microphoneMuted) return;
        PlayerStatePacket packet = new PlayerStatePacket(voiceDisabled, microphoneMuted);
        stateSender.accept(packet);
        reportedState = packet;
    }

    @Override
    public void close() {
        connected = false;
        clearConfig();
        if (udp != null) udp.close();
    }

    /** One instance per UDP client. Late worker updates cannot revive a closed endpoint. */
    @SideOnly(Side.CLIENT)
    public static final class UdpState implements AutoCloseable {
        @Getter
        private volatile InetSocketAddress remoteAddress;
        @Getter
        private volatile boolean confirmed;
        private boolean closed;

        public synchronized void opened(InetSocketAddress remoteAddress) {
            if (!closed) this.remoteAddress = Objects.requireNonNull(remoteAddress);
        }

        public synchronized void confirm() {
            if (!closed && remoteAddress != null) confirmed = true;
        }

        @Override
        public synchronized void close() {
            closed = true;
            remoteAddress = null;
            confirmed = false;
        }
    }
}
