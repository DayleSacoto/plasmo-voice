package su.plo.voice.platform.forge;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import su.plo.voice.platform.forge.server.ServerLanguages;
import su.plo.voice.platform.forge.server.VoiceCommands;
import su.plo.voice.platform.forge.server.VoiceMutes;
import su.plo.voice.platform.forge.client.VoiceControls;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.LanguageRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerActivationDistancesPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.tcp.serverbound.SourceInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;
import su.plo.voice.platform.forge.server.connection.ServerConnection;
import su.plo.voice.platform.forge.server.connection.UdpServer;
import su.plo.voice.platform.forge.server.connection.ServerConfig;
import su.plo.voice.platform.forge.server.connection.ServerSettings;
import su.plo.voice.platform.forge.server.connection.ServerVoicePlayers;

@Getter
@Mod(
        modid = PlasmoVoiceMod.MOD_ID,
        name = PlasmoVoiceMod.MOD_NAME,
        version = PlasmoVoiceMod.VERSION)
public final class PlasmoVoiceMod {

    public static final String MOD_ID = "plasmovoice";
    public static final String MOD_NAME = "Plasmo Voice";
    public static final String VERSION = "2.1.17";

    private static final Logger LOGGER = LogManager.getLogger(MOD_NAME);
    /** Upstream PlayerInfoRequestScheduler: resend delays until the client answers. */
    private static final long[] INFO_REQUEST_RETRY_MS = {1_000L, 3_000L, 5_000L, 10_000L, 15_000L};
    private static final long MUTE_EXPIRY_CHECK_MS = 5_000L;
    private static final VoiceDebug DEBUG = VoiceDebug.SERVER;

    private VoiceChannel voiceChannel;
    private UdpServer udpServer;
    private ServerConfig serverConfig;
    private ServerLanguages languages;
    private VoiceMutes mutes;
    private int minecraftPort;
    private long lastMuteExpiryCheck;

    private ServerVoicePlayers voicePlayers;
    /** Players that have not sent a voice packet yet (upstream PlayerInfoRequestScheduler and ModRequiredKickHandler). */
    @Getter(AccessLevel.NONE)
    private final Map<UUID, PendingPlayer> pendingPlayers = new HashMap<>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Initializing {} {} for Forge 1.7.10", MOD_NAME, VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        voiceChannel = new VoiceChannel(LOGGER);
        voicePlayers = new ServerVoicePlayers(voiceChannel);

        voiceChannel.setClientListener((connection, packet) -> {
            if (packet instanceof PlayerInfoRequestPacket) {
                LOGGER.debug("Received PlayerInfoRequestPacket from server");
            }
        });

