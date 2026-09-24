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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.JavaxInput;
import su.plo.voice.platform.forge.client.audio.Lwjgl3Alc;
import su.plo.voice.platform.forge.client.audio.MicrophoneTest;

/** Upstream DevicesTabWidget, limited to the options the 1.7.10 audio backend implements. */
@SideOnly(Side.CLIENT)
final class DevicesTab extends SettingsTab {
    private static final String OPEN_AL_SOFT_PREFIX = "OpenAL Soft on ";
    private static final double DEFAULT_THRESHOLD = -30D;
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    private static final ResourceLocation WARNING_ICON = new ResourceLocation("plasmovoice", "textures/icons/warning.png");
    /** Upstream Colors.WARNING. */
    private static final int WARNING_COLOR = 0xFAC653;
    private static final String MICROPHONE_HELP_URL = "https://plasmovoice.com/docs/client/microphone-not-available";
    private static final ResourceLocation TEST_STOP_ICON = new ResourceLocation("plasmovoice", "textures/icons/speaker_menu.png");
    private static final ResourceLocation TEST_START_ICON =
            new ResourceLocation("plasmovoice", "textures/icons/speaker_menu_disabled.png");

    DevicesTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        addCategory("gui.plasmovoice.devices.microphone");
        // Upstream ActivationThresholdWidget: 1 dB steps, the microphone level behind the knob and the test button.
        MicrophoneTest test = state.getMicrophoneTest();
        SliderWidget threshold = new SliderWidget(ELEMENT_WIDTH - 24,
                () -> MicrophoneTest.levelToRange(state.getActivationThreshold()),
                value -> state.setActivationThreshold(MicrophoneTest.rangeToLevel(value)),
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
        // Upstream lists the Java Sound mixers with use_javax_input; the first one is the default.
        List<String> inputDevices = state.isUseJavaxInput() ? JavaxInput.deviceNames() : Lwjgl3Alc.inputDevices();
        String defaultInput = state.isUseJavaxInput()
                ? (inputDevices.isEmpty() ? null : inputDevices.get(0))
                : Lwjgl3Alc.defaultInputDevice();
        addDevice("gui.plasmovoice.devices.microphone", inputDevices, defaultInput,
                state::getInputDevice, state::setInputDevice, !state.isInputDeviceDisabled(),
                state.isInputDeviceFailed() ? microphoneWarning(defaultInput) : null);
        addVolume("gui.plasmovoice.devices.microphone_volume", state::getMicrophoneVolume, state::setMicrophoneVolume);
        addToggle("gui.plasmovoice.devices.noise_suppression", state::isNoiseSuppression, state::setNoiseSuppression)
                .active = state.isNoiseSuppressionAvailable();
        // Upstream disables stereo capture with use_javax_input.
        addToggle("gui.plasmovoice.devices.stereo_capture", state::isStereoCapture, state::setStereoCapture)
                .active = !state.isUseJavaxInput();
        addToggle("gui.plasmovoice.devices.disable_input_device", state::isInputDeviceDisabled, disabled -> {
            state.setInputDeviceDisabled(disabled);
            rebuild();
        });

        addCategory("gui.plasmovoice.devices.output");
        addDevice("gui.plasmovoice.devices.output_device", Lwjgl3Alc.outputDevices(), Lwjgl3Alc.defaultOutputDevice(),
                state::getOutputDevice, state::setOutputDevice, true, null);
        addVolume("gui.plasmovoice.devices.volume", state::getVolume, state::setVolume);
        addToggle("gui.plasmovoice.devices.occlusion", state::isSoundOcclusion, state::setSoundOcclusion);
        addToggle("gui.plasmovoice.devices.directional_sources", state::isDirectionalSources, state::setDirectionalSources);
        // Upstream shows HRTF on every device; without ALC_SOFT_HRTF the output simply stays as it is.
        addToggle("gui.plasmovoice.devices.hrtf", state::isHrtf, state::setHrtf);
    }

    /** Selecting the system default stores an empty name, like upstream. */
    private void addDevice(String labelKey, List<String> devices, String defaultDevice,
                           Supplier<String> current, Consumer<String> select, boolean enabled, IconWidget warning) {
        List<String> names = new ArrayList<>();
        for (String device : devices) names.add(format(device));
        DropDownWidget dropDown = new DropDownWidget(warning == null ? ELEMENT_WIDTH : ELEMENT_WIDTH - 24,
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
        if (warning == null) {
            addOption(I18n.format(labelKey), null, dropDown, () -> current.get().isEmpty(), () -> select.accept(""));
        } else {
            addOption(I18n.format(labelKey), null, dropDown, () -> current.get().isEmpty(), () -> select.accept(""), warning);
        }
    }

    /** Upstream input device error button: the device that failed, and the wiki page on click. */
    private IconWidget microphoneWarning(String defaultDevice) {
        String device = state.getInputDevice().isEmpty() ? defaultDevice : state.getInputDevice();
        IconWidget warning = new IconWidget(() -> WARNING_ICON, () -> openUri(MICROPHONE_HELP_URL), () -> true,
                () -> I18n.format("gui.plasmovoice.devices.failed_to_initialize_microphone.tooltip",
                        format(device == null ? "" : device)));
        warning.iconColor = WARNING_COLOR;
        return warning;
    }

    /** Upstream MinecraftUtil.openUri; vanilla 1.7.10 opens links through java.awt.Desktop too. */
    private static void openUri(String uri) {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(uri));
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Failed to open {}: {}", uri, e.toString());
        }
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

    private ToggleWidget addToggle(String labelKey, BooleanSupplier current, Consumer<Boolean> set) {
        ToggleWidget toggle = new ToggleWidget(ELEMENT_WIDTH, current, set);
        addOption(I18n.format(labelKey), labelKey + ".tooltip", toggle, () -> !current.getAsBoolean(), () -> set.accept(false));
        return toggle;
    }

    /** Upstream GuiUtil.formatDeviceName. */
    static String format(String device) {
        return device.startsWith(OPEN_AL_SOFT_PREFIX) ? device.substring(OPEN_AL_SOFT_PREFIX.length()) : device;
    }
}
