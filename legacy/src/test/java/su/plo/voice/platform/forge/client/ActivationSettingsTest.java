package su.plo.voice.platform.forge.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import su.plo.slib.api.entity.player.McGameProfile;
import su.plo.voice.platform.forge.client.audio.CaptureActivation.Type;
import su.plo.voice.platform.forge.client.audio.SourcePlayer;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.server.connection.ServerConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.audio.source.DirectSourceInfo;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;

import static org.junit.Assert.*;

/** Per-activation client config (upstream ConfigClientActivation) and the activation hotkeys registered at runtime. */
public class ActivationSettingsTest {
    private static final int KEY_G = 34;
    private static final int KEY_H = 35;
    private static final int KEY_LCONTROL = 29;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void injectMinecraftHome() throws Exception {
        java.lang.reflect.Field home = Class.forName("cpw.mods.fml.relauncher.FMLInjectionData")
                .getDeclaredField("minecraftHome");
        home.setAccessible(true);
        if (home.get(null) == null) home.set(null, new File(".").getAbsoluteFile());
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void otherActivationsDefaultToPushToTalkNotToggled() {
        ClientState state = new ClientState();
        assertEquals(Type.PUSH_TO_TALK, state.getActivationType("groups"));
        assertFalse(state.isActivationToggled("groups"));
        assertFalse(state.isPushToTalkPressed("groups"));
        assertTrue(state.getActiveActivations().isEmpty());
        assertFalse(state.isActivationActive());
    }

    @Test
    public void proximityNameUsesTheProximityFieldsAndCannotInherit() {
        ClientState state = new ClientState();
        state.setActivationType(VoiceActivation.PROXIMITY_NAME, Type.VOICE);
        state.setActivationToggled(VoiceActivation.PROXIMITY_NAME, true);
        assertEquals(Type.VOICE, state.getActivationType());
        assertTrue(state.isActivationToggled());
        state.setPushToTalkPressed(VoiceActivation.PROXIMITY_NAME, true);
        assertTrue(state.isPushToTalkPressed());

        state.setActivationType(Type.INHERIT);
        assertEquals(Type.PUSH_TO_TALK, state.getActivationType());
        assertTrue("proximity is not an 'other' activation setting", state.activationSettingNames().isEmpty());
    }

    @Test
    public void typeAndToggleKeepEachOtherAndPushToTalkIsPerActivation() {
        ClientState state = new ClientState();
        state.setActivationToggled("groups", true);
        assertEquals(Type.PUSH_TO_TALK, state.getActivationType("groups"));
        state.setActivationType("groups", Type.INHERIT);
        assertTrue(state.isActivationToggled("groups"));
        assertFalse(state.isActivationToggled("radio"));

        state.setPushToTalkPressed("groups", true);
        assertTrue(state.isPushToTalkPressed("groups"));
        assertFalse(state.isPushToTalkPressed("radio"));
        assertFalse(state.isPushToTalkPressed());
        state.setPushToTalkPressed("groups", false);
        assertFalse(state.isPushToTalkPressed("groups"));
    }

    @Test
    public void perActivationConfigSurvivesARestartByName() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.setActivationType(Type.VOICE);
        before.setActivationType("groups", Type.INHERIT);
        before.setActivationToggled("groups", true);
        before.setActivationType("radio", Type.VOICE);
        ClientSettingsFile.save(file, before);

        String saved = read(file);
        assertTrue(saved, saved.contains("groups {"));
        assertTrue(saved, saved.contains("INHERIT"));

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertEquals(Type.INHERIT, after.getActivationType("groups"));
        assertTrue(after.isActivationToggled("groups"));
        assertEquals(Type.VOICE, after.getActivationType("radio"));
        assertFalse(after.isActivationToggled("radio"));
        assertEquals(Type.VOICE, after.getActivationType());
        assertFalse(after.isActivationToggled());
    }

