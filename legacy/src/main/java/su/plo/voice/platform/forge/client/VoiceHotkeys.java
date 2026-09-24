package su.plo.voice.platform.forge.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Upstream ConfigHotkeys + VoiceHotkey: named key combinations (up to three keyboard keys or mouse buttons).
 * Codes follow 1.7.10 KeyBinding: LWJGL keyboard codes, mouse buttons as {@code button - 100}.
 * Polled on the client thread every frame, which also filters key repeat.
 */
public final class VoiceHotkeys {
    public static final String PROXIMITY_PTT = "key.plasmovoice.proximity.ptt";
    public static final String PROXIMITY_TOGGLE = "key.plasmovoice.proximity.toggle";
    public static final String PROXIMITY_DISTANCE_INCREASE = "key.plasmovoice.proximity.distance_increase";
    public static final String PROXIMITY_DISTANCE_DECREASE = "key.plasmovoice.proximity.distance_decrease";
    public static final String MUTE_MICROPHONE = "key.plasmovoice.general.mute_microphone";
    public static final String DISABLE_VOICE = "key.plasmovoice.general.disable_voice";
    public static final String ACTION = "key.plasmovoice.general.action";
    public static final String OCCLUSION_TOGGLE = "key.plasmovoice.occlusion.toggle";
    public static final int MAX_KEYS = 3;
    private static final int KEY_LMENU = 56;
    private static final int KEY_M = 50;
    private static final int MOUSE_RIGHT = 1 - 100;

    private final Map<String, Hotkey> hotkeys = new LinkedHashMap<>();

    public VoiceHotkeys() {
        // Upstream: push-to-talk works in any context, the others only without an open screen.
        register(PROXIMITY_PTT, true, KEY_LMENU);
        register(PROXIMITY_TOGGLE, false);
        register(PROXIMITY_DISTANCE_INCREASE, false);
        register(PROXIMITY_DISTANCE_DECREASE, false);
        register(MUTE_MICROPHONE, false, KEY_M);
        register(DISABLE_VOICE, false);
        register(ACTION, false, MOUSE_RIGHT);
        register(OCCLUSION_TOGGLE, false);
    }

    private void register(String name, boolean anyContext, Integer... defaultKeys) {
        hotkeys.put(name, new Hotkey(anyContext, Arrays.asList(defaultKeys)));
    }

    public List<String> names() {
        return new ArrayList<>(hotkeys.keySet());
    }

    public List<Integer> getKeys(String name) {
        return hotkeys.get(name).keys;
    }

    /** Duplicates and codes beyond {@link #MAX_KEYS} are dropped; an empty list unbinds the hotkey. */
    public void setKeys(String name, List<Integer> keys) {
        List<Integer> unique = new ArrayList<>();
        for (Integer key : keys) {
            if (key != null && key != 0 && !unique.contains(key) && unique.size() < MAX_KEYS) unique.add(key);
        }
        Hotkey hotkey = hotkeys.get(name);
        hotkey.keys = Collections.unmodifiableList(unique);
        hotkey.pressed = false;
    }

    public boolean isDefault(String name) {
        Hotkey hotkey = hotkeys.get(name);
        return hotkey.keys.equals(hotkey.defaultKeys);
    }

    public void reset(String name) {
        setKeys(name, hotkeys.get(name).defaultKeys);
    }

    public boolean isPressed(String name) {
        return hotkeys.get(name).pressed;
    }

    /**
     * Fires {@code onPress} when a combination becomes held while its context allows it (upstream Action.DOWN).
     * The held state follows the keys even when the context forbids the action, so a key held while a screen
     * closes does not fire, and focus loss releases everything.
     */
    public void update(IntPredicate isDown, boolean windowActive, boolean screenOpen, boolean suspended, Consumer<String> onPress) {
        for (Map.Entry<String, Hotkey> entry : hotkeys.entrySet()) {
            Hotkey hotkey = entry.getValue();
            boolean down = windowActive && !hotkey.keys.isEmpty();
            for (int key : hotkey.keys) down &= isDown.test(key);
            boolean allowed = !suspended && (hotkey.anyContext || !screenOpen);
            if (down && !hotkey.pressed && allowed) onPress.accept(entry.getKey());
            hotkey.pressed = down;
        }
    }

    private static final class Hotkey {
        final boolean anyContext;
        final List<Integer> defaultKeys;
        volatile List<Integer> keys;
        boolean pressed;

        Hotkey(boolean anyContext, List<Integer> defaultKeys) {
            this.anyContext = anyContext;
            this.defaultKeys = Collections.unmodifiableList(defaultKeys);
            this.keys = this.defaultKeys;
        }
    }
}
