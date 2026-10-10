package su.plo.voice.platform.forge;

import java.util.Collections;
import java.util.Map;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.discovery.ASMDataTable;
import cpw.mods.fml.common.network.internal.NetworkModHolder;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The FML handshake check of the real @Mod annotation. Voice is optional on both sides like upstream: a server with
 * the mod takes clients without it (or with another version), and a client with the mod joins servers without it.
 */
public class NetworkCompatibilityTest {
    /** FMLLog needs the side the launcher normally sets. */
    @BeforeClass
    public static void injectLaunchSide() throws Exception {
        java.lang.reflect.Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        if (side.get(null) == null) side.set(null, Side.SERVER);
    }

    @Test
    public void handshakeAcceptsRemotesWithoutOrWithOtherVersionsOfTheMod() {
        NetworkModHolder holder = holder(PlasmoVoiceMod.class.getAnnotation(Mod.class).acceptableRemoteVersions());
        Map<String, String> none = Collections.emptyMap();
        assertTrue("client without the mod", holder.check(none, Side.CLIENT));
        assertTrue("server without the mod", holder.check(none, Side.SERVER));
        assertTrue(holder.check(Collections.singletonMap(PlasmoVoiceMod.MOD_ID, "2.1.16"), Side.CLIENT));
        assertTrue(holder.check(Collections.singletonMap(PlasmoVoiceMod.MOD_ID, PlasmoVoiceMod.VERSION), Side.SERVER));
    }

    /** What FML did before: "Mod rejections [FMLMod:plasmovoice{2.1.17}]" for a client without the mod. */
    @Test
    public void defaultCheckRejectedClientsWithoutTheMod() {
        assertFalse(holder("").check(Collections.<String, String>emptyMap(), Side.CLIENT));
    }

    private static NetworkModHolder holder(String acceptableRemoteVersions) {
        ModMetadata metadata = new ModMetadata();
        metadata.modId = PlasmoVoiceMod.MOD_ID;
        metadata.version = PlasmoVoiceMod.VERSION;
        return new NetworkModHolder(new DummyModContainer(metadata), PlasmoVoiceMod.class, acceptableRemoteVersions,
                new ASMDataTable());
    }
}
