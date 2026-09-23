package su.plo.voice.platform.forge.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class VoiceHotkeysTest {
    private static final int KEY_LMENU = 56;
    private static final int KEY_M = 50;
    private static final int KEY_LCONTROL = 29;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final Set<Integer> down = new HashSet<>();
    private final List<String> presses = new ArrayList<>();
    private final VoiceHotkeys hotkeys = new VoiceHotkeys();

    @BeforeClass
    public static void injectMinecraftHome() throws Exception {
        java.lang.reflect.Field home = Class.forName("cpw.mods.fml.relauncher.FMLInjectionData")
                .getDeclaredField("minecraftHome");
        home.setAccessible(true);
        if (home.get(null) == null) home.set(null, new File(".").getAbsoluteFile());
    }

    private void poll(boolean windowActive, boolean screenOpen, boolean suspended) {
        hotkeys.update(down::contains, windowActive, screenOpen, suspended, presses::add);
    }

    @Test
    public void upstreamDefaults() {
        assertEquals(Collections.singletonList(KEY_LMENU), hotkeys.getKeys(VoiceHotkeys.PROXIMITY_PTT));
        assertEquals(Collections.singletonList(KEY_M), hotkeys.getKeys(VoiceHotkeys.MUTE_MICROPHONE));
        assertTrue(hotkeys.getKeys(VoiceHotkeys.DISABLE_VOICE).isEmpty());
        assertTrue(hotkeys.getKeys(VoiceHotkeys.PROXIMITY_DISTANCE_INCREASE).isEmpty());
    }

    @Test
    public void heldKeyFiresOnceDespiteRepeat() {
        down.add(KEY_M);
        for (int frame = 0; frame < 10; frame++) poll(true, false, false);
        assertEquals(Collections.singletonList(VoiceHotkeys.MUTE_MICROPHONE), presses);
        down.clear();
        poll(true, false, false);
        down.add(KEY_M);
        poll(true, false, false);
        assertEquals(2, presses.size());
    }

    @Test
    public void screensBlockAllButPushToTalk() {
        down.add(KEY_M);
        down.add(KEY_LMENU);
        poll(true, true, false);
        assertEquals(Collections.singletonList(VoiceHotkeys.PROXIMITY_PTT), presses);
        assertTrue(hotkeys.isPressed(VoiceHotkeys.PROXIMITY_PTT));

        // Closing the screen with M still held does not toggle the microphone.
        poll(true, false, false);
        assertFalse(presses.contains(VoiceHotkeys.MUTE_MICROPHONE));
    }

    @Test
    public void focusLossAndRecordingReleaseKeys() {
        down.add(KEY_LMENU);
        poll(true, false, false);
        assertTrue(hotkeys.isPressed(VoiceHotkeys.PROXIMITY_PTT));
        poll(false, false, false);
        assertFalse(hotkeys.isPressed(VoiceHotkeys.PROXIMITY_PTT));

        down.clear();
        down.add(KEY_M);
        poll(true, false, true);
        assertFalse(presses.contains(VoiceHotkeys.MUTE_MICROPHONE));
    }

    @Test
    public void combinationsNeedEveryKey() {
        hotkeys.setKeys(VoiceHotkeys.DISABLE_VOICE, Arrays.asList(KEY_LCONTROL, KEY_M, KEY_M, 0, 30, 31));
        assertEquals(Arrays.asList(KEY_LCONTROL, KEY_M, 30), hotkeys.getKeys(VoiceHotkeys.DISABLE_VOICE));
        hotkeys.setKeys(VoiceHotkeys.DISABLE_VOICE, Arrays.asList(KEY_LCONTROL, -99));
        down.add(KEY_LCONTROL);
        poll(true, false, false);
        assertFalse(presses.contains(VoiceHotkeys.DISABLE_VOICE));
        down.add(-99);
        poll(true, false, false);
        assertTrue(presses.contains(VoiceHotkeys.DISABLE_VOICE));
    }

    @Test
    public void bindingsSurviveARestartAndBrokenValuesFallBack() throws Exception {
        File file = new File(folder.getRoot(), "client.cfg");
        ClientState before = new ClientState();
        before.getHotkeys().setKeys(VoiceHotkeys.PROXIMITY_PTT, Arrays.asList(KEY_LCONTROL, -99));
        before.getHotkeys().setKeys(VoiceHotkeys.MUTE_MICROPHONE, Collections.<Integer>emptyList());
        ClientSettingsFile.save(file, before);

        ClientState after = new ClientState();
        ClientSettingsFile.load(file, after);
        assertEquals(Arrays.asList(KEY_LCONTROL, -99), after.getHotkeys().getKeys(VoiceHotkeys.PROXIMITY_PTT));
        assertTrue(after.getHotkeys().getKeys(VoiceHotkeys.MUTE_MICROPHONE).isEmpty());
        assertTrue(after.getHotkeys().isDefault(VoiceHotkeys.DISABLE_VOICE));

        String broken = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("key.plasmovoice.proximity.ptt=29,-99", "key.plasmovoice.proximity.ptt=alt");
        Files.write(file.toPath(), broken.getBytes(StandardCharsets.UTF_8));
        ClientState reloaded = new ClientState();
        ClientSettingsFile.load(file, reloaded);
        assertTrue(reloaded.getHotkeys().isDefault(VoiceHotkeys.PROXIMITY_PTT));
    }
}