        voiceChannel.setServerListener((player, packet) -> {
            // Upstream: any voice packet proves the mod is installed.
            pendingPlayers.remove(player.getUniqueID());
            if (serverConfig == null) return;
            // Exact class: PlayerInfoPacket extends PlayerStatePacket in the upstream protocol.
            if (packet.getClass() == PlayerStatePacket.class) {
                PlayerStatePacket state = (PlayerStatePacket) packet;
                ServerConnection connection = voicePlayers.get(player.getUniqueID());
                if (connection != null && connection.handle(state)) {
                    LOGGER.debug("Voice state updated for {}: voiceDisabled={}, microphoneMuted={}",
                            player.getCommandSenderName(), state.isVoiceDisabled(), state.isMicrophoneMuted());
                    voicePlayers.stateChanged(connection, System.currentTimeMillis());
                }
                return;
            }
            if (packet instanceof SourceInfoRequestPacket || packet instanceof PlayerAudioEndPacket) {
                ServerConnection connection = voicePlayers.get(player.getUniqueID());
                if (connection == null) return;
                if (packet instanceof SourceInfoRequestPacket) {
                    voicePlayers.handleSourceInfoRequest(connection, ((SourceInfoRequestPacket) packet).getSourceId(), serverConfig);
                } else {
                    voicePlayers.handleAudioEnd(connection, (PlayerAudioEndPacket) packet, serverConfig);
                }
                return;
            }
            if (packet instanceof PlayerActivationDistancesPacket) {
                ServerConnection connection = voicePlayers.get(player.getUniqueID());
                if (connection != null) connection.handle((PlayerActivationDistancesPacket) packet, serverConfig, voiceChannel);
                return;
            }
            if (packet instanceof LanguageRequestPacket) {
                ServerConnection connection = voicePlayers.get(player.getUniqueID());
                if (connection != null) {
                    voicePlayers.handleLanguageRequest(connection, ((LanguageRequestPacket) packet).getLanguage(),
                            System.currentTimeMillis());
                }
                return;
            }
            if (!(packet instanceof PlayerInfoPacket)) {
                return;
            }

            PlayerInfoPacket info = (PlayerInfoPacket) packet;
            ServerConnection existing = voicePlayers.get(player.getUniqueID());
            ServerConnection connection = existing != null ? existing : new ServerConnection(player);
            if (DEBUG.enabled()) {
                DEBUG.log(Category.STATE, "PlayerInfoPacket received: player={}, uuid={}, version={}, minecraft={}, "
                                + "voiceDisabled={}, microphoneMuted={}, existingConnection={}, udpSession={}",
                        player.getCommandSenderName(), player.getUniqueID(), info.getVersion(), info.getMinecraftVersion(),
                        info.isVoiceDisabled(), info.isMicrophoneMuted(), existing != null,
                        existing != null && existing.getUdpSession() != null
                                ? "generation " + existing.getUdpSession().getGeneration() : "none");
            }

            ServerConnection.PlayerInfoResult result = connection.handle(info, VERSION,
                    serverConfig.getSettings().getClientModMinVersion());
            if (result != ServerConnection.PlayerInfoResult.ACCEPTED) {
                if (result == ServerConnection.PlayerInfoResult.UNSUPPORTED_VERSION) {
                    connection.suggestSupportedVersion(info.getMinecraftVersion());
                }
                LOGGER.warn("Voice connection rejected for {}: {} (version={})",
                        player.getCommandSenderName(), result, info.getVersion());
                return;
            }

            connection.setServerMuted(mutes.isMuted(player.getUniqueID()));
            voicePlayers.put(connection);
            LOGGER.debug(
                    "Voice server connection initialized for {}: minecraft={}, version={}, key={}, voiceDisabled={}, microphoneMuted={}",
                    player.getCommandSenderName(),
                    connection.getMinecraftVersion(),
                    connection.getModVersion(),
                    connection.getPublicKey().getAlgorithm(),
                    connection.isVoiceDisabled(),
                    connection.isMicrophoneMuted()
            );
            if (udpServer != null) connection.prepareUdp(udpServer);
            if (DEBUG.enabled()) {
                DEBUG.log(Category.STATE, "voice connection {}: player={}, udpSession={}",
                        existing != null ? "replaced" : "created", player.getCommandSenderName(),
                        connection.getUdpSession() != null ? "generation " + connection.getUdpSession().getGeneration() : "none");
            }
        });

        FMLCommonHandler.instance().bus().register(this);
        if (event.getSide().isClient()) VoiceControls.register();

