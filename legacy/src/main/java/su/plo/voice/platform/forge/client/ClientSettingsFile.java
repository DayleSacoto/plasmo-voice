package su.plo.voice.platform.forge.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.hud.HudOptions;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

/**
 * config/plasmovoice/client.cfg: the persisted part of {@link ClientState}, with upstream client.toml names.
 * Written on the client thread when the settings screen closes or a hotkey toggles a setting.
 */
@SideOnly(Side.CLIENT)
public final class ClientSettingsFile {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    static final double DEFAULT_THRESHOLD = -30D;
    private static final String VOICE = "voice";
    private static final String ACTIVATIONS = "activations";
    private static final String SERVERS = "servers";
    private static final String KEY_BINDINGS = "key_bindings";
    private static final String OVERLAY = "overlay";
    private static final String ADVANCED = "advanced";
    private static final String SOURCE_STATES = OVERLAY + ".source_states";
    private static final String VOLUMES = VOICE + ".volumes";

    private ClientSettingsFile() {
    }

    public static void load(File file, ClientState state) {
        Configuration config = new Configuration(file);
        state.setVoiceDisabled(config.get(VOICE, "disabled", false).getBoolean(false));
        state.setMicrophoneMuted(config.get(VOICE, "microphone_disabled", false).getBoolean(false));
        state.setActivationThreshold(config.get(VOICE, "activation_threshold", DEFAULT_THRESHOLD).getDouble(DEFAULT_THRESHOLD));
        state.setInputDevice(config.get(VOICE, "input_device", "").getString());
        state.setOutputDevice(config.get(VOICE, "output_device", "").getString());
        state.setStereoCapture(config.get(VOICE, "stereo_capture", false).getBoolean(false));
        state.setInputDeviceDisabled(config.get(VOICE, "disable_input_device", false).getBoolean(false));
        state.setMicrophoneVolume(config.get(VOICE, "microphone_volume", 1D).getDouble(1D));
        state.setVolume(config.get(VOICE, "volume", 1D).getDouble(1D));

        String proximity = ACTIVATIONS + "." + VoiceActivation.PROXIMITY_ID;
        String type = config.get(proximity, "type", CaptureActivation.Type.PUSH_TO_TALK.name()).getString();
        try {
            state.setActivationType(CaptureActivation.Type.valueOf(type));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown activation type {} in {}; using push-to-talk", type, file.getName());
        }
        state.setActivationToggled(config.get(proximity, "toggle", false).getBoolean(false));

        state.setShowActivationIcon(config.get(OVERLAY, "show_activation_icon", true).getBoolean(true));
        state.setActivationIconPosition(enumValue(config.get(OVERLAY, "activation_icon_position",
                HudOptions.IconPosition.BOTTOM_CENTER.name()), HudOptions.IconPosition.BOTTOM_CENTER, file));
        state.setOverlayEnabled(config.get(OVERLAY, "overlay_enabled", true).getBoolean(true));
        state.setOverlayPosition(enumValue(config.get(OVERLAY, "overlay_position",
                HudOptions.OverlayPosition.TOP_LEFT.name()), HudOptions.OverlayPosition.TOP_LEFT, file));
        state.setOverlayStyle(enumValue(config.get(OVERLAY, "overlay_style",
                HudOptions.OverlayStyle.NAME_SKIN.name()), HudOptions.OverlayStyle.NAME_SKIN, file));
        for (Map.Entry<String, Property> line : config.getCategory(SOURCE_STATES).getValues().entrySet()) {
            state.setOverlaySourceState(line.getKey(), enumValue(line.getValue(), HudOptions.OverlaySourceState.OFF, file));
        }
        state.setShowSourceIcons(Math.max(0, Math.min(2, config.get(OVERLAY, "show_source_icons", 0).getInt(0))));
        state.setVisualizeVoiceDistance(config.get(ADVANCED, "visualize_voice_distance", true).getBoolean(true));
        state.setVisualizeVoiceDistanceOnJoin(config.get(ADVANCED, "visualize_voice_distance_on_join", false).getBoolean(false));
        state.setPanning(config.get(ADVANCED, "panning", true).getBoolean(true));
        state.setExponentialVolumeSlider(config.get(ADVANCED, "exponential_volume_slider", true).getBoolean(true));
        state.setExponentialDistanceGain(config.get(ADVANCED, "exponential_distance_gain", true).getBoolean(true));

        for (ConfigCategory volume : config.getCategory(VOLUMES).getChildren()) {
            if (volume.containsKey("volume")) state.setSourceVolume(volume.getName(), volume.get("volume").getDouble(1D));
            if (volume.containsKey("muted")) state.setSourceMuted(volume.getName(), volume.get("muted").getBoolean(false));
        }

        VoiceHotkeys hotkeys = state.getHotkeys();
        for (String name : hotkeys.names()) {
            Property keys = config.get(KEY_BINDINGS, name, codes(hotkeys.getKeys(name)));
            try {
                hotkeys.setKeys(name, parseCodes(keys.getString()));
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid key binding {}={} in {}; using the default", name, keys.getString(), file.getName());
                hotkeys.reset(name);
            }
        }

        for (ConfigCategory server : config.getCategory(SERVERS).getChildren()) {
            UUID serverId = uuid(server.getName());
            if (serverId == null) continue;
            for (Map.Entry<String, Property> distance : server.getValues().entrySet()) {
                UUID activationId = uuid(distance.getKey());
                if (activationId != null && distance.getValue().isIntValue()) {
                    state.setActivationDistance(serverId, activationId, distance.getValue().getInt());
                }
            }
        }
        if (config.hasChanged()) config.save();
    }

