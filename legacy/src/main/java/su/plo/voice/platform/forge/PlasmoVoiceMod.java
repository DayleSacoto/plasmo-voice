package su.plo.voice.platform.forge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import su.plo.voice.proto.packets.PacketRegistry;
@Mod(
        modid = PlasmoVoiceMod.MOD_ID,
        name = PlasmoVoiceMod.MOD_NAME,
        version = PlasmoVoiceMod.VERSION)
public final class PlasmoVoiceMod {

    public static final String MOD_ID = "plasmovoice";
    public static final String MOD_NAME = "Plasmo Voice";
    public static final String VERSION = "2.1.17";

    private static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Initializing {} {} for Forge 1.7.10", MOD_NAME, VERSION);
        LOGGER.info("Protocol loaded: {}", PacketRegistry.class.getName());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("{} initialized", MOD_NAME);
    }
}