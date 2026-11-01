package su.plo.voice.platform.forge.client.gui;

import java.util.Arrays;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.client.lang.VoiceLang;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.VoiceHotkeys;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

/**
 * Upstream ActivationTabWidget: the proximity activation first, then one section for each other activation of the
 * server. Only those can inherit; the conflict warning icons are left out.
 */
@SideOnly(Side.CLIENT)
final class ActivationTab extends SettingsTab {
    private static final ResourceLocation ENABLED_ICON = new ResourceLocation("plasmovoice", "textures/icons/microphone_menu.png");
    private static final ResourceLocation DISABLED_ICON = new ResourceLocation("plasmovoice", "textures/icons/microphone_menu_disabled.png");

    ActivationTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        ClientConfig config = state.getConnection() == null ? null : state.getConnection().getConfig();
        if (config == null) return;
        VoiceActivation proximity = config.activation(VoiceActivation.PROXIMITY_ID);
        if (proximity != null) addActivation(config, proximity, false);
        for (VoiceActivation activation : config.otherActivations()) {
            state.getHotkeys().registerActivation(activation.getName());
            addActivation(config, activation, true);
        }
    }

    private void addActivation(ClientConfig config, VoiceActivation activation, boolean canInherit) {
        String name = activation.getName();
        String translated = state.translate(activation.getTranslation());
        addCategoryText(translated);
        addType(name, translated, canInherit);
        // Upstream createActivationButton: the push-to-talk key, or the toggle key for voice activation.
        if (state.getActivationType(name) == CaptureActivation.Type.PUSH_TO_TALK) {
            addHotkey("gui.plasmovoice.activation.ptt_button", VoiceHotkeys.pushToTalkKey(name));
        } else {
            addHotkey("gui.plasmovoice.activation.toggle_button", VoiceHotkeys.toggleKey(name));
        }
        addDistance(config, activation);
    }

    private void addType(String name, String activationName, boolean canInherit) {
        List<String> types = canInherit
                ? Arrays.asList(VoiceLang.format("gui.plasmovoice.activation.type_ptt"),
                        VoiceLang.format("gui.plasmovoice.activation.type_voice"),
                        VoiceLang.format("gui.plasmovoice.activation.type_inherit"))
                : Arrays.asList(VoiceLang.format("gui.plasmovoice.activation.type_ptt"),
                        VoiceLang.format("gui.plasmovoice.activation.type_voice"));
        boolean pushToTalk = state.getActivationType(name) == CaptureActivation.Type.PUSH_TO_TALK;
        DropDownWidget dropDown = new DropDownWidget(pushToTalk ? ELEMENT_WIDTH : ELEMENT_WIDTH - 24,
                () -> types.get(state.getActivationType(name).ordinal()), types, index -> {
                    state.setActivationType(name, CaptureActivation.Type.values()[index]);
                    rebuild();
                });
        Runnable reset = () -> {
            state.setActivationType(name, CaptureActivation.Type.PUSH_TO_TALK);
            rebuild();
        };
        String label = VoiceLang.format("gui.plasmovoice.activation.type");
        if (pushToTalk) {
            addOption(label, null, dropDown, () -> true, reset);
            return;
        }
        // Upstream ActivationToggleStateEntry: switches voice and inherit activations on and off.
        IconWidget toggle = new IconWidget(
                () -> state.isActivationToggled(name) ? DISABLED_ICON : ENABLED_ICON,
                () -> state.setActivationToggled(name, !state.isActivationToggled(name)),
                () -> true,
                () -> VoiceLang.format("gui.plasmovoice.activation.toggle", activationName,
                        EnumChatFormatting.GRAY + VoiceLang.format("gui.plasmovoice.toggle.currently", state.isActivationToggled(name)
                                ? EnumChatFormatting.RED + VoiceLang.format("gui.plasmovoice.toggle.disabled")
                                : EnumChatFormatting.GREEN + VoiceLang.format("gui.plasmovoice.toggle.enabled"))));
        addOption(label, null, dropDown, () -> false, reset, toggle);
    }

    /** Upstream DistanceSliderWidget: steps through the distances the server offers. */
    private void addDistance(ClientConfig config, VoiceActivation activation) {
        List<Integer> distances = activation.getDistances();
        // ponytail: a free distance range (-1, max) gets no control until a server needs the upstream text field.
        if (distances.isEmpty() || distances.get(0) == -1) return;
        int last = distances.size() - 1;
        addOption(VoiceLang.format("gui.plasmovoice.activation.distance", state.translate(activation.getTranslation())), null,
                new SliderWidget(ELEMENT_WIDTH,
                        () -> last == 0 ? 0D : distances.indexOf(current(config, activation)) / (double) last,
                        value -> state.changeActivationDistance(activation.getId(), distances.get((int) Math.round(value * last))),
                        value -> last == 0 ? 0D : Math.round(value * last) / (double) last,
                        () -> String.valueOf(current(config, activation))),
                () -> current(config, activation) == activation.getDefaultDistance(),
                () -> state.changeActivationDistance(activation.getId(), activation.getDefaultDistance()));
    }

    private int current(ClientConfig config, VoiceActivation activation) {
        return ClientConfig.allowedDistance(activation,
                state.getActivationDistance(config.getPacket().getServerId(), activation.getId()));
    }
}
