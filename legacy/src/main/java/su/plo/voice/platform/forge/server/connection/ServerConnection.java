package su.plo.voice.platform.forge.server.connection;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;
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
    private boolean configSent;

    public void prepareUdp(UdpServer server) {
        udpSession = server.createSession(player.getUniqueID());
        connectionInfoSent = false;
        configSent = false;
    }

    public void tick(UdpServer server, VoiceChannel channel, ServerConfig config) {
        if (udpSession == null) return;
        if (!udpSession.isActive()) {
            udpSession = null;
            channel.sendToPlayer(player, new PlayerInfoRequestPacket());
            return;
        }
        if (connectionInfoSent) {
            // The UDP worker publishes authentication through the volatile session flag.
            // TCP writes stay on the Minecraft server tick thread.
            synchronized (udpSession) {
                if (configSent || !udpSession.isActive() || !udpSession.isAuthenticated()) return;
                try {
                    channel.sendToPlayer(player, config.createPacket(publicKey));
                    configSent = true;
                    LogManager.getLogger("Plasmo Voice").info("ConfigPacket sent to {} after UDP authentication",
                            player.getCommandSenderName());
                } catch (java.security.GeneralSecurityException e) {
                    server.removeSession(player.getUniqueID());
                    LogManager.getLogger("Plasmo Voice").warn("Failed to encrypt voice configuration", e);
                }
            }
            return;
        }
        ConnectionPacket packet = server.connectionPacket(udpSession);
        if (packet == null) return;
        channel.sendToPlayer(player, packet);
        connectionInfoSent = true;
        LogManager.getLogger("Plasmo Voice").info("ConnectionPacket sent to {}: host={}, port={}, session present",
                player.getCommandSenderName(), packet.getIp(), packet.getPort());
    }

    /**
     * Upstream accepts live state only while the player has voice chat, i.e. after the first UDP ping.
     * Returns whether the state was applied. PlayerInfoUpdate broadcast needs the player list layer.
     */
    public boolean handle(PlayerStatePacket packet) {
        UdpServer.Session session = udpSession;
        if (session == null || !session.isActive() || !session.isAuthenticated()) return false;
        voiceDisabled = packet.isVoiceDisabled();
        microphoneMuted = packet.isMicrophoneMuted();
        return true;
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
