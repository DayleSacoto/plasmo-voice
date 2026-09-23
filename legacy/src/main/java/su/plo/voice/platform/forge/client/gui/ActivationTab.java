package su.plo.voice.platform.forge.client.gui;

import java.util.Arrays;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

/** Upstream ActivationTabWidget for the proximity activation, the only one this backport captures for. */
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
        VoiceActivation proximity = config == null ? null : config.activation(VoiceActivation.PROXIMITY_ID);
        if (proximity == null) return;

        addCategory(proximity.getTranslation());
        addType(I18n.format(proximity.getTranslation()));
        addDistance(config, proximity);
    }

    private void addType(String activationName) {
        List<String> types = Arrays.asList(
                I18n.format("gui.plasmovoice.activation.type_ptt"),
                I18n.format("gui.plasmovoice.activation.type_voice"));
        boolean pushToTalk = state.getActivationType() == CaptureActivation.Type.PUSH_TO_TALK;
        DropDownWidget dropDown = new DropDownWidget(pushToTalk ? ELEMENT_WIDTH : ELEMENT_WIDTH - 24,
                () -> types.get(state.getActivationType().ordinal()), types, index -> {
                    state.setActivationType(CaptureActivation.Type.values()[index]);
                    rebuild();
                });
        Runnable reset = () -> {
            state.setActivationType(CaptureActivation.Type.PUSH_TO_TALK);
            rebuild();
        };
        String label = I18n.format("gui.plasmovoice.activation.type");
        if (pushToTalk) {
            addOption(label, null, dropDown, () -> true, reset);
            return;
        }
        // Upstream ActivationToggleStateEntry: switches voice activation on and off.
        IconWidget toggle = new IconWidget(
                () -> state.isActivationToggled() ? DISABLED_ICON : ENABLED_ICON,
                () -> state.setActivationToggled(!state.isActivationToggled()),
                () -> true,
                () -> I18n.format("gui.plasmovoice.activation.toggle", activationName,
                        EnumChatFormatting.GRAY + I18n.format("gui.plasmovoice.toggle.currently", state.isActivationToggled()
                                ? EnumChatFormatting.RED + I18n.format("gui.plasmovoice.toggle.disabled")
                                : EnumChatFormatting.GREEN + I18n.format("gui.plasmovoice.toggle.enabled"))));
        addOption(label, null, dropDown, () -> false, reset, toggle);
    }

    /** Upstream DistanceSliderWidget: steps through the distances the server offers. */
    private void addDistance(ClientConfig config, VoiceActivation activation) {
        List<Integer> distances = activation.getDistances();
        // ponytail: a free distance range (-1, max) gets no control until a server needs the upstream text field.
        if (distances.isEmpty() || distances.get(0) == -1) return;
        int last = distances.size() - 1;
        addOption(I18n.format("gui.plasmovoice.activation.distance", I18n.format(activation.getTranslation())), null,
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
