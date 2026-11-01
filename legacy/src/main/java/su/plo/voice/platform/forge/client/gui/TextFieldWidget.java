package su.plo.voice.platform.forge.client.gui;

import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

/**
 * Upstream TextFieldWidget: the vanilla text box with a suggestion shown while it is empty and not being edited.
 * A masked field (passwords) draws one asterisk per character, refuses copy and cut and never exposes its text in
 * a tooltip; {@link #getText()} is for the code that sends it.
 */
@SideOnly(Side.CLIENT)
final class TextFieldWidget extends Widget {
    private static final int SUGGESTION_COLOR = 0xAAAAAA;

    private final GuiTextField field;
    private final String suggestion;
    private final Consumer<String> onChange;
    private boolean masked;
    private Runnable onEnter;

    TextFieldWidget(FontRenderer font, int width, String suggestion, Consumer<String> onChange) {
        super(width, 20);
        this.field = new GuiTextField(font, 0, 0, width, 20);
        this.suggestion = suggestion;
        this.onChange = onChange;
    }

    void setFocused(boolean focused) {
        field.setFocused(focused);
    }

    boolean isFocused() {
        return field.isFocused();
    }

    String getText() {
        return field.getText();
    }

    /** Replaces the text without calling the change listener. */
    void setText(String text) {
        field.setText(text == null ? "" : text);
    }

    void clear() {
        field.setText("");
    }

    void setMaxLength(int length) {
        field.setMaxStringLength(Math.max(1, length));
    }

    /** Draws asterisks instead of the characters and blocks copy and cut. */
    void setMasked(boolean masked) {
        this.masked = masked;
    }

    /** Runs when Enter is pressed in the focused field. */
    void setOnEnter(Runnable onEnter) {
        this.onEnter = onEnter;
    }

    void tick() {
        field.updateCursorCounter();
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        if (!active && field.isFocused()) field.setFocused(false);
        field.setEnabled(active);
        field.xPosition = x;
        field.yPosition = y;
        field.width = width;
        if (masked) {
            renderMasked(mc.fontRenderer);
        } else {
            field.drawTextBox();
        }
        if (showsSuggestion(field.isFocused(), field.getText())) {
            mc.fontRenderer.drawStringWithShadow(mc.fontRenderer.trimStringToWidth(suggestion, width - 8), x + 4,
                    y + (height - 8) / 2, SUGGESTION_COLOR);
        }
    }

    /** The vanilla box frame with asterisks; the real text is never handed to the font renderer. */
    private void renderMasked(FontRenderer font) {
        boolean focused = field.isFocused();
        drawRect(x - 1, y - 1, x + width + 1, y + height + 1, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
        drawRect(x, y, x + width, y + height, 0xFF000000);
        String text = field.getText();
        String stars = mask(text.length());
        String shown = font.trimStringToWidth(stars, width - 8, true);
        int color = active ? 14737632 : 7368816;
        int textY = y + (height - 8) / 2;
        font.drawStringWithShadow(shown, x + 4, textY, color);
        int shownWidth = font.getStringWidth(shown);
        if (focused && active && !text.isEmpty() && text.equals(field.getSelectedText())) {
            drawRect(x + 4, textY - 1, x + 4 + shownWidth, textY + 9, 0xFF0000FF);
            font.drawStringWithShadow(shown, x + 4, textY, 0xFFFFFF);
        }
        if (focused && active && (Minecraft.getSystemTime() / 500) % 2 == 0) {
            int cursor = Math.min(field.getCursorPosition(), text.length());
            int cursorX = x + 4 + Math.min(shownWidth, font.getStringWidth(mask(cursor)));
            drawRect(cursorX, textY - 1, cursorX + 1, textY + 9, 0xFFD0D0D0);
        }
    }

    /** One asterisk per character. */
    static String mask(int length) {
        StringBuilder stars = new StringBuilder(length);
        for (int i = 0; i < length; i++) stars.append('*');
        return stars.toString();
    }

    /** Like GTNH NEI's search field: the hint is only drawn, never the value, and only while the box is not focused. */
    static boolean showsSuggestion(boolean focused, String text) {
        return !focused && text.isEmpty();
    }

    /** A masked field must not put its text on the clipboard. */
    static boolean blocksClipboardWrite(boolean masked, boolean copy, boolean cut) {
        return masked && (copy || cut);
    }

    /**
     * Whether a key copies or cuts: vanilla GuiTextField reacts to the control characters (Ctrl+C = 3, Ctrl+X = 24),
     * which some layouts send with other key codes, so both the character and the Ctrl+C / Ctrl+X keys count.
     */
    static boolean isCopy(char typedChar, int keyCode, boolean ctrl) {
        return typedChar == 3 || ctrl && keyCode == Keyboard.KEY_C;
    }

    static boolean isCut(char typedChar, int keyCode, boolean ctrl) {
        return typedChar == 24 || ctrl && keyCode == Keyboard.KEY_X;
    }

    /** Also runs for clicks elsewhere, which take the focus away like vanilla. */
    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active) {
            field.setFocused(false);
            return isMouseOver(mouseX, mouseY);
        }
        field.mouseClicked(mouseX, mouseY, button);
        return isMouseOver(mouseX, mouseY);
    }

    @Override
    boolean keyTyped(char typedChar, int keyCode) {
        if (!active || !field.isFocused()) return false;
        if (onEnter != null && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
            onEnter.run();
            return true;
        }
        boolean ctrl = GuiScreen.isCtrlKeyDown();
        if (blocksClipboardWrite(masked, isCopy(typedChar, keyCode, ctrl), isCut(typedChar, keyCode, ctrl))) {
            return true;
        }
        String before = field.getText();
        boolean used = field.textboxKeyTyped(typedChar, keyCode);
        if (!before.equals(field.getText())) onChange.accept(field.getText());
        return used;
    }
}
