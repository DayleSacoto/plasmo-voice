package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.VoiceHotkeys;

/**
 * Upstream HotKeyWidget: click to record, hold up to three keys or mouse buttons and release one to save.
 * Escape saves what is held, or unbinds the hotkey when nothing is held.
 */
@SideOnly(Side.CLIENT)
final class HotKeyWidget extends Widget {
    private final SettingsTab tab;
    private final VoiceHotkeys hotkeys;
    private final String name;
    private final List<Integer> pressed = new ArrayList<>();

    HotKeyWidget(SettingsTab tab, VoiceHotkeys hotkeys, String name) {
        super(SettingsTab.ELEMENT_WIDTH, 20);
        this.tab = tab;
        this.hotkeys = hotkeys;
        this.name = name;
    }

    private boolean isRecording() {
        return tab.getRecording() == this;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        FontRenderer font = mc.fontRenderer;
        drawButtonBackground(mc, x, y, width, buttonState(mouseX, mouseY));
        String text;
        int color;
        if (isRecording()) {
            text = "> " + format(pressed.isEmpty() ? hotkeys.getKeys(name) : pressed) + " <";
            color = 0xFFFF55;
        } else {
            text = fit(font, format(hotkeys.getKeys(name)), width - 16);
            color = textColor(mouseX, mouseY);
        }
        drawCenteredString(font, text, x + width / 2, y + (height - 8) / 2, color);
    }

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (isRecording()) {
            if (pressed.size() < VoiceHotkeys.MAX_KEYS && !pressed.contains(button - 100)) pressed.add(button - 100);
            return true;
        }
        if (!active || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        playClick(Minecraft.getMinecraft());
        pressed.clear();
        tab.setRecording(this);
        return true;
    }

    @Override
    void mouseReleased(int mouseX, int mouseY, int button) {
        // The left click that started recording is not part of the combination.
        if (isRecording() && !(button == 0 && pressed.isEmpty()) && pressed.contains(button - 100)) save();
    }

    @Override
    boolean keyTyped(char typedChar, int keyCode) {
        if (!isRecording()) return false;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (pressed.isEmpty()) hotkeys.setKeys(name, pressed);
            save();
            return true;
        }
        if (keyCode != Keyboard.KEY_NONE && pressed.size() < VoiceHotkeys.MAX_KEYS && !pressed.contains(keyCode)) {
            pressed.add(keyCode);
        }
        return true;
    }

    void keyReleased(int keyCode) {
        if (isRecording() && pressed.contains(keyCode)) save();
    }

    @Override
    String tooltip(int mouseX, int mouseY) {
        String text = format(hotkeys.getKeys(name));
        return !isRecording() && Minecraft.getMinecraft().fontRenderer.getStringWidth(text) > width - 16 ? text : null;
    }

    private void save() {
        if (!pressed.isEmpty()) hotkeys.setKeys(name, pressed);
        pressed.clear();
        tab.setRecording(null);
    }

    /** Vanilla key names ("LMENU", "Button 2") joined like upstream. */
    static String format(List<Integer> keys) {
        if (keys.isEmpty()) return I18n.format("gui.none");
        StringBuilder text = new StringBuilder();
        for (int key : keys) {
            if (text.length() > 0) text.append(" + ");
            text.append(GameSettings.getKeyDisplayString(key));
        }
        return text.toString();
    }
}
