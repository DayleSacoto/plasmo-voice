package su.plo.voice.platform.forge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import su.plo.voice.proto.packets.PacketRegistry;
import su.plo.voice.platform.forge.client.VoiceControls;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;
import su.plo.voice.platform.forge.server.connection.ServerConnection;
import su.plo.voice.platform.forge.server.connection.UdpServer;
import su.plo.voice.platform.forge.server.connection.ServerConfig;

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
    private VoiceChannel voiceChannel;
    private UdpServer udpServer;
    private ServerConfig serverConfig;

    private final Map<UUID, ServerConnection> serverConnections = new HashMap<>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Initializing {} {} for Forge 1.7.10", MOD_NAME, VERSION);
        LOGGER.info("Protocol loaded: {}", PacketRegistry.class.getName());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        voiceChannel = new VoiceChannel(LOGGER);

        voiceChannel.setClientListener((connection, packet) -> {
            if (packet instanceof PlayerInfoRequestPacket) {
                LOGGER.info("Received PlayerInfoRequestPacket from server");
            }
        });

        voiceChannel.setServerListener((player, packet) -> {
            // Exact class: PlayerInfoPacket extends PlayerStatePacket in the upstream protocol.
            if (packet.getClass() == PlayerStatePacket.class) {
                PlayerStatePacket state = (PlayerStatePacket) packet;
                ServerConnection connection = serverConnections.get(player.getUniqueID());
                if (connection != null && connection.handle(state)) {
                    LOGGER.info("Voice state updated for {}: voiceDisabled={}, microphoneMuted={}",
                            player.getCommandSenderName(), state.isVoiceDisabled(), state.isMicrophoneMuted());
                }
                return;
            }
            if (!(packet instanceof PlayerInfoPacket)) {
                return;
            }

            PlayerInfoPacket info = (PlayerInfoPacket) packet;
            ServerConnection existing = serverConnections.get(player.getUniqueID());
            ServerConnection connection = existing != null ? existing : new ServerConnection(player);

            ServerConnection.PlayerInfoResult result =
                    connection.handle(info, VERSION, ServerConnection.DEFAULT_CLIENT_MOD_MIN_VERSION);
            if (result != ServerConnection.PlayerInfoResult.ACCEPTED) {
                if (result == ServerConnection.PlayerInfoResult.UNSUPPORTED_VERSION) {
                    connection.suggestSupportedVersion(info.getMinecraftVersion());
                }
                LOGGER.warn("Voice connection rejected for {}: {} (version={})",
                        player.getCommandSenderName(), result, info.getVersion());
                return;
            }

            serverConnections.put(player.getUniqueID(), connection);
            LOGGER.info(
                    "Voice server connection initialized for {}: minecraft={}, version={}, key={}, voiceDisabled={}, microphoneMuted={}",
                    player.getCommandSenderName(),
                    connection.getMinecraftVersion(),
                    connection.getModVersion(),
                    connection.getPublicKey().getAlgorithm(),
                    connection.isVoiceDisabled(),
                    connection.isMicrophoneMuted()
            );
            if (udpServer != null) connection.prepareUdp(udpServer);
        });

        FMLCommonHandler.instance().bus().register(this);
        if (event.getSide().isClient()) VoiceControls.register();

        LOGGER.info("{} initialized", MOD_NAME);
    }

    @SubscribeEvent
    public void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player.worldObj.isRemote) return;
        serverConnections.remove(event.player.getUniqueID());
        if (udpServer != null) udpServer.removeSession(event.player.getUniqueID());
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) throws java.security.GeneralSecurityException {
        serverConnections.clear();
        serverConfig = new ServerConfig();
        udpServer = UdpServer.fromProperties(LOGGER);
        udpServer.start();
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || udpServer == null) return;
        serverConnections.values().forEach(connection -> connection.tick(udpServer, voiceChannel, serverConfig));
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

        LOGGER.info("Sending PlayerInfoRequestPacket to {}", player.getCommandSenderName());
        voiceChannel.sendToPlayer(player, new PlayerInfoRequestPacket());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        if (udpServer != null) udpServer.close();
        udpServer = null;
        serverConfig = null;
        serverConnections.clear();

        if (voiceChannel != null) {
            voiceChannel.clearServer();
        }
    }
}
