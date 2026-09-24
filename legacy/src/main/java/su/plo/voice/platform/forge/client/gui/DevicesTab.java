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
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.Lwjgl3Alc;
import su.plo.voice.platform.forge.client.audio.MicrophoneTest;

/** Upstream DevicesTabWidget, limited to the options the 1.7.10 audio backend implements. */
@SideOnly(Side.CLIENT)
final class DevicesTab extends SettingsTab {
    private static final String OPEN_AL_SOFT_PREFIX = "OpenAL Soft on ";
    private static final double DEFAULT_THRESHOLD = -30D;
    private static final ResourceLocation TEST_STOP_ICON = new ResourceLocation("plasmovoice", "textures/icons/speaker_menu.png");
    private static final ResourceLocation TEST_START_ICON =
            new ResourceLocation("plasmovoice", "textures/icons/speaker_menu_disabled.png");

    DevicesTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        addCategory("gui.plasmovoice.devices.microphone");
        // Upstream ActivationThresholdWidget: AudioUtil.audioLevelToDoubleRange / doubleRangeToAudioLevel, 1 dB steps,
        // the microphone level behind the knob and the microphone test button.
        MicrophoneTest test = state.getMicrophoneTest();
        SliderWidget threshold = new SliderWidget(ELEMENT_WIDTH - 24,
                () -> 1D - Math.max(-60D, state.getActivationThreshold()) / -60D,
                value -> state.setActivationThreshold(Math.round((1D - value) * -60D)),
                value -> Math.round(value * 60D) / 60D,
                () -> String.format("%.0f dB", state.getActivationThreshold()));
        threshold.level = () -> test.value(System.currentTimeMillis());
        IconWidget testButton = new IconWidget(() -> test.isActive() ? TEST_STOP_ICON : TEST_START_ICON,
                () -> {
                    if (test.isActive()) {
                        test.stop();
                    } else {
                        test.start();
                    }
                },
                test::isInputOpen,
                () -> test.isInputOpen() ? null : I18n.format("gui.plasmovoice.devices.not_available"));
        addOption(I18n.format("gui.plasmovoice.devices.activation_threshold"),
                "gui.plasmovoice.devices.activation_threshold.tooltip", threshold,
                () -> state.getActivationThreshold() == DEFAULT_THRESHOLD,
                () -> state.setActivationThreshold(DEFAULT_THRESHOLD), testButton);
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