        LOGGER.info("{} initialized", MOD_NAME);
    }

    @SubscribeEvent
    public void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player.worldObj.isRemote) return;
        if (DEBUG.enabled()) {
            DEBUG.log(Category.STATE, "player left: player={}, uuid={}", event.player.getCommandSenderName(),
                    event.player.getUniqueID());
        }
        pendingPlayers.remove(event.player.getUniqueID());
        voicePlayers.remove(event.player.getUniqueID());
        if (udpServer != null) udpServer.removeSession(event.player.getUniqueID());
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) throws GeneralSecurityException {
        voicePlayers.clear();
        pendingPlayers.clear();
        ServerSettings settings = loadSettings();
        serverConfig = new ServerConfig(settings);
        applySettings(settings);
        mutes = new VoiceMutes(new File(configFolder(), "voice_mutes.json"), LOGGER);
        mutes.load();
        // getServerPort() is @SideOnly(SERVER): singleplayer has no fixed port, so upstream falls back to a random one.
        minecraftPort = event.getServer().isDedicatedServer() ? event.getServer().getServerPort() : -1;
        startUdpServer(settings);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        VoiceCommands.register(event, this);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || udpServer == null) return;
        long now = System.currentTimeMillis();
        if (DEBUG.enabled()) udpServer.checkWorker(now);
        voicePlayers.tick(udpServer, serverConfig, now);
        tickPendingPlayers(now);
        if (now - lastMuteExpiryCheck >= MUTE_EXPIRY_CHECK_MS) {
            lastMuteExpiryCheck = now;
            for (VoiceMutes.Mute mute : mutes.expired(now)) {
                unmute(mute.getPlayerId(), mute.isSilent() || !serverConfig.getSettings().isNotifyUnmuted());
            }
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (udpServer != null) udpServer.close();
        udpServer = null;
        serverConfig = null;
    }

    @SubscribeEvent
    public void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.player;

        LOGGER.debug("Sending PlayerInfoRequestPacket to {}", player.getCommandSenderName());
        if (DEBUG.enabled()) {
            DEBUG.log(Category.STATE, "player joined: player={}, uuid={}; PlayerInfoRequestPacket sent",
                    player.getCommandSenderName(), player.getUniqueID());
        }
        voiceChannel.sendToPlayer(player, new PlayerInfoRequestPacket());
        if (serverConfig != null) pendingPlayers.put(player.getUniqueID(), new PendingPlayer(player, System.currentTimeMillis()));
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        if (udpServer != null) udpServer.close();
        udpServer = null;
        serverConfig = null;
        voicePlayers.clear();
        pendingPlayers.clear();

        if (voiceChannel != null) {
            voiceChannel.clearServer();
        }
    }

    /**
     * Upstream VoiceReloadCommand: config and languages again, the same AES key, a new UDP server only when the
     * host changed (its players reconnect through the lost session), otherwise a new ConfigPacket for everyone.
     */
    public void reload() {
        ServerSettings settings = loadSettings();
        ServerConfig previous = serverConfig;
        try {
            serverConfig = new ServerConfig(settings, previous);
        } catch (GeneralSecurityException e) {
            LOGGER.error("Failed to reload the voice config", e);
            return;
        }
        applySettings(settings);
        if (udpServer == null || !settings.sameUdpEndpoint(previous.getSettings())) {
            if (udpServer != null) udpServer.close();
            startUdpServer(settings);
            return;
        }
        udpServer.setProximityActivation(serverConfig.getProximityActivation());
        udpServer.setMaxExtraBroadcastDistance(settings.getMaxExtraAudioBroadcastDistance());
        voicePlayers.resendConfig(serverConfig);
    }

    /** Upstream VoiceReconnectCommand: drop the UDP session and ask for the player info again. */
    public void reconnect(EntityPlayerMP player) {
        ServerConnection connection = voicePlayers.get(player.getUniqueID());
        if (DEBUG.enabled()) DEBUG.log(Category.STATE, "reconnect requested: player={}", player.getCommandSenderName());
        if (connection != null && connection.getUdpSession() != null && udpServer != null) {
            // The next tick reports the lost session, tells the voice players and requests the player info.
            udpServer.removeSession(player.getUniqueID());
        } else {
            voiceChannel.sendToPlayer(player, new PlayerInfoRequestPacket());
        }
    }

    /** Upstream VoiceMuteManager.mute. */
    public void mute(EntityPlayerMP player, UUID mutedBy, VoiceCommands.MuteDuration duration, long mutedToTime,
                     String reason, boolean silent) {
        if (mutes.mute(player.getUniqueID(), mutedBy, mutedToTime, reason, silent) == null) return;
        voicePlayers.setServerMuted(player.getUniqueID(), true);
        if (silent) return;
        String reasonText = formatReason(player, reason);
        sendMessage(player, mutedToTime > 0
                ? translate(player, "pv.mutes.temporarily_muted", duration.translate(this, player, System.currentTimeMillis()), reasonText)
                : translate(player, "pv.mutes.permanently_muted", reasonText));
    }

    /** Upstream VoiceMuteManager.unmute; false when the player was not muted. */
    public boolean unmute(UUID playerId, boolean silent) {
        if (mutes.unmute(playerId) == null) return false;
        voicePlayers.setServerMuted(playerId, false);
        EntityPlayerMP player = onlinePlayer(playerId);
        if (player != null && !silent) sendMessage(player, translate(player, "pv.mutes.unmuted"));
        return true;
    }

    /** An online player, a muted player known to usercache.json, or a UUID; no blocking profile lookups. */
    public GameProfile findProfile(String nameOrId) {
        try {
            UUID playerId = UUID.fromString(nameOrId);
            EntityPlayerMP online = onlinePlayer(playerId);
            if (online != null) return online.getGameProfile();
            GameProfile cached = MinecraftServer.getServer().func_152358_ax().func_152652_a(playerId);
            return cached != null ? cached : new GameProfile(playerId, nameOrId);
        } catch (IllegalArgumentException ignored) {
            // a name
        }
        EntityPlayerMP online = MinecraftServer.getServer().getConfigurationManager().func_152612_a(nameOrId);
        if (online != null) return online.getGameProfile();
        for (VoiceMutes.Mute mute : mutes.all()) {
            GameProfile cached = MinecraftServer.getServer().func_152358_ax().func_152652_a(mute.getPlayerId());
            if (cached != null && cached.getName().equalsIgnoreCase(nameOrId)) return cached;
        }
        return null;
    }

    /** A server message in the language of its receiver (the player's client language, or the default). */
    public String translate(ICommandSender receiver, String key, Object... args) {
        return languages.format(language(receiver), key, args);
    }

    public String formatReason(ICommandSender receiver, String reason) {
        return reason == null ? translate(receiver, "pv.mutes.empty_reason") : reason;
    }

    public void sendMessage(ICommandSender receiver, String text) {
        for (String line : text.split("\n")) receiver.addChatMessage(new ChatComponentText(line));
    }

    private static String language(ICommandSender receiver) {
        if (!(receiver instanceof EntityPlayerMP)) return null;
        String language = ReflectionHelper.getPrivateValue(EntityPlayerMP.class, (EntityPlayerMP) receiver,
                "field_71148_cg", "translator");
        return language == null ? null : language.toLowerCase(Locale.ROOT);
    }

    private static EntityPlayerMP onlinePlayer(UUID playerId) {
        for (Object player : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            if (((EntityPlayerMP) player).getUniqueID().equals(playerId)) return (EntityPlayerMP) player;
        }
        return null;
    }

    private void tickPendingPlayers(long now) {
        ServerSettings settings = serverConfig.getSettings();
        Iterator<PendingPlayer> iterator = pendingPlayers.values().iterator();
        while (iterator.hasNext()) {
            PendingPlayer pending = iterator.next();
            if (pending.attempt < INFO_REQUEST_RETRY_MS.length
                    && now - pending.lastRequest >= INFO_REQUEST_RETRY_MS[pending.attempt]) {
                pending.attempt++;
                pending.lastRequest = now;
                voiceChannel.sendToPlayer(pending.player, new PlayerInfoRequestPacket());
                if (DEBUG.enabled()) {
                    DEBUG.log(Category.STATE, "PlayerInfoRequestPacket resent: player={}, attempt={}",
                            pending.player.getCommandSenderName(), pending.attempt);
                }
            }
            if (settings.isClientModRequired() && now - pending.joinedAt >= settings.getClientModRequiredCheckTimeoutMs()) {
                iterator.remove();
                // Upstream pv.bypass_mod_requirement is an operator permission.
                if (!MinecraftServer.getServer().getConfigurationManager().func_152596_g(pending.player.getGameProfile())) {
                    pending.player.playerNetServerHandler.kickPlayerFromServer(
                            translate(pending.player, "pv.error.mod_missing_kick_message"));
                }
            } else if (!settings.isClientModRequired() && pending.attempt >= INFO_REQUEST_RETRY_MS.length) {
                iterator.remove();
            }
        }
    }

    private ServerSettings loadSettings() {
        return ServerSettings.load(new File(configFolder(), "server.cfg"), LOGGER);
    }

    private void applySettings(ServerSettings settings) {
        DEBUG.setEnabled(settings.isDebug());
        languages = ServerLanguages.load(new File(configFolder(), "languages"),
                settings.getDefaultLanguage(), settings.getForcedLanguage(), LOGGER);
        voicePlayers.setLanguages(languages);
        voicePlayers.setMaxExtraDistance(settings.getMaxExtraAudioBroadcastDistance());
    }

    private void startUdpServer(ServerSettings settings) {
        if (DEBUG.enabled()) {
            DEBUG.log(Category.STATE, "voice server starting: host={}:{}, minecraftPort={}, public={}:{}, keepAliveTimeout={}ms, "
                            + "sampleRate={}, mtu={}, codec=opus/{} bitrate={}, proximity={} default={}",
                    settings.getHostIp(), settings.getHostPort(), minecraftPort, settings.getPublicIp(), settings.getPublicPort(),
                    settings.getKeepAliveTimeoutMs(), settings.getSampleRate(), settings.getMtuSize(), settings.getOpusMode(),
                    settings.getOpusBitrate(), settings.getProximityDistances(), settings.getProximityDefaultDistance());
        }
        udpServer = UdpServer.create(LOGGER, settings, minecraftPort);
        udpServer.setProximityActivation(serverConfig.getProximityActivation());
        udpServer.start();
    }

    private static File configFolder() {
        return new File(Loader.instance().getConfigDir(), "plasmovoice");
    }

    private static final class PendingPlayer {
        final EntityPlayerMP player;
        final long joinedAt;
        long lastRequest;
        int attempt;

        PendingPlayer(EntityPlayerMP player, long now) {
            this.player = player;
            this.joinedAt = now;
            this.lastRequest = now;
        }
    }
}
