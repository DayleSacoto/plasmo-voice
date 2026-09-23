package su.plo.voice.platform.forge.server.connection;

import java.io.File;
import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

import static org.junit.Assert.*;

public class ServerSettingsTest {
    private static final Logger LOGGER = LogManager.getLogger("test");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Forge Configuration reads the game directory that FML normally injects at launch. */
    @BeforeClass
    public static void injectMinecraftHome() throws Exception {
        java.lang.reflect.Field home = Class.forName("cpw.mods.fml.relauncher.FMLInjectionData")
                .getDeclaredField("minecraftHome");
        home.setAccessible(true);
        if (home.get(null) == null) home.set(null, new File(".").getAbsoluteFile());
    }

    @Test
    public void freshFileGetsUpstreamDefaultsAndKeepsServerId() throws Exception {
        File file = new File(folder.getRoot(), "plasmovoice/server.cfg");
        ServerSettings first = ServerSettings.load(file, LOGGER);
        assertTrue(file.isFile());
        assertEquals("0.0.0.0", first.getHostIp());
        assertEquals(0, first.getHostPort());
        assertNull(first.getPublicIp());
        assertEquals(48_000, first.getSampleRate());
        assertEquals(1024, first.getMtuSize());
        assertEquals(15_000, first.getKeepAliveTimeoutMs());
        assertEquals("2.0.0", first.getClientModMinVersion());
        assertEquals(Arrays.asList(8, 16, 32), first.getProximityDistances());
        assertEquals(16, first.getProximityDefaultDistance());
        assertEquals("VOIP", first.getOpusMode());
        assertEquals(-1000, first.getOpusBitrate());
        assertEquals(first.getServerId(), ServerSettings.load(file, LOGGER).getServerId());
    }

    @Test
    public void invalidValuesFallBackToDefaults() {
        File file = new File(folder.getRoot(), "server.cfg");
        Configuration config = new Configuration(file);
        config.get("general", "server_id", "").set("not-a-uuid");
        config.get("host", "port", 0).set("abc");
        config.get("voice", "sample_rate", 48_000).set("44100");
        config.get("voice", "mtu_size", 1024).set("50");
        config.get("voice", "keep_alive_timeout_ms", 15_000).set("500");
        config.get("voice.opus", "mode", "VOIP").set("MUSIC");
        config.get("voice.opus", "bitrate", -1000).set("100");
        config.get("voice.proximity", "distances", new int[] {8, 16, 32}).set(new String[] {"8", "far"});
        config.save();

        ServerSettings settings = ServerSettings.load(file, LOGGER);
        assertNotNull(settings.getServerId());
        assertEquals(0, settings.getHostPort());
        assertEquals(48_000, settings.getSampleRate());
        assertEquals(1024, settings.getMtuSize());
        assertEquals(15_000, settings.getKeepAliveTimeoutMs());
        assertEquals("VOIP", settings.getOpusMode());
        assertEquals(-1000, settings.getOpusBitrate());
        assertEquals(Arrays.asList(8, 16, 32), settings.getProximityDistances());
        // A regenerated server id is written back so it stays stable.
        assertEquals(settings.getServerId(), ServerSettings.load(file, LOGGER).getServerId());
    }

    @Test
    public void customValuesReachAddressesAndConfigPacket() throws Exception {
        File file = new File(folder.getRoot(), "server.cfg");
        Configuration config = new Configuration(file);
        config.get("host", "port", 0).set("24454");
        config.get("host.public", "ip", "").set("voice.example.org");
        config.get("host.public", "port", 0).set("25000");
        config.get("voice", "sample_rate", 48_000).set("24000");
        config.get("voice.opus", "bitrate", -1000).set("64000");
        config.get("voice.proximity", "distances", new int[] {8, 16, 32}).set(new String[] {"48", "8", "24"});
        config.get("voice.proximity", "default_distance", 16).set("24");
        config.get("voice.player_icon", "visibility", new String[0]).set(new String[] {"HIDE_NOT_INSTALLED", "BOGUS"});
        config.get("voice.player_icon", "y_offset", 0D).set("0.5");
        config.get("general", "forced_language", "").set("RU_RU");
        config.get("notifications", "muted", true).set(false);
        config.get("voice", "max_extra_audio_broadcast_distance", 16).set("4");
        config.save();

        ServerSettings settings = ServerSettings.load(file, LOGGER);
        assertEquals(24454, settings.bindPort(25565));
        assertEquals("voice.example.org", settings.advertisedIp());
        assertEquals(25000, settings.advertisedPort());

        ConfigPacket packet = new ServerConfig(settings).createPacket(
                java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic());
        assertEquals(settings.getServerId(), packet.getServerId());
        assertEquals(24_000, packet.getCaptureInfo().getSampleRate());
        assertEquals("64000", packet.getCaptureInfo().getEncoderInfo().getParams().get("bitrate"));
        VoiceActivation activation = packet.getActivations().iterator().next();
        assertEquals(Arrays.asList(8, 24, 48), activation.getDistances());
        assertEquals(24, activation.getDefaultDistance());
        // Upstream voice.player_icon reaches the client; unknown flags are ignored.
        assertEquals(java.util.Collections.singleton(su.plo.voice.proto.data.config.PlayerIconVisibility.HIDE_NOT_INSTALLED),
                packet.getPlayerIconConfig().getIconVisibility());
        assertEquals(0.5D, packet.getPlayerIconConfig().getIconOffset().getY(), 0D);
        assertEquals("ru_ru", settings.getForcedLanguage());
        assertFalse(settings.isNotifyMuted());
        assertTrue(settings.isNotifyUnmuted());
        assertEquals(4, settings.getMaxExtraAudioBroadcastDistance());
        assertTrue(settings.sameUdpEndpoint(ServerSettings.load(file, LOGGER)));
        assertFalse(settings.sameUdpEndpoint(ServerSettings.defaults()));
    }

    @Test
    public void portZeroFollowsMinecraftPortLikeUpstream() {
        ServerSettings settings = ServerSettings.defaults();
        assertEquals(25565, settings.bindPort(25565));
        assertEquals(0, settings.bindPort(-1)); // singleplayer before LAN: random port
        assertEquals("0.0.0.0", settings.advertisedIp());
        assertEquals(0, settings.advertisedPort()); // resolved to the bound port
    }
}
