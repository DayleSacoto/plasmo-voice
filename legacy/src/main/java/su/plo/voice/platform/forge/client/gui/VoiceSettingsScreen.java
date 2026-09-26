package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.VoiceControls;

/** Legacy port of the upstream settings screen and VoiceSettingsNavigation: header, tabs and the voice toggles. */
@SideOnly(Side.CLIENT)
public final class VoiceSettingsScreen extends GuiScreen {
    private static final ResourceLocation MICROPHONE_ICON = icon("microphone_menu");
    private static final ResourceLocation MICROPHONE_DISABLED_ICON = icon("microphone_menu_disabled");
    private static final ResourceLocation SPEAKER_ICON = icon("speaker_menu");
    private static final ResourceLocation SPEAKER_DISABLED_ICON = icon("speaker_menu_disabled");
    private static final int HEADER_HEIGHT = 36;

    private final ClientState state;
    private final List<TabButton> tabButtons = new ArrayList<>();
    private final List<SettingsTab> tabs = new ArrayList<>();
    private int active;
    private int navigationHeight = HEADER_HEIGHT;
    private IconButton microphoneButton;
    private IconButton voiceButton;

    public VoiceSettingsScreen(ClientState state) {
        this.state = state;
    }

    @Override
    public void initGui() {
        state.getMicrophoneTest().setListening(true);
        buttonList.clear();
        microphoneButton = new IconButton(0, width - 52, 8,
                () -> state.isMicrophoneMuted() ? MICROPHONE_DISABLED_ICON : MICROPHONE_ICON);
        voiceButton = new IconButton(1, width - 28, 8,
                () -> state.isVoiceDisabled() ? SPEAKER_DISABLED_ICON : SPEAKER_ICON);
        buttonList.add(microphoneButton);
        buttonList.add(voiceButton);

        tabs.clear();
        tabButtons.clear();
        addTab("gui.plasmovoice.devices", "devices", new DevicesTab(this, state));
        addTab("gui.plasmovoice.volume", "volume", new VolumeTab(this, state));
        addTab("gui.plasmovoice.activation", "activation", new ActivationTab(this, state));
        addTab("gui.plasmovoice.overlay", "overlay", new OverlayTab(this, state));
        addTab("gui.plasmovoice.advanced", "advanced", new AdvancedTab(this, state));
        addTab("gui.plasmovoice.hotkeys", "hotkeys", new HotKeysTab(this, state));
        layoutTabs();
        activeTab().init(navigationHeight, height);
    }

    private void addTab(String nameKey, String icon, SettingsTab tab) {
        tabButtons.add(new TabButton(fontRendererObj, I18n.format(nameKey), icon));
        tabs.add(tab);
    }

    private SettingsTab activeTab() {
        return tabs.get(active);
    }

    private void openTab(int index) {
        activeTab().removed();
        active = index;
        activeTab().init(navigationHeight, height);
    }

    /** Upstream VoiceSettingsNavigation: tabs centered in the header, or wrapped below the title when narrow. */
    private void layoutTabs() {
        int buttonsWidth = -4;
        for (TabButton button : tabButtons) buttonsWidth += button.width + 4;
        int titleWidth = 14 + fontRendererObj.getStringWidth(title()) + 4;
        int centeredX = width / 2 - buttonsWidth / 2;
        boolean minimized = centeredX < titleWidth || titleWidth + buttonsWidth + 14 + 48 > width;

        int x = minimized ? 14 : centeredX;
        int y = minimized ? HEADER_HEIGHT : 8;
        int lines = 1;
        for (TabButton button : tabButtons) {
            if (minimized && x + button.width > width - 8) {
                x = 14;
                y += 26;
                lines++;
            }
            button.x = x;
            button.y = y;
            x += button.width + 4;
        }
        navigationHeight = minimized ? HEADER_HEIGHT + lines * 28 : HEADER_HEIGHT;
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
        if (!state.isVoiceAvailable()) {
            mc.displayGuiScreen(new VoiceNotAvailableScreen(state));
            return;
        }
        activeTab().tick();
    }

    @Override
    public void onGuiClosed() {
        state.getMicrophoneTest().stop();
        state.getMicrophoneTest().setListening(false);
        activeTab().removed();
        state.save();
    }

    /** The hotkey tab records combinations, so key releases matter too. */
    @Override
    public void handleKeyboardInput() {
        if (!Keyboard.getEventKeyState()) activeTab().keyReleased(Keyboard.getEventKey());
        super.handleKeyboardInput();
    }

    public boolean isCapturingHotkey() {
        return !tabs.isEmpty() && activeTab().getRecording() != null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (activeTab().keyTyped(typedChar, keyCode)) return;
        if (VoiceControls.isSettingsKey(keyCode)) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (activeTab().mouseClicked(mouseX, mouseY, button)) return;
        for (int i = 0; i < tabButtons.size(); i++) {
            if (tabButtons.get(i).mouseClicked(mouseX, mouseY, button)) {
                openTab(i);
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceClick) {
        activeTab().mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button >= 0) activeTab().mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        activeTab().scroll(wheel > 0 ? -1 : 1, mouseX, mouseY);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        boolean overHeader = mouseY < navigationHeight;
        activeTab().render(mc, overHeader ? -1 : mouseX, overHeader ? -1 : mouseY);
        drawHeader();
        fontRendererObj.drawStringWithShadow(title(), 14, 15, 0xFFFFFF);
        for (int i = 0; i < tabButtons.size(); i++) {
            TabButton button = tabButtons.get(i);
            button.active = i != active;
            button.render(mc, mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (microphoneButton.isHovered()) {
            drawToggleTooltip("gui.plasmovoice.toggle.microphone", !state.isMicrophoneMuted(), mouseX, mouseY);
        } else if (voiceButton.isHovered()) {
            drawToggleTooltip("gui.plasmovoice.toggle.voice", !state.isVoiceDisabled(), mouseX, mouseY);
        } else if (activeTab().getTooltip() != null) {
            func_146283_a(activeTab().getTooltip(), mouseX, mouseY);
        }
    }

    private String title() {
        return I18n.format("gui.plasmovoice.title", "Plasmo Voice", PlasmoVoiceMod.VERSION);
    }

    /** Same darkened options background the vanilla lists use for their header. */
    private void drawHeader() {
        Tessellator tessellator = Tessellator.instance;
        mc.getTextureManager().bindTexture(Gui.optionsBackground);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(0x404040, 255);
        tessellator.addVertexWithUV(0, navigationHeight, 0, 0, navigationHeight / 32.0);
        tessellator.addVertexWithUV(width, navigationHeight, 0, width / 32.0, navigationHeight / 32.0);
        tessellator.addVertexWithUV(width, 0, 0, width / 32.0, 0);
        tessellator.addVertexWithUV(0, 0, 0, 0, 0);
        tessellator.draw();
        drawGradientRect(0, navigationHeight, width, navigationHeight + 4, 0xFF000000, 0x00000000);
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
