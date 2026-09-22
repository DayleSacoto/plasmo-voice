package su.plo.voice.platform.forge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import su.plo.voice.proto.packets.PacketRegistry;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;

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
            if (packet instanceof PlayerInfoPacket) {
                PlayerInfoPacket info = (PlayerInfoPacket) packet;

                LOGGER.info(
                        "Received PlayerInfoPacket from {}: minecraft={}, version={}, publicKey={} bytes",
                        player.getCommandSenderName(),
                        info.getMinecraftVersion(),
                        info.getVersion(),
                        info.getPublicKey().length
                );
            }
        });

        FMLCommonHandler.instance().bus().register(this);

        LOGGER.info("{} initialized", MOD_NAME);
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
        voiceChannel.clearServer();
    }
}
