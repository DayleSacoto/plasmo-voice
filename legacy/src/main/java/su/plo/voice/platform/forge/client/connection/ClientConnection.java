package su.plo.voice.platform.forge.client.connection;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collections;
import java.util.Locale;
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
import su.plo.voice.platform.forge.client.audio.VoicePlayback;
import su.plo.voice.platform.forge.client.hud.DistanceVisualizer;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;
import su.plo.voice.proto.packets.tcp.clientbound.DistanceVisualizePacket;
import su.plo.voice.proto.packets.tcp.clientbound.LanguagePacket;
import su.plo.voice.proto.packets.tcp.serverbound.LanguageRequestPacket;
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
    private static final VoiceDebug DEBUG = VoiceDebug.CLIENT;

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
    private VoicePlayback playback;
    /** Client thread writes, capture thread reads. */
    private volatile boolean serverMuted;
    /** Client language of the last LanguageRequestPacket; null until the config is accepted. */
    private String requestedLanguage;

    public ClientConnection(VoiceChannel channel, NetworkManager connection, ClientState clientState) {
        this.channel = Objects.requireNonNull(channel);
        this.connection = Objects.requireNonNull(connection);
        this.clientState = Objects.requireNonNull(clientState);
        this.state = clientState.openConnection(channel::sendToServer);
        state.setPacketSender(channel::sendToServer);
        LOGGER.debug("Voice client state opened: voiceDisabled={}, microphoneMuted={}, configured={}",
                clientState.isVoiceDisabled(), clientState.isMicrophoneMuted(), state.isConfigured());
        DEBUG.log(Category.STATE, "voice client state opened: server={}, voiceDisabled={}, microphoneMuted={}, thread={}",
                connection.getSocketAddress(), clientState.isVoiceDisabled(), clientState.isMicrophoneMuted(), VoiceDebug.thread());
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
        } else if (packet instanceof DistanceVisualizePacket) {
            DistanceVisualizePacket visualize = (DistanceVisualizePacket) packet;
            DistanceVisualizer.show(visualize.getRadius(), visualize.getHexColor(), visualize.getPosition());
        } else if (packet instanceof LanguagePacket) {
            state.setServerLanguage(((LanguagePacket) packet).getLanguage());
        } else if (packet instanceof SourceAudioEndPacket) {
            ClientVoiceSources current = sources;
            if (current != null) current.onAudioEnd((SourceAudioEndPacket) packet);
        }
    }

    /** UDP worker thread. SelfAudioInfoPacket only feeds upstream's addon API (self source infos), so it is ignored. */
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

    /** Client thread, every frame. */
    public void updatePlayback(float partialTicks) {
        if (playback != null) playback.updatePositions(partialTicks);
        // Upstream VoiceAudioCapture.isServerMuted: read by the capture thread.
        EntityPlayer self = Minecraft.getMinecraft().thePlayer;
        VoicePlayerInfo local = self == null ? null
                : state.getPlayer(state.localPlayerId(self.getCommandSenderName(), self.getUniqueID()));
        serverMuted = local != null && local.isMuted();
        if (DEBUG.enabled() && udpClient != null) udpClient.checkWorker(System.currentTimeMillis());
        // Upstream LanguageChangedEvent: the server sends the translations of the new language.
        if (requestedLanguage != null && !requestedLanguage.equals(clientLanguage())) requestLanguage();
    }

    /** Upstream requests the language once the server info is initialized. */
    private void requestLanguage() {
        requestedLanguage = clientLanguage();
        channel.sendToServer(new LanguageRequestPacket(requestedLanguage));
    }

    /** 1.7.10 codes are like en_US; upstream language files are lower case. */
    private static String clientLanguage() {
        return Minecraft.getMinecraft().gameSettings.language.toLowerCase(Locale.ROOT);
    }

    /** TCP writes stay on the client thread; a request for a replaced config is dropped. */
    private void requestSourceInfo(ClientVoiceSources owner, UUID sourceId) {
        Minecraft.getMinecraft().func_152344_a(() -> {
            if (state.isConnected() && sources == owner) channel.sendToServer(new SourceInfoRequestPacket(sourceId));
        });
    }

    private void handle(PlayerDisconnectPacket packet) {
        EntityPlayer self = Minecraft.getMinecraft().thePlayer;
        if (self != null && state.localPlayerId(self.getCommandSenderName(), self.getUniqueID()).equals(packet.getPlayerId())) {
            // Upstream: the server dropped our UDP session; its PlayerInfoRequest restarts the handshake.
            if (DEBUG.enabled()) {
                DEBUG.log(Category.STATE, "PlayerDisconnectPacket for the local player: UDP generation={} closed by server; "
                        + "waiting for a new handshake", udpClient == null ? "none" : udpClient.getGeneration());
            }
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
            host = connection.getSocketAddress() instanceof InetSocketAddress
                    ? ((InetSocketAddress) connection.getSocketAddress()).getHostString() : "127.0.0.1";
        }
        UdpClient previous = udpClient;
        udpClient = new UdpClient(LOGGER, packet.getSecret(), host, packet.getPort(), state.replaceUdp(), this::onUdpAudio);
        LOGGER.info("Connecting to voice chat {}:{}", host, packet.getPort());
        if (DEBUG.enabled()) {
            DEBUG.log(Category.TCP, "ConnectionPacket: advertised={}:{}, resolvedHost={}, generation={}, replacing={}",
                    packet.getIp(), packet.getPort(), host, udpClient.getGeneration(),
                    previous == null ? "none" : "generation " + previous.getGeneration());
        }
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
        LOGGER.debug("Voice client state closed: connected={}, udpEndpoint={}, udpConfirmed={}, configured={}; voiceDisabled={}, microphoneMuted={}",
                state.isConnected(), state.hasUdpEndpoint(), state.isUdpConfirmed(), state.isConfigured(),
                clientState.isVoiceDisabled(), clientState.isMicrophoneMuted());
        DEBUG.log(Category.STATE, "voice client state closed: thread={}", VoiceDebug.thread());
    }

    private void handle(ConfigPacket packet) {
        LOGGER.debug("ConfigPacket received");
        if (DEBUG.enabled()) {
            DEBUG.log(Category.TCP, "ConfigPacket: serverId={}, sampleRate={}, mtu={}, codec={}, encryption={}, activations={}, "
                            + "sourceLines={}, udpGeneration={}, udpConfirmed={}, reload={}",
                    packet.getServerId(), packet.getCaptureInfo().getSampleRate(), packet.getCaptureInfo().getMtuSize(),
                    packet.getCaptureInfo().getEncoderInfo() == null ? "none" : packet.getCaptureInfo().getEncoderInfo().getName(),
                    packet.getEncryption() == null ? "none" : packet.getEncryption().getAlgorithm(),
                    packet.getActivations().size(), packet.getSourceLines().size(),
                    udpClient == null ? "none" : udpClient.getGeneration(), state.isUdpConfirmed(), state.isConfigured());
        }
        if (udpClient == null || udpClient.getRemoteAddress() == null) {
            LOGGER.warn("Config packet is received before UDP is connected");
            return;
        }
        try {
            ClientConfig accepted = ClientConfig.decode(packet, getKeyPair().getPrivate());
            // Upstream DistanceVisualizeOnJoinListener: only the config of a new voice connection, not a reload.
            boolean joined = !state.isConfigured();
            // A server reload sends the config again: the previous capture and playback stop first.
            clearConfig();
            state.acceptConfig(accepted);
            ClientVoiceSources created = new ClientVoiceSources(clientState::isVoiceDisabled,
                    info -> clientState.isMuted(accepted, info),
                    sourceId -> requestSourceInfo(this.sources, sourceId));
            sources = created;
            state.setSources(created);
            VoicePlayback startedPlayback = new VoicePlayback(accepted, clientState, created);
            playback = startedPlayback;
            startedPlayback.start();
            VoiceCapture startedCapture = new VoiceCapture(accepted, clientState, udpClient,
                    () -> proximityDistance(accepted),
                    end -> sendFromClientThread(end), () -> serverMuted);
            capture = startedCapture;
            startedCapture.start();
            if (accepted.getAesKey() != null) LOGGER.debug("RSA encryption data decrypted; algorithm={}",
                    packet.getEncryption().getAlgorithm());
            DEBUG.log(Category.STATE, "voice configured: encryption={}, capture and playback started",
                    accepted.getAesKey() != null ? "decrypted" : "none");
            LOGGER.info("Voice configuration accepted: serverId={}, sampleRate={}, mtu={}, codec={}",
                    packet.getServerId(), packet.getCaptureInfo().getSampleRate(),
                    packet.getCaptureInfo().getMtuSize(),
                    packet.getCaptureInfo().getEncoderInfo() == null ? "none" : packet.getCaptureInfo().getEncoderInfo().getName());
            accepted.activationDistances(activationId -> clientState.getActivationDistance(packet.getServerId(), activationId))
                    .forEach((activationId, distance) -> channel.sendToServer(
                    new PlayerActivationDistancesPacket(Collections.singletonMap(activationId, distance))));
            // Settings changed after PlayerInfoPacket but before the server accepted state updates.
            clientState.syncState();
            requestLanguage();
            if (joined && clientState.isVisualizeVoiceDistanceOnJoin()) {
                DistanceVisualizer.show(proximityDistance(accepted), DistanceVisualizer.PROXIMITY_COLOR, null);
            }
        } catch (GeneralSecurityException e) {
            clearConfig();
            udpClient.close();
            udpClient = null;
            LOGGER.warn("Failed to decrypt voice configuration", e);
            DEBUG.log(Category.STATE, "RSA decryption of the voice configuration failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Capture thread: the player's current proximity distance for this server. */
    private int proximityDistance(ClientConfig config) {
        VoiceActivation proximity = config.activation(VoiceActivation.PROXIMITY_ID);
        return proximity == null ? 0 : ClientConfig.allowedDistance(proximity,
                clientState.getActivationDistance(config.getPacket().getServerId(), VoiceActivation.PROXIMITY_ID));
    }

    private void clearConfig() {
        if (state.isConfigured()) LOGGER.debug("Voice client config/encryption state cleared");
        state.clearConfig();
        sources = null;
        requestedLanguage = null;
        if (playback != null) playback.close();
        playback = null;
        if (capture != null) capture.close();
        capture = null;
    }

    private void handle(PlayerInfoRequestPacket packet) {
        if (DEBUG.enabled()) {
            DEBUG.log(Category.STATE, "PlayerInfoRequestPacket: replying with voiceDisabled={}, microphoneMuted={}, "
                            + "udpGeneration={}, configured={}", clientState.isVoiceDisabled(), clientState.isMicrophoneMuted(),
                    udpClient == null ? "none" : udpClient.getGeneration(), state.isConfigured());
        }
        channel.sendToServer(clientState.createPlayerInfo(
                "1.7.10",
                PlasmoVoiceMod.VERSION,
                getKeyPair().getPublic().getEncoded()
        ));
    }
}
