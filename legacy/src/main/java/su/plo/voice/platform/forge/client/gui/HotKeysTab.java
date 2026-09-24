package su.plo.voice.platform.forge.client.gui;

import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.VoiceHotkeys;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

/** Upstream HotKeysTabWidget: the general hotkeys and the distance hotkeys of each activation. */
@SideOnly(Side.CLIENT)
final class HotKeysTab extends SettingsTab {
    HotKeysTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        addCategory("key.plasmovoice.general");
        addHotkey(VoiceHotkeys.MUTE_MICROPHONE, VoiceHotkeys.MUTE_MICROPHONE);
        addHotkey(VoiceHotkeys.DISABLE_VOICE, VoiceHotkeys.DISABLE_VOICE);
        addHotkey(VoiceHotkeys.ACTION, VoiceHotkeys.ACTION);

        ClientConfig config = state.getConnection() == null ? null : state.getConnection().getConfig();
        VoiceActivation proximity = config == null ? null : config.activation(VoiceActivation.PROXIMITY_ID);
        if (proximity == null) return;
        List<Integer> distances = proximity.getDistances();
        if (distances.isEmpty() || distances.get(0) == -1) return;
        addCategory("key.plasmovoice.distance", state.translate(proximity.getTranslation()));
        addHotkey("key.plasmovoice.distance.increase", VoiceHotkeys.PROXIMITY_DISTANCE_INCREASE);
        addHotkey("key.plasmovoice.distance.decrease", VoiceHotkeys.PROXIMITY_DISTANCE_DECREASE);
    }
}
