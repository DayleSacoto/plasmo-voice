package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.hud.HudOptions;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;

/**
 * Upstream OverlayTabWidget. "Show On Static Sources" is left out: static sources come only from server addons.
 * Source lines without a player list are switched on and off, like upstream.
 */
@SideOnly(Side.CLIENT)
final class OverlayTab extends SettingsTab {
    OverlayTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
    }

    @Override
    void build() {
        addCategory("gui.plasmovoice.overlay.activation_icon");
        addOption(I18n.format("gui.plasmovoice.overlay.activation_icon_show"), null,
                new ToggleWidget(ELEMENT_WIDTH, state::isShowActivationIcon, state::setShowActivationIcon),
                state::isShowActivationIcon, () -> state.setShowActivationIcon(true));
        addOption(I18n.format("gui.plasmovoice.overlay.activation_icon_position"), null,
                new ButtonWidget(ELEMENT_WIDTH, () -> I18n.format(state.getActivationIconPosition().translation),
                        this::chooseIconPosition),
                () -> state.getActivationIconPosition() == HudOptions.IconPosition.BOTTOM_CENTER,
                () -> state.setActivationIconPosition(HudOptions.IconPosition.BOTTOM_CENTER));

        addCategory("gui.plasmovoice.overlay.source_icons");
        List<String> iconModes = new ArrayList<>();
        for (String mode : new String[] {"hud", "always", "hidden"}) {
            iconModes.add(I18n.format("gui.plasmovoice.overlay.show_source_icons." + mode));
        }
        addOption(I18n.format("gui.plasmovoice.overlay.show_source_icons"), null,
                new DropDownWidget(ELEMENT_WIDTH, () -> iconModes.get(state.getShowSourceIcons()), iconModes,
                        state::setShowSourceIcons),
                () -> state.getShowSourceIcons() == 0, () -> state.setShowSourceIcons(0));

        addCategory("gui.plasmovoice.overlay");
        addOption(I18n.format("gui.plasmovoice.overlay.enable"), null,
                new ToggleWidget(ELEMENT_WIDTH, state::isOverlayEnabled, state::setOverlayEnabled),
                state::isOverlayEnabled, () -> state.setOverlayEnabled(true));
        addOption(I18n.format("gui.plasmovoice.overlay.position"), null,
                new ButtonWidget(ELEMENT_WIDTH, () -> I18n.format(state.getOverlayPosition().translation),
                        this::chooseOverlayPosition),
                () -> state.getOverlayPosition() == HudOptions.OverlayPosition.TOP_LEFT,
                () -> state.setOverlayPosition(HudOptions.OverlayPosition.TOP_LEFT));
        List<String> styles = new ArrayList<>();
        for (HudOptions.OverlayStyle style : HudOptions.OverlayStyle.values()) styles.add(I18n.format(style.translation));
        addOption(I18n.format("gui.plasmovoice.overlay.style"), null,
                new DropDownWidget(ELEMENT_WIDTH, () -> styles.get(state.getOverlayStyle().ordinal()), styles,
                        index -> state.setOverlayStyle(HudOptions.OverlayStyle.values()[index])),
                () -> state.getOverlayStyle() == HudOptions.OverlayStyle.NAME_SKIN,
                () -> state.setOverlayStyle(HudOptions.OverlayStyle.NAME_SKIN));

        ClientConfig config = state.getConnection() == null ? null : state.getConnection().getConfig();
        if (config == null) return;
        addCategory("gui.plasmovoice.overlay.sources");
        List<VoiceSourceLine> lines = new ArrayList<>(config.getPacket().getSourceLines());
        lines.sort((a, b) -> Integer.compare(a.getWeight(), b.getWeight()));
        for (VoiceSourceLine line : lines) {
            String name = line.getName();
            addIconOption(new ResourceLocation(line.getIcon()), state.translate(line.getTranslation()), null,
                    new ToggleWidget(ELEMENT_WIDTH,
                            () -> state.getOverlaySourceState(name) == HudOptions.OverlaySourceState.ON,
                            on -> state.setOverlaySourceState(name,
                                    on ? HudOptions.OverlaySourceState.ON : HudOptions.OverlaySourceState.OFF)),
                    () -> state.getOverlaySourceState(name) == HudOptions.OverlaySourceState.OFF,
                    () -> state.setOverlaySourceState(name, HudOptions.OverlaySourceState.OFF));
        }
    }

    /** Upstream disables the anchor the other HUD element already uses. */
    private void chooseIconPosition() {
        HudOptions.IconPosition[] positions = HudOptions.IconPosition.values();
        String[] translations = new String[positions.length];
        for (int i = 0; i < positions.length; i++) translations[i] = positions[i].translation;
        Minecraft.getMinecraft().displayGuiScreen(new HudPositionScreen(screen,
                "gui.plasmovoice.overlay.activation_icon_position.choose", positions, translations,
                state.getOverlayPosition().name(), index -> state.setActivationIconPosition(positions[index])));
    }

    private void chooseOverlayPosition() {
        HudOptions.OverlayPosition[] positions = HudOptions.OverlayPosition.values();
        String[] translations = new String[positions.length];
        for (int i = 0; i < positions.length; i++) translations[i] = positions[i].translation;
        Minecraft.getMinecraft().displayGuiScreen(new HudPositionScreen(screen,
                "gui.plasmovoice.overlay.position.choose", positions, translations,
                state.getActivationIconPosition().name(), index -> state.setOverlayPosition(positions[index])));
    }
}
