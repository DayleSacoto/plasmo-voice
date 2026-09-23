package su.plo.voice.platform.forge.server.connection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.SelfSourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerDisconnectPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoUpdatePacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerListPacket;
import su.plo.voice.proto.packets.tcp.clientbound.SelfSourceInfoPacket;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.tcp.clientbound.SourceInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;

/** Server-thread registry of voice connections and the upstream player list broadcasts. */
@RequiredArgsConstructor
public final class ServerVoicePlayers {
    private final VoiceChannel channel;
    private final Map<UUID, ServerConnection> connections = new HashMap<>();

    public ServerConnection get(UUID playerId) {
        return connections.get(playerId);
    }

    public void put(ServerConnection connection) {
        connections.put(connection.getPlayer().getUniqueID(), connection);
    }

    /** Logout: other voice players drop the entry, like upstream UDP disconnect. */
    public void remove(UUID playerId) {
        ServerConnection connection = connections.remove(playerId);
        if (connection != null && connection.isVoiceConnected()) broadcast(new PlayerDisconnectPacket(playerId));
    }

    public void clear() {
        connections.clear();
    }

    public void stateChanged(ServerConnection connection, long now) {
        if (connection.stateThrottle.changed(now)) broadcast(new PlayerInfoUpdatePacket(connection.createPlayerInfo()));
    }

    public void tick(UdpServer server, ServerConfig config, long now) {
        for (ServerConnection connection : connections.values()) {
            switch (connection.tick(server, channel, config)) {
                case VOICE_CONNECTED:
                    channel.sendToPlayer(connection.getPlayer(), new PlayerListPacket(voicePlayers()));
                    broadcast(new PlayerInfoUpdatePacket(connection.createPlayerInfo()));
                    break;
                case VOICE_DISCONNECTED:
                    // Upstream also tells the player itself, which makes its client drop the dead UDP client.
                    PlayerDisconnectPacket disconnect = new PlayerDisconnectPacket(connection.getPlayer().getUniqueID());
                    broadcast(disconnect);
                    channel.sendToPlayer(connection.getPlayer(), disconnect);
                    connection.requestPlayerInfo(channel);
                    break;
                case SESSION_LOST:
                    connection.requestPlayerInfo(channel);
                    break;
                default:
                    break;
            }
            if (connection.stateThrottle.due(now) && connection.isVoiceConnected()) {
                broadcast(new PlayerInfoUpdatePacket(connection.createPlayerInfo()));
            }
        }
        for (ServerConnection connection : connections.values()) {
            UdpServer.Session session = connection.getUdpSession();
            if (session == null) continue;
            session.setPresence(connection.presence());
        }
        for (ServerConnection connection : connections.values()) {
            UdpServer.Session session = connection.getUdpSession();
            // The UDP worker cannot write TCP, so the first frame of a source is announced here.
            if (session != null && session.getLastDistance() >= 0 && session.getSourceInfoDirty().compareAndSet(true, false)) {
                PlayerSourceInfo sourceInfo = sourceInfo(connection, session, config);
                sendToListeners(session, session.getLastDistance(), new SourceInfoPacket(sourceInfo));
                channel.sendToPlayer(connection.getPlayer(), new SelfSourceInfoPacket(new SelfSourceInfo(
                        sourceInfo, connection.getPlayer().getUniqueID(), config.getProximityActivation().getId(), -1L)));
            }
        }
    }

    /** Upstream PlayerChannelHandler.handle(SourceInfoRequestPacket). */
    public void handleSourceInfoRequest(ServerConnection requester, UUID sourceId, ServerConfig config) {
        if (!requester.isVoiceConnected() || requester.isVoiceDisabled()) return;
        for (ServerConnection connection : connections.values()) {
            UdpServer.Session session = connection.getUdpSession();
            if (session == null || !session.getSourceId().equals(sourceId)) continue;
            if (connection != requester) {
                channel.sendToPlayer(requester.getPlayer(), new SourceInfoPacket(sourceInfo(connection, session, config)));
            }
            return;
        }
    }

    /** Upstream PlayerChannelHandler.handle(PlayerAudioEndPacket) with the proximity activation. */
    public void handleAudioEnd(ServerConnection speaker, PlayerAudioEndPacket packet, ServerConfig config) {
        UdpServer.Session session = speaker.getUdpSession();
        VoiceActivation activation = config.getProximityActivation();
        if (session == null || !speaker.isVoiceConnected() || speaker.isMicrophoneMuted()
                || !activation.getId().equals(packet.getActivationId())) return;
        short distance = (short) activation.calculateAllowedDistance(packet.getDistance());
        sendToListeners(session, distance, new SourceAudioEndPacket(session.getSourceId(), packet.getSequenceNumber()));
    }

    private void sendToListeners(UdpServer.Session speaker, short distance, Packet<?> packet) {
        for (ServerConnection connection : connections.values()) {
            UdpServer.Session session = connection.getUdpSession();
            if (session != null && UdpServer.isListener(speaker, session, distance)) {
                channel.sendToPlayer(connection.getPlayer(), packet);
            }
        }
    }

    private static PlayerSourceInfo sourceInfo(ServerConnection connection, UdpServer.Session session, ServerConfig config) {
        return new PlayerSourceInfo("plasmovoice", session.getSourceId(), config.getProximityLine().getId(), null,
                session.getSourceState(), new OpusDecoderInfo(), false, true, 0, connection.createPlayerInfo());
    }

    private List<VoicePlayerInfo> voicePlayers() {
        List<VoicePlayerInfo> players = new ArrayList<>();
        for (ServerConnection connection : connections.values()) {
            if (connection.isVoiceConnected()) players.add(connection.createPlayerInfo());
        }
        return players;
    }

    private void broadcast(Packet<?> packet) {
        for (ServerConnection connection : connections.values()) {
            if (connection.isVoiceConnected()) channel.sendToPlayer(connection.getPlayer(), packet);
        }
    }
}
