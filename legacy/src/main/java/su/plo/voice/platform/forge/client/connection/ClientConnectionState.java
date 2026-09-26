package su.plo.voice.platform.forge.client.connection;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import net.minecraft.client.resources.I18n;
import lombok.Setter;
import su.plo.voice.platform.forge.client.audio.ClientVoiceSources;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerActivationDistancesPacket;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

/** Independent connection facts; configuration and ping confirmation have no ordering dependency. */
@SideOnly(Side.CLIENT)
public final class ClientConnectionState implements AutoCloseable {
    private final Consumer<? super PlayerStatePacket> stateSender;
    /** Remote sources of the accepted config, for the overlay; null while not configured. */
    @Getter
    @Setter
    private volatile ClientVoiceSources sources;
    /** TCP sender for the other serverbound packets; null in tests. */
    @Setter
    private Consumer<Packet<?>> packetSender;
    @Getter
    private boolean connected = true;
    @Getter
    private ClientConfig config;
    @Getter
    private UdpState udp;
    // Last voice state the server was told through PlayerInfoPacket or PlayerStatePacket.
    private PlayerStatePacket reportedState;
    private final Map<UUID, VoicePlayerInfo> players = new LinkedHashMap<>();
    /** Upstream LanguagePacket: the server's client translations (activation and source line names). */
    @Setter
    private volatile Map<String, String> serverLanguage = Collections.emptyMap();

    public ClientConnectionState(Consumer<? super PlayerStatePacket> stateSender) {
        this.stateSender = Objects.requireNonNull(stateSender);
    }

    public UdpState replaceUdp() {
        if (!connected) throw new IllegalStateException("Connection is closed");
        clearConfig();
        // The server sends the full PlayerListPacket again after the new UDP session authenticates.
        players.clear();
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
        sources = null;
    }

    /** Upstream VoiceClientActivation.onDistanceChange: tells the server the new activation distance. */
    public void sendActivationDistance(UUID activationId, int distance) {
        if (isStateSyncReady() && packetSender != null) {
            packetSender.accept(new PlayerActivationDistancesPacket(Collections.singletonMap(activationId, distance)));
        }
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

    /** Upstream translatable server names: the server language first, then the client's resources. */
    public String translate(String key) {
        String value = serverLanguage.get(key);
        return value != null ? value : I18n.format(key);
    }

    public Collection<VoicePlayerInfo> getPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    /**
     * The server's id of the local player. A 1.7.10 client keeps its session UUID, which an offline-mode server
     * replaces with the offline UUID of the name, so the voice player list is matched by name first.
     */
    public UUID localPlayerId(String name, UUID sessionId) {
        for (VoicePlayerInfo player : players.values()) {
            if (player.getPlayerNick().equals(name)) return player.getPlayerId();
        }
        return sessionId;
    }

    public VoicePlayerInfo getPlayer(UUID playerId) {
        return players.get(playerId);
    }

    public void putPlayers(Collection<VoicePlayerInfo> infos) {
        if (!connected) return;
        infos.forEach(this::putPlayer);
    }

    public void putPlayer(VoicePlayerInfo info) {
        if (connected) players.put(info.getPlayerId(), info);
    }

    public void removePlayer(UUID playerId) {
        players.remove(playerId);
    }

    /** Upstream ModServerConnection.close(): the player list goes with the UDP connection. */
    public void clearPlayers() {
        players.clear();
    }

    @Override
    public void close() {
        connected = false;
        clearConfig();
        players.clear();
        if (udp != null) udp.close();
    }

    /** One instance per UDP client. Late worker updates cannot revive a closed endpoint. */
    @SideOnly(Side.CLIENT)
    public static final class UdpState implements AutoCloseable {
        @Getter
        private volatile InetSocketAddress remoteAddress;
        @Getter
        private volatile boolean confirmed;
        /** Upstream soft keep-alive timeout: no server ping for 7 seconds, shown with the disconnected icon. */
        @Getter
        @Setter
        private volatile boolean timedOut;
        private boolean closed;

        public synchronized void opened(InetSocketAddress remoteAddress) {
            if (!closed) this.remoteAddress = Objects.requireNonNull(remoteAddress);
        }

        public synchronized boolean isClosed() {
            return closed;
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
