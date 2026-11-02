package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import su.plo.voice.platform.forge.client.lang.VoiceLang;
import net.minecraft.util.ResourceLocation;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.groups.ClientGroups;
import su.plo.voice.platform.forge.client.hud.GroupHudModel;
import su.plo.voice.platform.forge.client.hud.GroupHudPosition;
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
        addOption(VoiceLang.format("gui.plasmovoice.overlay.activation_icon_show"), null,
                new ToggleWidget(ELEMENT_WIDTH, state::isShowActivationIcon, state::setShowActivationIcon),
                state::isShowActivationIcon, () -> state.setShowActivationIcon(true));
        addOption(VoiceLang.format("gui.plasmovoice.overlay.activation_icon_position"), null,
                new ButtonWidget(ELEMENT_WIDTH, () -> VoiceLang.format(state.getActivationIconPosition().translation),
                        this::chooseIconPosition),
                () -> state.getActivationIconPosition() == HudOptions.IconPosition.BOTTOM_CENTER,
                () -> state.setActivationIconPosition(HudOptions.IconPosition.BOTTOM_CENTER));

        addCategory("gui.plasmovoice.overlay.source_icons");
        List<String> iconModes = new ArrayList<>();
        for (String mode : new String[] {"hud", "always", "hidden"}) {
            iconModes.add(VoiceLang.format("gui.plasmovoice.overlay.show_source_icons." + mode));
        }
        addOption(VoiceLang.format("gui.plasmovoice.overlay.show_source_icons"), null,
                new DropDownWidget(ELEMENT_WIDTH, () -> iconModes.get(state.getShowSourceIcons()), iconModes,
                        state::setShowSourceIcons),
                () -> state.getShowSourceIcons() == 0, () -> state.setShowSourceIcons(0));

        addCategory("gui.plasmovoice.overlay");
        addOption(VoiceLang.format("gui.plasmovoice.overlay.enable"), null,
                new ToggleWidget(ELEMENT_WIDTH, state::isOverlayEnabled, state::setOverlayEnabled),
                state::isOverlayEnabled, () -> state.setOverlayEnabled(true));
        addOption(VoiceLang.format("gui.plasmovoice.overlay.position"), null,
                new ButtonWidget(ELEMENT_WIDTH, () -> VoiceLang.format(state.getOverlayPosition().translation),
                        this::chooseOverlayPosition),
                () -> state.getOverlayPosition() == HudOptions.OverlayPosition.TOP_LEFT,
                () -> state.setOverlayPosition(HudOptions.OverlayPosition.TOP_LEFT));
        List<String> styles = new ArrayList<>();
        for (HudOptions.OverlayStyle style : HudOptions.OverlayStyle.values()) styles.add(VoiceLang.format(style.translation));
        addOption(VoiceLang.format("gui.plasmovoice.overlay.style"), null,
                new DropDownWidget(ELEMENT_WIDTH, () -> styles.get(state.getOverlayStyle().ordinal()), styles,
                        index -> state.setOverlayStyle(HudOptions.OverlayStyle.values()[index])),
                () -> state.getOverlayStyle() == HudOptions.OverlayStyle.NAME_SKIN,
                () -> state.setOverlayStyle(HudOptions.OverlayStyle.NAME_SKIN));

        ClientConfig config = state.getConnection() == null ? null : state.getConnection().getConfig();
        if (config != null) buildSources(config);
        addCategory("gui.plasmovoice.overlay.group_category");
        List<String> visibilities = new ArrayList<>();
        for (HudOptions.GroupOverlayVisibility visibility : HudOptions.GroupOverlayVisibility.values()) {
            visibilities.add(VoiceLang.format(visibility.translation));
        }
        addOption(VoiceLang.format("gui.plasmovoice.overlay.group_visibility"),
                "gui.plasmovoice.overlay.group_visibility.tooltip",
                new DropDownWidget(ELEMENT_WIDTH, () -> visibilities.get(state.getGroupHudVisibility().ordinal()),
                        visibilities,
                        index -> state.setGroupHudVisibility(HudOptions.GroupOverlayVisibility.values()[index])),
                () -> state.getGroupHudVisibility() == HudOptions.GroupOverlayVisibility.ALWAYS,
                () -> state.setGroupHudVisibility(HudOptions.GroupOverlayVisibility.ALWAYS));
        addOption(VoiceLang.format("gui.plasmovoice.overlay.group_style"), null,
                new DropDownWidget(ELEMENT_WIDTH, () -> styles.get(state.getGroupHudStyle().ordinal()), styles,
                        index -> state.setGroupHudStyle(HudOptions.OverlayStyle.values()[index])),
                () -> state.getGroupHudStyle() == HudOptions.OverlayStyle.NAME_SKIN,
                () -> state.setGroupHudStyle(HudOptions.OverlayStyle.NAME_SKIN));
        addOption(VoiceLang.format("gui.plasmovoice.overlay.group_opacity"), null,
                new SliderWidget(ELEMENT_WIDTH,
                        () -> state.getGroupHudBackgroundOpacity() / 100D,
                        value -> state.setGroupHudBackgroundOpacity((int) Math.round(value * 100D)),
                        value -> Math.round(value * 100D) / 100D,
                        () -> state.getGroupHudBackgroundOpacity() + "%"),
                () -> state.getGroupHudBackgroundOpacity() == GroupHudModel.DEFAULT_OPACITY,
                () -> state.setGroupHudBackgroundOpacity(GroupHudModel.DEFAULT_OPACITY));
        addOption(VoiceLang.format("gui.plasmovoice.overlay.group_position"), null,
                new ButtonWidget(ELEMENT_WIDTH, () -> VoiceLang.format("gui.plasmovoice.overlay.group_position.edit"),
                        () -> Minecraft.getMinecraft().displayGuiScreen(new GroupHudEditScreen(screen, state))),
                () -> GroupHudPosition.DEFAULT.equals(state.getGroupHudPlacement()), state::resetGroupHudPlacement);
    }

    private void buildSources(ClientConfig config) {
        List<VoiceSourceLine> lines = overlaySourceLines(config.getPacket().getSourceLines());
        if (lines.isEmpty()) return;
        addCategory("gui.plasmovoice.overlay.sources");
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

    /**
     * The source lines that get an Overlay Sources toggle, lightest first. The groups line is left out: group voice
     * arrives as direct sources that the generic overlay never lists, and the Group HUD (its own controls below)
     * shows group speakers instead.
     */
    static List<VoiceSourceLine> overlaySourceLines(Collection<VoiceSourceLine> sourceLines) {
        List<VoiceSourceLine> lines = new ArrayList<>();
        for (VoiceSourceLine line : sourceLines) {
            if (!ClientGroups.LINE_ID.equals(line.getId())) lines.add(line);
        }
        lines.sort((a, b) -> Integer.compare(a.getWeight(), b.getWeight()));
        return lines;
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
