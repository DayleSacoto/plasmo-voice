package su.plo.voice.platform.forge.client.gui;

import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

/** Upstream TextFieldWidget: the vanilla text box with a suggestion shown while it is empty and not being edited. */
@SideOnly(Side.CLIENT)
final class TextFieldWidget extends Widget {
    private static final int SUGGESTION_COLOR = 0xAAAAAA;

    private final GuiTextField field;
    private final String suggestion;
    private final Consumer<String> onChange;

    TextFieldWidget(FontRenderer font, int width, String suggestion, Consumer<String> onChange) {
        super(width, 20);
        this.field = new GuiTextField(font, 0, 0, width, 20);
        this.suggestion = suggestion;
        this.onChange = onChange;
    }

    void setFocused(boolean focused) {
        field.setFocused(focused);
    }

    void tick() {
        field.updateCursorCounter();
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        field.xPosition = x;
        field.yPosition = y;
        field.width = width;
        field.drawTextBox();
        if (showsSuggestion(field.isFocused(), field.getText())) {
            mc.fontRenderer.drawStringWithShadow(mc.fontRenderer.trimStringToWidth(suggestion, width - 8), x + 4,
                    y + (height - 8) / 2, SUGGESTION_COLOR);
        }
    }

    /** Like GTNH NEI's search field: the hint is only drawn, never the value, and only while the box is not focused. */
    static boolean showsSuggestion(boolean focused, String text) {
        return !focused && text.isEmpty();
    }

    /** Also runs for clicks elsewhere, which take the focus away like vanilla. */
    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        field.mouseClicked(mouseX, mouseY, button);
        return isMouseOver(mouseX, mouseY);
    }

    @Override
    boolean keyTyped(char typedChar, int keyCode) {
        if (!field.isFocused()) return false;
        String before = field.getText();
        boolean used = field.textboxKeyTyped(typedChar, keyCode);
        if (!before.equals(field.getText())) onChange.accept(field.getText());
        return used;
    }
}
