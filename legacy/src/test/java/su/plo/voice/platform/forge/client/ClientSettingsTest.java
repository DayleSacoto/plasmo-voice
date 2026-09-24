package su.plo.voice.platform.forge.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.platform.forge.client.hud.HudOptions;
import su.plo.voice.platform.forge.server.connection.ServerConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerActivationDistancesPacket;

import static org.junit.Assert.*;

public class ClientSettingsTest {
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
    public void settingsSurviveARestart() {
        File file = new File(folder.getRoot(), "plasmovoice/client.cfg");
        UUID serverId = UUID.randomUUID();
        ClientState before = new ClientState();
        before.setVoiceDisabled(true);
        before.setMicrophoneMuted(true);
        before.setActivationThreshold(-42D);
        before.setInputDevice("OpenAL Soft on Microphone (USB)");
        before.setOutputDevice("OpenAL Soft on Speakers");
        before.setStereoCapture(true);
        before.setInputDeviceDisabled(true);
        before.setMicrophoneVolume(1.5D);
        before.setVolume(0.25D);
        before.setActivationType(CaptureActivation.Type.VOICE);
        before.setActivationToggled(true);
        before.setActivationDistance(serverId, VoiceActivation.PROXIMITY_ID, 32);
        before.setPushToTalkPressed(true);
        before.setShowActivationIcon(false);
        before.setActivationIconPosition(HudOptions.IconPosition.TOP_RIGHT);
        before.setOverlayEnabled(false);
        before.setOverlayPosition(HudOptions.OverlayPosition.BOTTOM_LEFT);
        before.setOverlayStyle(HudOptions.OverlayStyle.NAME);
        before.setOverlaySourceState("proximity", HudOptions.OverlaySourceState.ON);
        before.setVisualizeVoiceDistance(false);
        before.setVisualizeVoiceDistanceOnJoin(true);
        before.setPanning(false);
        before.setExponentialVolumeSlider(false);
        before.setExponentialDistanceGain(false);
        ClientSettingsFile.save(file, before);

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertTrue(after.isVoiceDisabled());
        assertTrue(after.isMicrophoneMuted());
        assertEquals(-42D, after.getActivationThreshold(), 0D);
        assertEquals("OpenAL Soft on Microphone (USB)", after.getInputDevice());
        assertEquals("OpenAL Soft on Speakers", after.getOutputDevice());
        assertTrue(after.isStereoCapture());
        assertTrue(after.isInputDeviceDisabled());
        assertEquals(1.5D, after.getMicrophoneVolume(), 0D);
        assertEquals(0.25D, after.getVolume(), 0D);
        assertEquals(CaptureActivation.Type.VOICE, after.getActivationType());
        assertTrue(after.isActivationToggled());
        assertEquals(Integer.valueOf(32), after.getActivationDistance(serverId, VoiceActivation.PROXIMITY_ID));
        assertFalse(after.isShowActivationIcon());
        assertEquals(HudOptions.IconPosition.TOP_RIGHT, after.getActivationIconPosition());
        assertFalse(after.isOverlayEnabled());
        assertEquals(HudOptions.OverlayPosition.BOTTOM_LEFT, after.getOverlayPosition());
        assertEquals(HudOptions.OverlayStyle.NAME, after.getOverlayStyle());
        assertEquals(HudOptions.OverlaySourceState.ON, after.getOverlaySourceState("proximity"));
        assertEquals(HudOptions.OverlaySourceState.OFF, after.getOverlaySourceState("radio"));
        assertFalse(after.isVisualizeVoiceDistance());
        assertTrue(after.isVisualizeVoiceDistanceOnJoin());
        assertFalse(after.isPanning());
        assertFalse(after.isExponentialVolumeSlider());
        assertFalse(after.isExponentialDistanceGain());
        // Key state is runtime-only.
        assertFalse(after.isPushToTalkPressed());
    }

