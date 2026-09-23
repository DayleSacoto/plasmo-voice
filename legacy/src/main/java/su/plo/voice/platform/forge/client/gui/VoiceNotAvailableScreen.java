package su.plo.voice.platform.forge.client.gui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.VoiceControls;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;

/** Upstream VoiceNotAvailableScreen; switches to settings as soon as voice becomes available. */
@SideOnly(Side.CLIENT)
public final class VoiceNotAvailableScreen extends GuiScreen {
    private static final String WIKI_LINK = "https://plasmovoice.com/docs/server/installing";
    private static final int WIDTH = 248;
    private static final int HEIGHT = 50;

    private final ClientState state;
    private GuiButton closeButton;
    private int y;

    public VoiceNotAvailableScreen(ClientState state) {
        this.state = state;
    }

    @Override
    public void initGui() {
        int x = (width - WIDTH) / 2;
        y = (height - HEIGHT) / 2;
        buttonList.clear();
        closeButton = new GuiButton(0, x + 10, 0, WIDTH - 20, 20, I18n.format("message.plasmovoice.close"));
        buttonList.add(closeButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == closeButton) mc.displayGuiScreen(null);
    }

    @Override
    public void updateScreen() {
        if (state.isVoiceAvailable()) mc.displayGuiScreen(new VoiceSettingsScreen(state));
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
        String[] lines = VoiceSettingsScreen.lines(message());
        for (int i = 0; i < lines.length; i++) {
            drawCenteredString(fontRendererObj, lines[i], width / 2, y + i * fontRendererObj.FONT_HEIGHT, 0xFFFFFF);
        }
        closeButton.yPosition = y + fontRendererObj.FONT_HEIGHT * lines.length + 20;
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String message() {
        ClientConnectionState connection = state.getConnection();
        if (connection == null || !connection.isConnected()) {
            return I18n.format("gui.plasmovoice.not_available");
        }
        if (connection.getUdp() != null && connection.getUdp().isClosed()) {
            return I18n.format("gui.plasmovoice.cannot_connect_to_udp", EnumChatFormatting.YELLOW + WIKI_LINK);
        }
        return I18n.format("gui.plasmovoice.connecting");
    }
}
