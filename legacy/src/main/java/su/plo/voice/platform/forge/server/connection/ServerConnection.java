package su.plo.voice.platform.forge.server.connection;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerActivationDistancesPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.platform.forge.network.VoiceChannel;
import org.apache.logging.log4j.LogManager;

@Getter
@RequiredArgsConstructor
public final class ServerConnection {
    public static final String DEFAULT_CLIENT_MOD_MIN_VERSION = "2.0.0";
    private static final String MODRINTH_LINK = "https://modrinth.com/plugin/plasmo-voice";
    private static final Pattern MINECRAFT_VERSION_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{1,32}");

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
    /** Upstream hasVoiceChat(): from the config sent after UDP authentication until the session is lost. */
    private boolean voiceConnected;
    @Getter(AccessLevel.NONE)
    final StateBroadcastThrottle stateThrottle = new StateBroadcastThrottle();
    private final Map<UUID, Integer> activationDistances = new HashMap<>();

    public void prepareUdp(UdpServer server) {
        udpSession = server.createSession(player.getUniqueID());
        connectionInfoSent = false;
        configSent = false;
    }

    /** Server-thread handshake step; the result tells the player registry what to broadcast. */
    TickResult tick(UdpServer server, VoiceChannel channel, ServerConfig config) {
        if (udpSession == null) return TickResult.NONE;
        if (!udpSession.isActive()) {
            udpSession = null;
            boolean hadVoiceChat = voiceConnected;
            voiceConnected = false;
            // Upstream BaseVoicePlayer.reset() on UDP disconnect.
            activationDistances.clear();
            return hadVoiceChat ? TickResult.VOICE_DISCONNECTED : TickResult.SESSION_LOST;
        }
        if (connectionInfoSent) {
            // The UDP worker publishes authentication through the volatile session flag.
            // TCP writes stay on the Minecraft server tick thread.
            synchronized (udpSession) {
                if (configSent || !udpSession.isActive() || !udpSession.isAuthenticated()) return TickResult.NONE;
                try {
                    channel.sendToPlayer(player, config.createPacket(publicKey));
                    configSent = true;
                    voiceConnected = true;
                    LogManager.getLogger("Plasmo Voice").info("ConfigPacket sent to {} after UDP authentication",
                            player.getCommandSenderName());
                    return TickResult.VOICE_CONNECTED;
                } catch (java.security.GeneralSecurityException e) {
                    server.removeSession(player.getUniqueID());
                    LogManager.getLogger("Plasmo Voice").warn("Failed to encrypt voice configuration", e);
                }
            }
            return TickResult.NONE;
        }
        ConnectionPacket packet = server.connectionPacket(udpSession);
        if (packet == null) return TickResult.NONE;
        channel.sendToPlayer(player, packet);
        connectionInfoSent = true;
        LogManager.getLogger("Plasmo Voice").info("ConnectionPacket sent to {}: host={}, port={}, session present",
                player.getCommandSenderName(), packet.getIp(), packet.getPort());
        return TickResult.NONE;
    }

    void requestPlayerInfo(VoiceChannel channel) {
        channel.sendToPlayer(player, new PlayerInfoRequestPacket());
    }

    /** Snapshot for the UDP worker; EntityPlayerMP must only be read on the server thread. */
    UdpServer.Presence presence() {
        return new UdpServer.Presence(voiceConnected, voiceDisabled, microphoneMuted,
                player.dimension, player.posX, player.posY, player.posZ);
    }

    VoicePlayerInfo createPlayerInfo() {
        // No server mute manager yet, so "muted" (server-side mute) is always false.
        return new VoicePlayerInfo(player.getUniqueID(), player.getCommandSenderName(), false,
                voiceDisabled, microphoneMuted);
    }

    /**
     * Upstream accepts live state only while the player has voice chat, i.e. after the first UDP ping.
     * Returns whether the state was applied and actually changed.
     */
    public boolean handle(PlayerStatePacket packet) {
        UdpServer.Session session = udpSession;
        if (session == null || !session.isActive() || !session.isAuthenticated()) return false;
        boolean changed = voiceDisabled != packet.isVoiceDisabled() || microphoneMuted != packet.isMicrophoneMuted();
        voiceDisabled = packet.isVoiceDisabled();
        microphoneMuted = packet.isMicrophoneMuted();
        return changed;
    }

    public void handle(PlayerActivationDistancesPacket packet, ServerConfig config) {
        activationDistances.putAll(config.knownActivationDistances(packet.getDistanceByActivationId()));
    }

    /** Upstream PlayerChannelHandler.handle(PlayerInfoPacket): nothing changes unless the client is accepted. */
    public PlayerInfoResult handle(PlayerInfoPacket packet, String serverVersion, String clientModMinVersion) {
        PlayerInfoResult result = checkVersion(packet.getVersion(), serverVersion, clientModMinVersion);
        if (result != PlayerInfoResult.ACCEPTED) return result;

        PublicKey key = decodePublicKey(packet.getPublicKey());
        if (key == null) return PlayerInfoResult.INVALID_PUBLIC_KEY;

        publicKey = key;
        modVersion = packet.getVersion();
        minecraftVersion = packet.getMinecraftVersion();
        voiceDisabled = packet.isVoiceDisabled();
        microphoneMuted = packet.isMicrophoneMuted();
        return PlayerInfoResult.ACCEPTED;
    }

    static PlayerInfoResult checkVersion(String clientVersion, String serverVersion, String clientModMinVersion) {
        VoiceVersion client;
        try {
            client = VoiceVersion.parse(clientVersion);
        } catch (IllegalArgumentException e) {
            return PlayerInfoResult.MALFORMED_VERSION;
        }
        if (client.major != VoiceVersion.parse(serverVersion).major) return PlayerInfoResult.UNSUPPORTED_VERSION;

        VoiceVersion minVersion = VoiceVersion.parse(DEFAULT_CLIENT_MOD_MIN_VERSION);
        try {
            minVersion = VoiceVersion.parse(clientModMinVersion);
        } catch (IllegalArgumentException ignored) {
            // Upstream falls back to 2.0.0 for an unparsable config value.
        }
        return client.asInt() < minVersion.asInt() ? PlayerInfoResult.UNSUPPORTED_VERSION : PlayerInfoResult.ACCEPTED;
    }

    static PublicKey decodePublicKey(byte[] encoded) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Upstream ServerVersionUtil.suggestSupportedVersion with the en_us server translation. */
    public void suggestSupportedVersion(String clientMinecraftVersion) {
        String link = MINECRAFT_VERSION_PATTERN.matcher(clientMinecraftVersion).matches()
                ? MODRINTH_LINK + "/versions?g=" + clientMinecraftVersion
                : MODRINTH_LINK;
        ChatComponentText click = new ChatComponentText("Download supported version");
        click.setChatStyle(new ChatStyle()
                .setColor(EnumChatFormatting.YELLOW)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(link))));
        player.addChatMessage(new ChatComponentText("Sorry, your Plasmo Voice version is not supported on this server. ")
                .appendSibling(click));
    }

    enum TickResult {
        NONE,
        VOICE_CONNECTED,
        VOICE_DISCONNECTED,
        SESSION_LOST
    }

    public enum PlayerInfoResult {
        ACCEPTED,
        MALFORMED_VERSION,
        UNSUPPORTED_VERSION,
        INVALID_PUBLIC_KEY
    }
}