    @Test
    public void outOfRangeOrUnknownValuesFallBackToUpstreamDefaults() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        String content = "voice {\n    D:volume=7.0\n    D:activation_threshold=-99.0\n}\n\n"
                + "activations {\n    \"" + VoiceActivation.PROXIMITY_ID + "\" {\n        S:type=SHOUT\n    }\n}\n";
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        ClientState state = new ClientState();
        ClientSettingsFile.load(file, state);
        assertEquals(2D, state.getVolume(), 0D);
        assertEquals(-60D, state.getActivationThreshold(), 0D);
        assertEquals(1D, state.getMicrophoneVolume(), 0D);
        assertEquals(CaptureActivation.Type.PUSH_TO_TALK, state.getActivationType());
        assertEquals("", state.getInputDevice());
    }

    @Test
    public void distanceChangesAreStoredPerServerAndSentOncePerStep() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        ClientConfig config = ClientConfig.decode(new ServerConfig().createPacket(keys.getPublic()), keys.getPrivate());
        UUID serverId = config.getPacket().getServerId();

        ClientState state = new ClientState();
        List<Packet<?>> sent = new ArrayList<>();
        ClientConnectionState connection = state.openConnection(packet -> {});
        connection.setPacketSender(sent::add);
        connection.acceptConfig(config);

        state.changeActivationDistance(VoiceActivation.PROXIMITY_ID, 32);
        state.changeActivationDistance(VoiceActivation.PROXIMITY_ID, 32);
        assertEquals(1, sent.size());
        assertEquals(Integer.valueOf(32),
                ((PlayerActivationDistancesPacket) sent.get(0)).getDistanceByActivationId().get(VoiceActivation.PROXIMITY_ID));
        assertEquals(Integer.valueOf(32), config.activationDistances(id -> state.getActivationDistance(serverId, id))
                .get(VoiceActivation.PROXIMITY_ID));

        // Going back to the server default forgets the choice, like upstream's default config entries.
        state.changeActivationDistance(VoiceActivation.PROXIMITY_ID, 16);
        assertNull(state.getActivationDistance(serverId, VoiceActivation.PROXIMITY_ID));
        assertEquals(2, sent.size());

        // A stored distance the server no longer offers falls back to its default.
        state.setActivationDistance(serverId, VoiceActivation.PROXIMITY_ID, 17);
        assertEquals(Integer.valueOf(16), config.activationDistances(id -> state.getActivationDistance(serverId, id))
                .get(VoiceActivation.PROXIMITY_ID));
    }

    @Test
    public void volumesSurviveARestartAndApplyToPlayerSources() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        ServerConfig serverConfig = new ServerConfig();
        ClientConfig config = ClientConfig.decode(serverConfig.createPacket(keys.getPublic()), keys.getPrivate());
        UUID playerId = UUID.randomUUID();
        String playerKey = ClientState.playerVolumeKey(playerId);
        PlayerSourceInfo source = new PlayerSourceInfo("plasmovoice", UUID.randomUUID(), serverConfig.getProximityLine().getId(),
                null, (byte) 0, new OpusDecoderInfo(), false, true, 0,
                new VoicePlayerInfo(playerId, "speaker", false, false, false));

        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.setVolume(0.5D);
        before.setSourceVolume(VoiceSourceLine.PROXIMITY_NAME, 0.5D);
        before.setSourceVolume(playerKey, 1.5D);
        before.setSourceMuted(playerKey, true);
        before.setShowSourceIcons(1);
        ClientSettingsFile.save(file, before);

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertEquals(1.5D, after.getSourceVolume(playerKey), 0D);
        assertTrue(after.isSourceMuted(playerKey));
        assertEquals(1, after.getShowSourceIcons());
        // Upstream: global volume times the line volume times the player volume.
        assertEquals(0.5D * 0.5D * 1.5D, after.volume(config, source), 1e-9);
        assertTrue(after.isMuted(config, source));

        // Defaults are not stored, and a muted line mutes every source on it.
        after.setSourceVolume(playerKey, 1D);
        after.setSourceMuted(playerKey, false);
        assertFalse(after.isMuted(config, source));
        after.setSourceMuted(VoiceSourceLine.PROXIMITY_NAME, true);
        assertTrue(after.isMuted(config, source));
        after.setSourceVolume(playerKey, 9D);
        assertEquals(2D, after.getSourceVolume(playerKey), 0D);
        ClientSettingsFile.save(file, after);
        String saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(saved.contains("muted=false"));
    }

    @Test
    public void localPlayerIsFoundByNameOnOfflineModeServers() {
        ClientConnectionState connection = new ClientState().openConnection(packet -> {});
        UUID session = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        assertEquals(session, connection.localPlayerId("VoiceTester", session));
        connection.putPlayer(new VoicePlayerInfo(offline, "VoiceTester", false, false, false));
        connection.putPlayer(new VoicePlayerInfo(UUID.randomUUID(), "Other", false, false, false));
        assertEquals(offline, connection.localPlayerId("VoiceTester", session));
    }
}
