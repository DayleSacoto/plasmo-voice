package su.plo.voice.platform.forge.client.gui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.hud.GroupHud;
import su.plo.voice.platform.forge.client.hud.GroupHudModel;
import su.plo.voice.platform.forge.client.hud.GroupHudPosition;
import su.plo.voice.platform.forge.client.lang.VoiceLang;

/**
 * Drag and drop editor of the Group HUD position. Always shows the same three preview members drawn exactly like the
 * HUD, in the selected Group Overlay style and background opacity, one of them speaking (outline or underline). The position is edited in the screen and committed by Done;
 * Escape leaves the stored position as it was when the screen opened. Everything is in scaled GUI pixels, like the HUD.
 */
@SideOnly(Side.CLIENT)
public final class GroupHudEditScreen extends GuiScreen {
    private static final int RESET = 0;
    private static final int DONE = 1;
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_BOTTOM = 28;
    private static final List<GroupHudModel.Entry> FAKE_MEMBERS = fakeMembers();

    private final GuiScreen parent;
    private final ClientState state;
    /** The stored position when the screen opened; Escape keeps it. */
    private final GroupHudPosition.Placement original;
    private GroupHudPosition.Placement placement;
    private boolean dragging;
    private int grabX;
    private int grabY;
    /** Rows keep the order of the anchor the drag started from, so they do not flip under the cursor. */
    private GroupHudPosition.Anchor dragLayoutAnchor;

    public GroupHudEditScreen(GuiScreen parent, ClientState state) {
        this.parent = parent;
        this.state = state;
        this.original = state.getGroupHudPlacement();
        this.placement = original;
    }

    @Override
    public void initGui() {
        dragging = false;
        buttonList.clear();
        int left = width / 2 - BUTTON_WIDTH - BUTTON_GAP / 2;
        int y = height - BUTTON_BOTTOM;
        buttonList.add(new GuiButton(RESET, left, y, BUTTON_WIDTH, 20, VoiceLang.format("gui.plasmovoice.overlay.group_reset")));
        buttonList.add(new GuiButton(DONE, left + BUTTON_WIDTH + BUTTON_GAP, y, BUTTON_WIDTH, 20,
                VoiceLang.format("gui.plasmovoice.overlay.group_done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == RESET) {
            placement = GroupHudPosition.DEFAULT;
            dragging = false;
        } else if (button.id == DONE) {
            GroupHudModel.Layout layout = layout(placement.anchor);
            state.setGroupHudPlacement(GroupHudPosition.normalize(placement, layout.width, layout.height, width, height));
            state.save();
            mc.displayGuiScreen(parent);
        }
    }

    /** Escape cancels: the state was never touched, so the position from when the screen opened stays. */
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        for (Object object : buttonList) {
            if (button == 0 && ((GuiButton) object).mousePressed(mc, mouseX, mouseY)) {
                super.mouseClicked(mouseX, mouseY, button);
                return;
            }
        }
        if (button != 0) return;
        GroupHudModel.Layout layout = layout(placement.anchor);
        GroupHudPosition.Point origin = origin(layout);
        if (mouseX >= origin.x && mouseX < origin.x + layout.width && mouseY >= origin.y && mouseY < origin.y + layout.height) {
            dragging = true;
            grabX = mouseX - origin.x;
            grabY = mouseY - origin.y;
            dragLayoutAnchor = placement.anchor;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int lastButton, long timeSinceLastClick) {
        if (dragging) drag(mouseX, mouseY);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        if (dragging && which == 0) {
            drag(mouseX, mouseY);
            dragging = false;
        }
        super.mouseMovedOrUp(mouseX, mouseY, which);
    }

    private void drag(int mouseX, int mouseY) {
        GroupHudModel.Layout layout = layout(dragLayoutAnchor);
        placement = GroupHudPosition.fromDrag(mouseX - grabX, mouseY - grabY, layout.width, layout.height, width, height);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, VoiceLang.format("gui.plasmovoice.overlay.group_edit.title"), width / 2, 12, 0xFFFFFF);
        drawCenteredString(fontRendererObj, VoiceLang.format("gui.plasmovoice.overlay.group_edit.hint"), width / 2,
                height - BUTTON_BOTTOM - 14, 0xA0A0A0);

        GroupHudModel.Layout layout = layout(dragging ? dragLayoutAnchor : placement.anchor);
        GroupHudPosition.Point origin = origin(layout);
        GroupHud.draw(mc, layout, origin.x, origin.y, state.getGroupHudBackgroundOpacity());
        boolean hovered = mouseX >= origin.x && mouseX < origin.x + layout.width
                && mouseY >= origin.y && mouseY < origin.y + layout.height;
        if (dragging || hovered) drawOutline(origin.x, origin.y, layout.width, layout.height, dragging ? 0xFFFFFFFF : 0x80FFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private GroupHudPosition.Point origin(GroupHudModel.Layout layout) {
        return GroupHudPosition.origin(placement, layout.width, layout.height, width, height);
    }

    /** The preview members laid out like the HUD for an anchor, in the selected Group Overlay style. */
    private GroupHudModel.Layout layout(GroupHudPosition.Anchor anchor) {
        return GroupHud.layout(fontRendererObj, FAKE_MEMBERS, anchor, state.getGroupHudStyle());
    }

    private void drawOutline(int x, int y, int w, int h, int color) {
        drawRect(x - 1, y - 1, x + w + 1, y, color);
        drawRect(x - 1, y + h, x + w + 1, y + h + 1, color);
        drawRect(x - 1, y, x, y + h, color);
        drawRect(x + w, y, x + w + 1, y + h, color);
    }

    private static List<GroupHudModel.Entry> fakeMembers() {
        List<GroupHudModel.Entry> members = new ArrayList<>();
        members.add(fake("PlayerOne", false));
        members.add(fake("PlayerTwo", true));
        members.add(fake("PlayerThree", false));
        return members;
    }

    private static GroupHudModel.Entry fake(String name, boolean speaking) {
        UUID id = UUID.nameUUIDFromBytes(("preview:" + name).getBytes(StandardCharsets.UTF_8));
        // No skin name: the preview always shows the default head and never downloads skins of real accounts.
        return new GroupHudModel.Entry(id, name, speaking, null);
    }
}
