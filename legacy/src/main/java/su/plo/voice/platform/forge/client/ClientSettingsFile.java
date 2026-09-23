package su.plo.voice.platform.forge.client;

import java.io.File;
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

        config.removeCategory(config.getCategory(SERVERS));
        state.distancesByServer().forEach((serverId, distances) -> distances.forEach((activationId, distance) ->
                config.get(SERVERS + "." + serverId, activationId.toString(), 0).set(distance)));
        config.save();
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
