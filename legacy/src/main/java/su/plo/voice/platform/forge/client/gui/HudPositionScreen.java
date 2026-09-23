package su.plo.voice.platform.forge.client.gui;

import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

/**
 * Upstream HudPositionScreen / ActivationIconPositionScreen / OverlayPositionScreen: one 100px button per
 * anchor at the screen edges; the position taken by the other HUD element is disabled. Escape goes back.
 */
@SideOnly(Side.CLIENT)
final class HudPositionScreen extends GuiScreen {
    private static final int BUTTON_OFFSET = 25;
    private static final int BUTTON_WIDTH = 100;

    private final GuiScreen parent;
    private final String chooseKey;
    private final Enum<?>[] positions;
    private final String[] translations;
    private final String disabled;
    private final Consumer<Integer> select;

    HudPositionScreen(GuiScreen parent, String chooseKey, Enum<?>[] positions, String[] translations,
                      String disabled, Consumer<Integer> select) {
        this.parent = parent;
        this.chooseKey = chooseKey;
        this.positions = positions;
        this.translations = translations;
        this.disabled = disabled;
        this.select = select;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        for (int i = 0; i < positions.length; i++) {
            String name = positions[i].name();
            int x = name.endsWith("LEFT") ? BUTTON_OFFSET
                    : name.endsWith("RIGHT") ? width - BUTTON_OFFSET - BUTTON_WIDTH
                    : width / 2 - BUTTON_WIDTH / 2;
            int y = name.startsWith("TOP") ? BUTTON_OFFSET : height - 20 - BUTTON_OFFSET;
            GuiButton button = new GuiButton(i, x, y, BUTTON_WIDTH, 20, I18n.format(translations[i]));
            button.enabled = !name.equals(disabled);
            buttonList.add(button);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        select.accept(button.id);
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        String text = I18n.format(chooseKey);
        fontRendererObj.drawStringWithShadow(text, width / 2 - fontRendererObj.getStringWidth(text) / 2,
                height / 2 - fontRendererObj.FONT_HEIGHT, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
