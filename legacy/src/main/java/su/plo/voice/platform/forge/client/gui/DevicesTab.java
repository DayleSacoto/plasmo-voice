package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.Lwjgl3Alc;

/** Upstream DevicesTabWidget, limited to the options the 1.7.10 audio backend implements. */
@SideOnly(Side.CLIENT)
final class DevicesTab extends SettingsTab {
    private static final String OPEN_AL_SOFT_PREFIX = "OpenAL Soft on ";
    private static final double DEFAULT_THRESHOLD = -30D;

    DevicesTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        addCategory("gui.plasmovoice.devices.microphone");
        // Upstream AudioUtil.audioLevelToDoubleRange / doubleRangeToAudioLevel, 1 dB steps.
        addOption(I18n.format("gui.plasmovoice.devices.activation_threshold"),
                "gui.plasmovoice.devices.activation_threshold.tooltip",
                new SliderWidget(ELEMENT_WIDTH,
                        () -> 1D - Math.max(-60D, state.getActivationThreshold()) / -60D,
                        value -> state.setActivationThreshold(Math.round((1D - value) * -60D)),
                        value -> Math.round(value * 60D) / 60D,
                        () -> String.format("%.0f dB", state.getActivationThreshold())),
                () -> state.getActivationThreshold() == DEFAULT_THRESHOLD,
                () -> state.setActivationThreshold(DEFAULT_THRESHOLD));
        addDevice("gui.plasmovoice.devices.microphone", Lwjgl3Alc.inputDevices(), Lwjgl3Alc.defaultInputDevice(),
                state::getInputDevice, state::setInputDevice, !state.isInputDeviceDisabled());
        addVolume("gui.plasmovoice.devices.microphone_volume", state::getMicrophoneVolume, state::setMicrophoneVolume);
        addToggle("gui.plasmovoice.devices.stereo_capture", state::isStereoCapture, state::setStereoCapture);
        addToggle("gui.plasmovoice.devices.disable_input_device", state::isInputDeviceDisabled, disabled -> {
            state.setInputDeviceDisabled(disabled);
            rebuild();
        });

        addCategory("gui.plasmovoice.devices.output");
        addDevice("gui.plasmovoice.devices.output_device", Lwjgl3Alc.outputDevices(), Lwjgl3Alc.defaultOutputDevice(),
                state::getOutputDevice, state::setOutputDevice, true);
        addVolume("gui.plasmovoice.devices.volume", state::getVolume, state::setVolume);
    }

    /** Selecting the system default stores an empty name, like upstream. */
    private void addDevice(String labelKey, List<String> devices, String defaultDevice,
                           Supplier<String> current, Consumer<String> select, boolean enabled) {
        List<String> names = new ArrayList<>();
        for (String device : devices) names.add(format(device));
        DropDownWidget dropDown = new DropDownWidget(ELEMENT_WIDTH,
                () -> {
                    if (devices.isEmpty()) return I18n.format("gui.plasmovoice.devices.not_available");
                    String selected = current.get();
                    return format(selected.isEmpty() && defaultDevice != null ? defaultDevice : selected);
                },
                names,
                index -> {
                    String device = devices.get(index);
                    select.accept(device.equals(defaultDevice) ? "" : device);
                });
        dropDown.active = enabled && !devices.isEmpty();
        addOption(I18n.format(labelKey), null, dropDown, () -> current.get().isEmpty(), () -> select.accept(""));
    }

    /** Upstream VolumeSliderWidget: 0-200 % in 5 % steps; holding left shift disables the snapping. */
    private void addVolume(String labelKey, DoubleSupplier current, DoubleConsumer set) {
        addOption(I18n.format(labelKey), "gui.plasmovoice.devices.volume.tooltip",
                new SliderWidget(ELEMENT_WIDTH,
                        () -> current.getAsDouble() / 2D,
                        value -> set.accept(value * 2D),
                        DevicesTab::snapVolume,
                        () -> Math.round(current.getAsDouble() * 100D) + "%"),
                () -> current.getAsDouble() == 1D,
                () -> set.accept(1D));
    }

    /** Slider value 0..1 for 0..200 %: 5 % steps unless left shift is held. */
    static double snapVolume(double value) {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ? value : Math.round(value * 200D / 5D) * 5D / 200D;
    }

    private void addToggle(String labelKey, BooleanSupplier current, Consumer<Boolean> set) {
        addOption(I18n.format(labelKey), labelKey + ".tooltip", new ToggleWidget(ELEMENT_WIDTH, current, set),
                () -> !current.getAsBoolean(), () -> set.accept(false));
    }

    /** Upstream GuiUtil.formatDeviceName. */
    static String format(String device) {
        return device.startsWith(OPEN_AL_SOFT_PREFIX) ? device.substring(OPEN_AL_SOFT_PREFIX.length()) : device;
    }
}