    public static void save(File file, ClientState state) {
        Configuration config = new Configuration(file);
        config.get(VOICE, "disabled", false).set(state.isVoiceDisabled());
        config.get(VOICE, "microphone_disabled", false).set(state.isMicrophoneMuted());
        config.get(VOICE, "activation_threshold", DEFAULT_THRESHOLD).set(state.getActivationThreshold());
        config.get(VOICE, "input_device", "").set(state.getInputDevice());
        config.get(VOICE, "output_device", "").set(state.getOutputDevice());
        config.get(VOICE, "stereo_capture", false).set(state.isStereoCapture());
        config.get(VOICE, "disable_input_device", false).set(state.isInputDeviceDisabled());
        config.get(VOICE, "microphone_volume", 1D).set(state.getMicrophoneVolume());
        config.get(VOICE, "volume", 1D).set(state.getVolume());

        String proximity = ACTIVATIONS + "." + VoiceActivation.PROXIMITY_ID;
        config.get(proximity, "type", CaptureActivation.Type.PUSH_TO_TALK.name()).set(state.getActivationType().name());
        config.get(proximity, "toggle", false).set(state.isActivationToggled());

        config.get(OVERLAY, "show_activation_icon", true).set(state.isShowActivationIcon());
        config.get(OVERLAY, "activation_icon_position", "").set(state.getActivationIconPosition().name());
        config.get(OVERLAY, "overlay_enabled", true).set(state.isOverlayEnabled());
        config.get(OVERLAY, "overlay_position", "").set(state.getOverlayPosition().name());
        config.get(OVERLAY, "overlay_style", "").set(state.getOverlayStyle().name());
        state.overlaySourceStates().forEach((line, sourceState) -> config.get(SOURCE_STATES, line, "").set(sourceState.name()));
        config.get(OVERLAY, "show_source_icons", 0).set(state.getShowSourceIcons());
        config.get(ADVANCED, "visualize_voice_distance", true).set(state.isVisualizeVoiceDistance());
        config.get(ADVANCED, "visualize_voice_distance_on_join", false).set(state.isVisualizeVoiceDistanceOnJoin());
        config.get(ADVANCED, "panning", true).set(state.isPanning());
        config.get(ADVANCED, "exponential_volume_slider", true).set(state.isExponentialVolumeSlider());
        config.get(ADVANCED, "exponential_distance_gain", true).set(state.isExponentialDistanceGain());

        config.removeCategory(config.getCategory(VOLUMES));
        state.volumes().forEach((key, volume) -> config.get(VOLUMES + "." + key, "volume", 1D).set(volume));
        state.mutes().forEach((key, muted) -> config.get(VOLUMES + "." + key, "muted", false).set(muted));

        VoiceHotkeys hotkeys = state.getHotkeys();
        for (String name : hotkeys.names()) {
            config.get(KEY_BINDINGS, name, "").set(codes(hotkeys.getKeys(name)));
        }
        config.getCategory(KEY_BINDINGS).setComment("Keyboard key codes and mouse buttons (button - 100), comma separated; empty is unbound");

        config.removeCategory(config.getCategory(SERVERS));
        state.distancesByServer().forEach((serverId, distances) -> distances.forEach((activationId, distance) ->
                config.get(SERVERS + "." + serverId, activationId.toString(), 0).set(distance)));
        config.save();
    }

    private static <E extends Enum<E>> E enumValue(Property property, E fallback, File file) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), property.getString());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown value {}={} in {}; using {}", property.getName(), property.getString(), file.getName(), fallback);
            return fallback;
        }
    }

    static String codes(List<Integer> keys) {
        StringBuilder text = new StringBuilder();
        for (int key : keys) {
            if (text.length() > 0) text.append(',');
            text.append(key);
        }
        return text.toString();
    }

    static List<Integer> parseCodes(String text) {
        List<Integer> keys = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.trim().isEmpty()) keys.add(Integer.parseInt(part.trim()));
        }
        return keys;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
