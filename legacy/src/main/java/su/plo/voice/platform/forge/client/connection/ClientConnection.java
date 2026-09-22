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
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;

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

    public ClientConnection(VoiceChannel channel, NetworkManager connection) {
        this.channel = Objects.requireNonNull(channel);
        this.connection = Objects.requireNonNull(connection);
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
        }
    }

    private void handle(ConnectionPacket packet) {
        if (udpClient != null) udpClient.close();
        connectionInfo = packet;
        String host = packet.getIp();
        if ("0.0.0.0".equals(host)) {
            host = connection.getSocketAddress() instanceof java.net.InetSocketAddress
                    ? ((java.net.InetSocketAddress) connection.getSocketAddress()).getHostString() : "127.0.0.1";
        }
        udpClient = new UdpClient(LOGGER, packet.getSecret(), host, packet.getPort());
        LOGGER.info("ConnectionPacket received: host={}, port={}, session present; voice configuration pending",
                packet.getIp(), packet.getPort());
        udpClient.start();
    }

    @Override
    public void close() {
        if (udpClient != null) udpClient.close();
        udpClient = null;
        connectionInfo = null;
        keyPair = null;
    }

    private void handle(PlayerInfoRequestPacket packet) {
        channel.sendToServer(new PlayerInfoPacket(
                "1.7.10",
                PlasmoVoiceMod.VERSION,
                getKeyPair().getPublic().getEncoded(),
                false,
                false
        ));
    }
}