    @Test
    public void settingsFilesWithoutOtherActivationsLoadAsBefore() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.setActivationType(Type.VOICE);
        before.setActivationToggled(true);
        ClientSettingsFile.save(file, before);

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertEquals(Type.VOICE, after.getActivationType());
        assertTrue(after.isActivationToggled());
        assertTrue(after.activationSettingNames().isEmpty());
        assertEquals(Type.PUSH_TO_TALK, after.getActivationType("groups"));
    }

    @Test
    public void brokenOrProximityEntriesDoNotBreakLoading() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.setActivationType("groups", Type.VOICE);
        before.setActivationToggled("groups", true);
        ClientSettingsFile.save(file, before);
        Files.write(file.toPath(), read(file).replace("VOICE", "SHOUTING").getBytes(StandardCharsets.UTF_8));

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertEquals(Type.PUSH_TO_TALK, after.getActivationType("groups"));
        assertTrue(after.isActivationToggled("groups"));

        // A proximity activation stored as INHERIT (never valid upstream) falls back to push-to-talk.
        ClientState proximity = new ClientState();
        proximity.setActivationType(Type.VOICE);
        ClientSettingsFile.save(file, proximity);
        Files.write(file.toPath(), read(file).replace("VOICE", "INHERIT").getBytes(StandardCharsets.UTF_8));
        ClientState reloaded = new ClientState();
        ClientSettingsFile.load(file, reloaded);
        assertEquals(Type.PUSH_TO_TALK, reloaded.getActivationType());
    }

    @Test
    public void activationHotkeysRegisterUnboundWithTheUpstreamContexts() {
        VoiceHotkeys hotkeys = new VoiceHotkeys();
        int fixed = hotkeys.names().size();
        assertFalse(hotkeys.isRegistered(VoiceHotkeys.pushToTalkKey("groups")));

        hotkeys.registerActivation("groups");
        assertEquals("key.plasmovoice.groups.ptt", VoiceHotkeys.pushToTalkKey("groups"));
        assertEquals("key.plasmovoice.groups.toggle", VoiceHotkeys.toggleKey("groups"));
        assertEquals(fixed + 2, hotkeys.names().size());
        assertTrue(hotkeys.getKeys(VoiceHotkeys.pushToTalkKey("groups")).isEmpty());
        assertTrue(hotkeys.getKeys(VoiceHotkeys.toggleKey("groups")).isEmpty());
        assertTrue(hotkeys.isDefault(VoiceHotkeys.pushToTalkKey("groups")));

        // Registering again, or the proximity activation, changes nothing.
        hotkeys.setKeys(VoiceHotkeys.pushToTalkKey("groups"), Collections.singletonList(KEY_G));
        hotkeys.registerActivation("groups");
        hotkeys.registerActivation(VoiceActivation.PROXIMITY_NAME);
        assertEquals(fixed + 2, hotkeys.names().size());
        assertEquals(Collections.singletonList(KEY_G), hotkeys.getKeys(VoiceHotkeys.pushToTalkKey("groups")));
        assertEquals(Collections.singletonList(56), hotkeys.getKeys(VoiceHotkeys.PROXIMITY_PTT));

        hotkeys.reset(VoiceHotkeys.pushToTalkKey("groups"));
        assertTrue(hotkeys.getKeys(VoiceHotkeys.pushToTalkKey("groups")).isEmpty());
    }

    @Test
    public void pushToTalkWorksInScreensButToggleDoesNot() {
        VoiceHotkeys hotkeys = new VoiceHotkeys();
        hotkeys.registerActivation("groups");
        hotkeys.setKeys(VoiceHotkeys.pushToTalkKey("groups"), Collections.singletonList(KEY_G));
        hotkeys.setKeys(VoiceHotkeys.toggleKey("groups"), Collections.singletonList(KEY_H));
        java.util.List<String> presses = new java.util.ArrayList<>();
        HashSet<Integer> down = new HashSet<>(Arrays.asList(KEY_G, KEY_H));

        hotkeys.update(down::contains, true, true, false, presses::add);
        assertEquals(Collections.singletonList(VoiceHotkeys.pushToTalkKey("groups")), presses);
        assertTrue(hotkeys.isPressed(VoiceHotkeys.pushToTalkKey("groups")));

        // The screen closes with the toggle key still held: no toggle. Pressed anew, it fires.
        hotkeys.update(down::contains, true, false, false, presses::add);
        assertEquals(1, presses.size());
        down.remove(KEY_H);
        hotkeys.update(down::contains, true, false, false, presses::add);
        down.add(KEY_H);
        hotkeys.update(down::contains, true, false, false, presses::add);
        assertEquals(Arrays.asList(VoiceHotkeys.pushToTalkKey("groups"), VoiceHotkeys.toggleKey("groups")), presses);
    }

    @Test
    public void activationHotkeyBindingsPersistAndWaitForTheirRegistration() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.getHotkeys().registerActivation("groups");
        before.getHotkeys().setKeys(VoiceHotkeys.pushToTalkKey("groups"), Arrays.asList(KEY_LCONTROL, KEY_G));
        before.getHotkeys().setKeys(VoiceHotkeys.toggleKey("groups"), Collections.singletonList(KEY_H));
        ClientSettingsFile.save(file, before);

        // Loaded before any server config registered the hotkeys.
        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertFalse(after.getHotkeys().isRegistered(VoiceHotkeys.pushToTalkKey("groups")));
        assertEquals(2, after.getHotkeys().unregisteredBindings().size());

        after.getHotkeys().registerActivation("groups");
        assertEquals(Arrays.asList(KEY_LCONTROL, KEY_G), after.getHotkeys().getKeys(VoiceHotkeys.pushToTalkKey("groups")));
        assertEquals(Collections.singletonList(KEY_H), after.getHotkeys().getKeys(VoiceHotkeys.toggleKey("groups")));
        assertTrue(after.getHotkeys().unregisteredBindings().isEmpty());
    }

    @Test
    public void bindingsOfActivationsNotRegisteredThisSessionSurviveLoadAndSave() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState first = new ClientState();
        first.getHotkeys().registerActivation("groups");
        first.getHotkeys().registerActivation("radio");
        first.getHotkeys().setKeys(VoiceHotkeys.pushToTalkKey("groups"), Collections.singletonList(KEY_G));
        first.getHotkeys().setKeys(VoiceHotkeys.pushToTalkKey("radio"), Collections.singletonList(KEY_H));
        first.setActivationType("radio", Type.VOICE);
        ClientSettingsFile.save(file, first);

        // A session on a server that only has groups: radio stays unregistered through a settings save.
        ClientState second = new ClientState();
        ClientSettingsFile.load(file, second);
        second.getHotkeys().registerActivation("groups");
        second.getHotkeys().setKeys(VoiceHotkeys.toggleKey("groups"), Collections.singletonList(KEY_LCONTROL));
        ClientSettingsFile.save(file, second);

        // Even from a state that never loaded them, the bindings are still in the file.
        File copy = new File(folder.getRoot(), "copy.cfg");
        Files.write(copy.toPath(), read(file).getBytes(StandardCharsets.UTF_8));
        ClientState third = new ClientState();
        ClientSettingsFile.load(copy, third);
        third.getHotkeys().registerActivation("radio");
        third.getHotkeys().registerActivation("groups");
        assertEquals(Collections.singletonList(KEY_H), third.getHotkeys().getKeys(VoiceHotkeys.pushToTalkKey("radio")));
        assertEquals(Collections.singletonList(KEY_G), third.getHotkeys().getKeys(VoiceHotkeys.pushToTalkKey("groups")));
        assertEquals(Collections.singletonList(KEY_LCONTROL), third.getHotkeys().getKeys(VoiceHotkeys.toggleKey("groups")));
        assertEquals(Type.VOICE, third.getActivationType("radio"));
    }

    @Test
    public void unregisteredBindingsAreWrittenEvenWithoutAnExistingFileEntry() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState state = new ClientState();
        state.getHotkeys().restore(VoiceHotkeys.pushToTalkKey("later"), Collections.singletonList(KEY_G));
        assertEquals(Collections.singletonList(KEY_G),
                state.getHotkeys().unregisteredBindings().get(VoiceHotkeys.pushToTalkKey("later")));
        ClientSettingsFile.save(file, state);
        assertTrue(read(file), read(file).contains("key.plasmovoice.later.ptt=34"));
    }

    @Test
    public void invalidActivationBindingsAreDropped() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.getHotkeys().registerActivation("groups");
        before.getHotkeys().setKeys(VoiceHotkeys.pushToTalkKey("groups"), Collections.singletonList(KEY_G));
        ClientSettingsFile.save(file, before);
        Files.write(file.toPath(), read(file).replace("key.plasmovoice.groups.ptt=34", "key.plasmovoice.groups.ptt=alt")
                .getBytes(StandardCharsets.UTF_8));

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        after.getHotkeys().registerActivation("groups");
        assertTrue(after.getHotkeys().getKeys(VoiceHotkeys.pushToTalkKey("groups")).isEmpty());
    }

    @Test
    public void directSourcesWithASenderUseThePlayerMuteVolumeAndName() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        ServerConfig serverConfig = new ServerConfig();
        ClientConfig config = ClientConfig.decode(serverConfig.createPacket(keys.getPublic(), true), keys.getPrivate());
        UUID playerId = UUID.randomUUID();
        UUID lineId = serverConfig.getProximityLine().getId();
        DirectSourceInfo grouped = new DirectSourceInfo("plasmovoice", UUID.randomUUID(), lineId, null, (byte) 0,
                new OpusDecoderInfo(), false, true, 0, new McGameProfile(playerId, "speaker", Collections.emptyList()),
                null, null, true);
        DirectSourceInfo anonymous = new DirectSourceInfo("plasmovoice", UUID.randomUUID(), lineId, null, (byte) 0,
                new OpusDecoderInfo(), false, true, 0, null, null, null, true);
        PlayerSourceInfo player = new PlayerSourceInfo("plasmovoice", UUID.randomUUID(), lineId, null, (byte) 0,
                new OpusDecoderInfo(), false, true, 0, new VoicePlayerInfo(playerId, "speaker", false, false, false));

        assertEquals(playerId, SourcePlayer.id(grouped));
        assertEquals("speaker", SourcePlayer.name(grouped));
        assertEquals(playerId, SourcePlayer.id(player));
        assertNull(SourcePlayer.id(anonymous));
        assertNull(SourcePlayer.name(anonymous));

        ClientState state = new ClientState();
        String playerKey = ClientState.playerVolumeKey(playerId);
        state.setVolume(0.5D);
        state.setSourceVolume(VoiceSourceLine.PROXIMITY_NAME, 0.5D);
        state.setSourceVolume(playerKey, 1.5D);
        assertEquals(0.5D * 0.5D * 1.5D, state.volume(config, grouped), 1e-9);
        // Same as the player source of that player; a sender-less source has no player to adjust.
        assertEquals(state.volume(config, player), state.volume(config, grouped), 0D);
        assertEquals(0.5D * 0.5D, state.volume(config, anonymous), 1e-9);

        assertFalse(state.isMuted(config, grouped));
        state.setSourceMuted(playerKey, true);
        assertTrue(state.isMuted(config, grouped));
        assertTrue(state.isMuted(config, player));
        assertFalse(state.isMuted(config, anonymous));
    }
}
