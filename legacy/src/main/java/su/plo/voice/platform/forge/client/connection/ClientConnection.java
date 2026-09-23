package su.plo.voice.platform.forge.client.connection;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.ClientVoiceSources;
import su.plo.voice.platform.forge.client.audio.VoiceCapture;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerActivationDistancesPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerDisconnectPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoUpdatePacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerListPacket;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.tcp.clientbound.SourceInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.SourceInfoRequestPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

@SideOnly(Side.CLIENT)
public final class ClientConnection implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");

    private final VoiceChannel channel;
    @Getter
    private final NetworkManager connection;

    private KeyPair keyPair;
    @Getter
    private ConnectionPacket connectionInfo;
    private UdpClient udpClient;
    @Getter
    private final ClientConnectionState state;
    private final ClientState clientState;
    /** Remote sources of the accepted config; read by the UDP worker. */
    private volatile ClientVoiceSources sources;
    private VoiceCapture capture;

    public ClientConnection(VoiceChannel channel, NetworkManager connection, ClientState clientState) {
        this.channel = Objects.requireNonNull(channel);
        this.connection = Objects.requireNonNull(connection);
        this.clientState = Objects.requireNonNull(clientState);
        this.state = clientState.openConnection(channel::sendToServer);
        LOGGER.info("Voice client state opened: voiceDisabled={}, microphoneMuted={}, configured={}",
                clientState.isVoiceDisabled(), clientState.isMicrophoneMuted(), state.isConfigured());
    }

    public ClientConfig getConfig() {
        return state.getConfig();
    }

    public KeyPair getKeyPair() {
        if (keyPair == null) {
            throw new IllegalStateException("KeyPair is not initialized");
        }

        return keyPair;
    }

    public void generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        keyPair = generator.generateKeyPair();
    }

    public void handle(Packet<?> packet) {
        if (packet instanceof PlayerInfoRequestPacket) {
            handle((PlayerInfoRequestPacket) packet);
        } else if (packet instanceof ConnectionPacket) {
            handle((ConnectionPacket) packet);
        } else if (packet instanceof ConfigPacket) {
            handle((ConfigPacket) packet);
        } else if (packet instanceof PlayerListPacket) {
            state.putPlayers(((PlayerListPacket) packet).getPlayers());
        } else if (packet instanceof PlayerInfoUpdatePacket) {
            state.putPlayer(((PlayerInfoUpdatePacket) packet).getPlayerInfo());
        } else if (packet instanceof PlayerDisconnectPacket) {
            handle((PlayerDisconnectPacket) packet);
        } else if (packet instanceof SourceInfoPacket) {
            ClientVoiceSources current = sources;
            if (current != null) current.updateSourceInfo(((SourceInfoPacket) packet).getSourceInfo());
        } else if (packet instanceof SourceAudioEndPacket) {
            ClientVoiceSources current = sources;
            if (current != null) current.onAudioEnd((SourceAudioEndPacket) packet);
        }
    }

    /** UDP worker thread. SelfAudioInfoPacket feeds the talking indicator, which arrives with the HUD. */
    private void onUdpAudio(Packet<?> packet) {
        ClientVoiceSources current = sources;
        if (current != null && packet instanceof SourceAudioPacket) current.onAudio((SourceAudioPacket) packet);
    }

    /** The capture thread cannot write TCP; the packet is dropped if the connection closed meanwhile. */
    private void sendFromClientThread(Packet<?> packet) {
        Minecraft.getMinecraft().func_152344_a(() -> {
            if (state.isConnected()) channel.sendToServer(packet);
        });
    }

    /** TCP writes stay on the client thread; a request for a replaced config is dropped. */
    private void requestSourceInfo(ClientVoiceSources owner, UUID sourceId) {
        Minecraft.getMinecraft().func_152344_a(() -> {
            if (state.isConnected() && sources == owner) channel.sendToServer(new SourceInfoRequestPacket(sourceId));
        });
    }

    private void handle(PlayerDisconnectPacket packet) {
        EntityPlayer self = Minecraft.getMinecraft().thePlayer;
        if (self != null && self.getUniqueID().equals(packet.getPlayerId())) {
            // Upstream: the server dropped our UDP session; its PlayerInfoRequest restarts the handshake.
            clearConfig();
            if (udpClient != null) udpClient.close();
            udpClient = null;
            LOGGER.info("Voice UDP session closed by server; waiting for a new handshake");
            return;
        }
        state.removePlayer(packet.getPlayerId());
    }

    private void handle(ConnectionPacket packet) {
        clearConfig();
        if (udpClient != null) udpClient.close();
        connectionInfo = packet;
        String host = packet.getIp();
        if ("0.0.0.0".equals(host)) {
            host = connection.getSocketAddress() instanceof java.net.InetSocketAddress
                    ? ((java.net.InetSocketAddress) connection.getSocketAddress()).getHostString() : "127.0.0.1";
        }
        udpClient = new UdpClient(LOGGER, packet.getSecret(), host, packet.getPort(), state.replaceUdp(), this::onUdpAudio);
        LOGGER.info("ConnectionPacket received: host={}, port={}, session present; voice configuration pending",
                packet.getIp(), packet.getPort());
        udpClient.start();
    }

    @Override
    public void close() {
        clearConfig();
        state.close();
        if (udpClient != null) udpClient.close();
        udpClient = null;
        connectionInfo = null;
        keyPair = null;
        LOGGER.info("Voice client state closed: connected={}, udpEndpoint={}, udpConfirmed={}, configured={}; voiceDisabled={}, microphoneMuted={}",
                state.isConnected(), state.hasUdpEndpoint(), state.isUdpConfirmed(), state.isConfigured(),
                clientState.isVoiceDisabled(), clientState.isMicrophoneMuted());
    }

    private void handle(ConfigPacket packet) {
        LOGGER.info("ConfigPacket received");
        if (udpClient == null || udpClient.getRemoteAddress() == null) {
            LOGGER.warn("Config packet is received before UDP is connected");
            return;
        }
        try {
            ClientConfig accepted = ClientConfig.decode(packet, getKeyPair().getPrivate());
            state.acceptConfig(accepted);
            ClientVoiceSources created = new ClientVoiceSources(accepted, clientState::isVoiceDisabled,
                    sourceId -> requestSourceInfo(this.sources, sourceId),
                    // Positional playback consumes decoded PCM once the OpenAL output exists.
                    (source, sequenceNumber, pcm) -> {});
            sources = created;
            VoiceCapture startedCapture = new VoiceCapture(accepted, clientState, udpClient,
                    accepted.activationDistances().getOrDefault(VoiceActivation.PROXIMITY_ID, 0),
                    end -> sendFromClientThread(end));
            capture = startedCapture;
            startedCapture.start();
            if (accepted.getAesKey() != null) LOGGER.info("RSA encryption data decrypted; algorithm={}",
                    packet.getEncryption().getAlgorithm());
            LOGGER.info("Voice configuration accepted: serverId={}, sampleRate={}, mtu={}, codec={}; audio not started",
                    packet.getServerId(), packet.getCaptureInfo().getSampleRate(),
                    packet.getCaptureInfo().getMtuSize(),
                    packet.getCaptureInfo().getEncoderInfo() == null ? "none" : packet.getCaptureInfo().getEncoderInfo().getName());
            accepted.activationDistances().forEach((activationId, distance) -> channel.sendToServer(
                    new PlayerActivationDistancesPacket(Collections.singletonMap(activationId, distance))));
            // Settings changed after PlayerInfoPacket but before the server accepted state updates.
            clientState.syncState();
        } catch (GeneralSecurityException e) {
            clearConfig();
            udpClient.close();
            udpClient = null;
            LOGGER.warn("Failed to decrypt voice configuration", e);
        }
    }

    private void clearConfig() {
        if (state.isConfigured()) LOGGER.info("Voice client config/encryption state cleared");
        state.clearConfig();
        ClientVoiceSources current = sources;
        sources = null;
        if (current != null) current.close();
        if (capture != null) capture.close();
        capture = null;
    }

    private void handle(PlayerInfoRequestPacket packet) {
        channel.sendToServer(clientState.createPlayerInfo(
                "1.7.10",
                PlasmoVoiceMod.VERSION,
                getKeyPair().getPublic().getEncoded()
        ));
    }
}
