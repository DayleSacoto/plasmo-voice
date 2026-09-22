package su.plo.voice.platform.forge.client.connection;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Objects;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

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

    public ClientConnection(VoiceChannel channel, NetworkManager connection, ClientState clientState) {
        this.channel = Objects.requireNonNull(channel);
        this.connection = Objects.requireNonNull(connection);
        this.clientState = Objects.requireNonNull(clientState);
        this.state = clientState.openConnection();
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
        }
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
        udpClient = new UdpClient(LOGGER, packet.getSecret(), host, packet.getPort(), state.replaceUdp());
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
            if (accepted.getAesKey() != null) LOGGER.info("RSA encryption data decrypted; algorithm={}",
                    packet.getEncryption().getAlgorithm());
            LOGGER.info("Voice configuration accepted: serverId={}, sampleRate={}, mtu={}, codec={}; audio not started",
                    packet.getServerId(), packet.getCaptureInfo().getSampleRate(),
                    packet.getCaptureInfo().getMtuSize(),
                    packet.getCaptureInfo().getEncoderInfo() == null ? "none" : packet.getCaptureInfo().getEncoderInfo().getName());
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
    }

    private void handle(PlayerInfoRequestPacket packet) {
        channel.sendToServer(clientState.createPlayerInfo(
                "1.7.10",
                PlasmoVoiceMod.VERSION,
                getKeyPair().getPublic().getEncoded()
        ));
    }
}
