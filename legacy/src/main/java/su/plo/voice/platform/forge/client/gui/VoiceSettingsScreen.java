package su.plo.voice.platform.forge.client.gui;

import java.net.InetSocketAddress;
import java.util.Arrays;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.VoiceControls;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;

/** Legacy port of the upstream settings frame: header, title and the microphone/voice toggles. */
@SideOnly(Side.CLIENT)
public final class VoiceSettingsScreen extends GuiScreen {
    private static final ResourceLocation MICROPHONE_ICON = icon("microphone_menu");
    private static final ResourceLocation MICROPHONE_DISABLED_ICON = icon("microphone_menu_disabled");
    private static final ResourceLocation SPEAKER_ICON = icon("speaker_menu");
    private static final ResourceLocation SPEAKER_DISABLED_ICON = icon("speaker_menu_disabled");
    private static final int HEADER_HEIGHT = 36;
    private static final int CONTAINER_WIDTH = 303;
    private static final int ROW_HEIGHT = 24;

    private final ClientState state;
    private IconButton microphoneButton;
    private IconButton voiceButton;

    public VoiceSettingsScreen(ClientState state) {
        this.state = state;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        microphoneButton = new IconButton(0, width - 52, 8,
                () -> state.isMicrophoneMuted() ? MICROPHONE_DISABLED_ICON : MICROPHONE_ICON);
        voiceButton = new IconButton(1, width - 28, 8,
                () -> state.isVoiceDisabled() ? SPEAKER_DISABLED_ICON : SPEAKER_ICON);
        buttonList.add(microphoneButton);
        buttonList.add(voiceButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == microphoneButton) {
            state.setMicrophoneMuted(!state.isMicrophoneMuted());
        } else if (button == voiceButton) {
            state.setVoiceDisabled(!state.isVoiceDisabled());
        }
    }

    @Override
    public void updateScreen() {
        if (!state.isVoiceAvailable()) mc.displayGuiScreen(new VoiceNotAvailableScreen(state));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (VoiceControls.isSettingsKey(keyCode)) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawConnection(width / 2 - CONTAINER_WIDTH / 2, HEADER_HEIGHT + 4);
        drawHeader();
        fontRendererObj.drawStringWithShadow(
                I18n.format("gui.plasmovoice.title", "Plasmo Voice", PlasmoVoiceMod.VERSION), 14, 15, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (microphoneButton.isHovered()) {
            drawToggleTooltip("gui.plasmovoice.toggle.microphone", !state.isMicrophoneMuted(), mouseX, mouseY);
        } else if (voiceButton.isHovered()) {
            drawToggleTooltip("gui.plasmovoice.toggle.voice", !state.isVoiceDisabled(), mouseX, mouseY);
        }
    }

    private void drawConnection(int left, int y) {
        String category = I18n.format("gui.plasmovoice.connection");
        fontRendererObj.drawStringWithShadow(category,
                width / 2 - fontRendererObj.getStringWidth(category) / 2, rowTextY(y), 0xFFFFFF);
        y += ROW_HEIGHT;

        ClientConnectionState connection = state.getConnection();
        InetSocketAddress endpoint = connection.getUdp() == null ? null : connection.getUdp().getRemoteAddress();
        drawRow(left, y, "gui.plasmovoice.connection.connected", yesNo(connection.isConnected()));
        drawRow(left, y += ROW_HEIGHT, "gui.plasmovoice.connection.udp_endpoint", endpoint == null
                ? I18n.format("gui.plasmovoice.connection.none")
                : endpoint.getHostString() + ":" + endpoint.getPort());
        drawRow(left, y += ROW_HEIGHT, "gui.plasmovoice.connection.udp_confirmed", yesNo(connection.isUdpConfirmed()));
        drawRow(left, y += ROW_HEIGHT, "gui.plasmovoice.connection.configured", yesNo(connection.isConfigured()));
        drawRow(left, y + ROW_HEIGHT, "gui.plasmovoice.connection.players", String.valueOf(connection.getPlayers().size()));
    }

    private void drawRow(int left, int y, String label, String value) {
        fontRendererObj.drawStringWithShadow(I18n.format(label), left, rowTextY(y), 0xFFFFFF);
        fontRendererObj.drawStringWithShadow(value,
                left + CONTAINER_WIDTH - fontRendererObj.getStringWidth(value), rowTextY(y), 0xFFFFFF);
    }

    private int rowTextY(int y) {
        return y + ROW_HEIGHT / 2 - fontRendererObj.FONT_HEIGHT / 2;
    }

    private static String yesNo(boolean value) {
        return value
                ? EnumChatFormatting.GREEN + I18n.format("gui.plasmovoice.connection.yes")
                : EnumChatFormatting.RED + I18n.format("gui.plasmovoice.connection.no");
    }

    /** Same darkened options background the vanilla lists use for their header. */
    private void drawHeader() {
        Tessellator tessellator = Tessellator.instance;
        mc.getTextureManager().bindTexture(Gui.optionsBackground);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(0x404040, 255);
        tessellator.addVertexWithUV(0, HEADER_HEIGHT, 0, 0, HEADER_HEIGHT / 32.0);
        tessellator.addVertexWithUV(width, HEADER_HEIGHT, 0, width / 32.0, HEADER_HEIGHT / 32.0);
        tessellator.addVertexWithUV(width, 0, 0, width / 32.0, 0);
        tessellator.addVertexWithUV(0, 0, 0, 0, 0);
        tessellator.draw();
        drawGradientRect(0, HEADER_HEIGHT, width, HEADER_HEIGHT + 4, 0xFF000000, 0x00000000);
    }

    private void drawToggleTooltip(String key, boolean enabled, int mouseX, int mouseY) {
        String stateText = enabled
                ? EnumChatFormatting.GREEN + I18n.format("gui.plasmovoice.toggle.enabled")
                : EnumChatFormatting.RED + I18n.format("gui.plasmovoice.toggle.disabled");
        String currently = EnumChatFormatting.GRAY + I18n.format("gui.plasmovoice.toggle.currently", stateText);
        func_146283_a(Arrays.asList(lines(I18n.format(key, currently))), mouseX, mouseY);
    }

    /** Upstream translations use "\n"; .lang files keep it as two literal characters. */
    static String[] lines(String text) {
        return text.split("\\\\n|\n");
    }

    private static ResourceLocation icon(String name) {
        return new ResourceLocation("plasmovoice", "textures/icons/" + name + ".png");
    }
}
