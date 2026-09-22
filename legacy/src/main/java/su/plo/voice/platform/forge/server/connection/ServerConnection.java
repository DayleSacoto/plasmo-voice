package su.plo.voice.platform.forge.server.connection;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.platform.forge.network.VoiceChannel;
import org.apache.logging.log4j.LogManager;

@Getter
@RequiredArgsConstructor
public final class ServerConnection {

    @NonNull
    private final EntityPlayerMP player;

    private PublicKey publicKey;
    private String modVersion;
    private String minecraftVersion;
    private boolean voiceDisabled;
    private boolean microphoneMuted;
    private UdpServer.Session udpSession;
    private boolean connectionInfoSent;

    public void prepareUdp(UdpServer server) {
        udpSession = server.createSession(player.getUniqueID());
        connectionInfoSent = false;
    }

    public void tick(UdpServer server, VoiceChannel channel) {
        if (udpSession == null) return;
        if (!udpSession.isActive()) {
            udpSession = null;
            channel.sendToPlayer(player, new PlayerInfoRequestPacket());
            return;
        }
        if (connectionInfoSent) return;
        ConnectionPacket packet = server.connectionPacket(udpSession);
        if (packet == null) return;
        channel.sendToPlayer(player, packet);
        connectionInfoSent = true;
        LogManager.getLogger("Plasmo Voice").info("ConnectionPacket sent to {}: host={}, port={}, session present",
                player.getCommandSenderName(), packet.getIp(), packet.getPort());
    }

    public void handle(PlayerInfoPacket packet) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec publicKeySpec =
                new X509EncodedKeySpec(packet.getPublicKey());

        publicKey = keyFactory.generatePublic(publicKeySpec);

        modVersion = packet.getVersion();
        minecraftVersion = packet.getMinecraftVersion();
        voiceDisabled = packet.isVoiceDisabled();
        microphoneMuted = packet.isMicrophoneMuted();
    }
}
