package su.plo.voice.platform.forge.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;
import su.plo.voice.platform.forge.client.ClientState;

/**
 * Upstream AdvancedTabWidget. Stereo sources to mono and source types overlap are left out: they only affect
 * stereo and direct sources, which only server addons create.
 */
@SideOnly(Side.CLIENT)
final class AdvancedTab extends SettingsTab {
    AdvancedTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        addCategory("gui.plasmovoice.advanced.visual");
        addToggle("gui.plasmovoice.advanced.visualize_voice_distance", true,
                state::isVisualizeVoiceDistance, state::setVisualizeVoiceDistance);
        addToggle("gui.plasmovoice.advanced.visualize_voice_distance_on_join", false,
                state::isVisualizeVoiceDistanceOnJoin, state::setVisualizeVoiceDistanceOnJoin);

        addCategory("gui.plasmovoice.advanced.audio_engine");
        // Upstream IntSliderWidget over 100..360 in 1 degree steps, shown as the bare number.
        addOption(I18n.format("gui.plasmovoice.advanced.directional_sources_angle"),
                "gui.plasmovoice.advanced.directional_sources_angle.tooltip",
                new SliderWidget(ELEMENT_WIDTH,
                        () -> (state.getDirectionalSourcesAngle() - 100) / 260D,
                        value -> state.setDirectionalSourcesAngle((int) Math.round(value * 260D) + 100),
                        value -> Math.round(value * 260D) / 260D,
                        () -> String.valueOf(state.getDirectionalSourcesAngle())),
                () -> state.getDirectionalSourcesAngle() == 145, () -> state.setDirectionalSourcesAngle(145));
        addToggle("gui.plasmovoice.advanced.panning", true, state::isPanning, state::setPanning);
        addOption(I18n.format("gui.plasmovoice.advanced.adaptive_jitter_buffer"),
                "gui.plasmovoice.advanced.adaptive_jitter_buffer.tooltip",
                new ToggleWidget(ELEMENT_WIDTH, state::isAdaptiveJitterBuffer, state::setAdaptiveJitterBuffer),
                () -> !state.isAdaptiveJitterBuffer(), () -> state.setAdaptiveJitterBuffer(false));

        addCategory("gui.plasmovoice.advanced.exponential_volume");
        addToggle("gui.plasmovoice.advanced.exponential_volume.volume_slider", true,
                state::isExponentialVolumeSlider, state::setExponentialVolumeSlider);
        addToggle("gui.plasmovoice.advanced.exponential_volume.distance_gain", true,
                state::isExponentialDistanceGain, state::setExponentialDistanceGain);
    }

    private void addToggle(String key, boolean defaultValue, BooleanSupplier getter, Consumer<Boolean> setter) {
        addOption(I18n.format(key), null, new ToggleWidget(ELEMENT_WIDTH, getter, setter),
                () -> getter.getAsBoolean() == defaultValue, () -> setter.accept(defaultValue));
    }
}
