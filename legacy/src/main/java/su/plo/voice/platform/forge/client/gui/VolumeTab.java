package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import su.plo.slib.api.entity.player.McGameProfile;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.platform.forge.client.hud.VoiceHud;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;
import su.plo.voice.proto.data.player.VoicePlayerInfo;

/** Upstream VolumeTabWidget: volume and mute of each source line and of each voice player, with a player search. */
@SideOnly(Side.CLIENT)
final class VolumeTab extends SettingsTab {
    private static final ResourceLocation SPEAKER_ICON = new ResourceLocation("plasmovoice", "textures/icons/speaker_menu.png");
    private static final ResourceLocation SPEAKER_MUTED_ICON =
            new ResourceLocation("plasmovoice", "textures/icons/speaker_menu_disabled.png");
    private static final int SEARCH_ROW_HEIGHT = 26;
    private static final int PLAYER_ROW_HEIGHT = 30;

    private final TextFieldWidget search;
    private String query = "";
    /** Players of the last build, to rebuild when one joins or leaves (upstream VoicePlayerConnectedEvent). */
    private Map<UUID, String> shownPlayers = new LinkedHashMap<>();

    VolumeTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
        search = new TextFieldWidget(Minecraft.getMinecraft().fontRenderer, CONTAINER_WIDTH,
                I18n.format("gui.plasmovoice.volume.players_search"), text -> {
                    query = text.toLowerCase(Locale.ROOT);
                    rebuild();
                });
    }

    /** Upstream focuses the search whenever the tab opens. */
    @Override
    void init(int top, int bottom) {
        super.init(top, bottom);
        search.setFocused(true);
    }

    /** A focused field keeps lwjgl3ify's text input on, so it is released with the tab. */
    @Override
    void removed() {
        super.removed();
        search.setFocused(false);
    }

    @Override
    void build() {
        ClientConnectionState connection = state.getConnection();
        ClientConfig config = connection == null ? null : connection.getConfig();
        if (config == null) return;

        addCategory("gui.plasmovoice.volume.sources");
        List<VoiceSourceLine> lines = new ArrayList<>(config.getPacket().getSourceLines());
        lines.sort((a, b) -> Integer.compare(a.getWeight(), b.getWeight()));
        for (VoiceSourceLine line : lines) {
            addVolume(ROW_HEIGHT, iconLabel(new ResourceLocation(line.getIcon())), state.translate(line.getTranslation()), line.getName());
        }

        addCategory("gui.plasmovoice.volume.players");
        addFullWidth(search, SEARCH_ROW_HEIGHT);
        shownPlayers = players(connection, config);
        shownPlayers.forEach((playerId, name) -> addVolume(PLAYER_ROW_HEIGHT, (mc, x, centerY) -> {
            GL11.glEnable(GL11.GL_BLEND);
            VoiceHud.drawHead(mc, playerId, x, centerY - 12, 24);
            return 30;
        }, name, ClientState.playerVolumeKey(playerId)));
    }

    @Override
    void tick() {
        search.tick();
        ClientConnectionState connection = state.getConnection();
        ClientConfig config = connection == null ? null : connection.getConfig();
        if (config != null && !players(connection, config).equals(shownPlayers)) rebuild();
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!search.isMouseOver(mouseX, mouseY)) search.setFocused(false);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Upstream createVolumeSlider plus the mute toggle; reset restores both. */
    private void addVolume(int height, LabelIcon labelIcon, String label, String key) {
        SliderWidget slider = new SliderWidget(ELEMENT_WIDTH - 24,
                () -> state.getSourceVolume(key) / 2D,
                value -> state.setSourceVolume(key, value * 2D),
                DevicesTab::snapVolume,
                () -> Math.round(state.getSourceVolume(key) * 100D) + "%");
        IconWidget mute = new IconWidget(() -> state.isSourceMuted(key) ? SPEAKER_MUTED_ICON : SPEAKER_ICON,
                () -> state.setSourceMuted(key, !state.isSourceMuted(key)), () -> true, null);
        addRow(height, labelIcon, label, null, slider,
                () -> state.getSourceVolume(key) == 1D && !state.isSourceMuted(key),
                () -> {
                    state.setSourceVolume(key, 1D);
                    state.setSourceMuted(key, false);
                },
                mute);
    }

    /**
     * Upstream refreshPlayerEntries: voice players matching the search plus the players of source lines,
     * without the local player, sorted by name.
     */
    private Map<UUID, String> players(ClientConnectionState connection, ClientConfig config) {
        Map<UUID, String> players = new LinkedHashMap<>();
        for (VoicePlayerInfo player : connection.getPlayers()) {
            if (player.getPlayerNick().toLowerCase(Locale.ROOT).contains(query)) {
                players.put(player.getPlayerId(), player.getPlayerNick());
            }
        }
        for (VoiceSourceLine line : config.getPacket().getSourceLines()) {
            if (line.getPlayers() == null) continue;
            for (McGameProfile player : line.getPlayers()) players.put(player.getId(), player.getName());
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            players.remove(connection.localPlayerId(mc.thePlayer.getCommandSenderName(), mc.thePlayer.getUniqueID()));
        }

        List<Map.Entry<UUID, String>> sorted = new ArrayList<>(players.entrySet());
        sorted.sort(Map.Entry.comparingByValue());
        Map<UUID, String> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> entry : sorted) result.put(entry.getKey(), entry.getValue());
        return result;
    }
}
