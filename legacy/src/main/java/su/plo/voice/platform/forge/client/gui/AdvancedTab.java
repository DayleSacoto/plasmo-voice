package su.plo.voice.platform.forge.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;
import su.plo.voice.platform.forge.client.ClientState;

/**
 * Upstream AdvancedTabWidget. Directional sources angle, stereo sources to mono, source types overlap and the
 * adaptive jitter buffer are left out: they only affect source types that come from server addons.
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
        addToggle("gui.plasmovoice.advanced.panning", true, state::isPanning, state::setPanning);

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
