package su.plo.voice.platform.forge.client.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import org.junit.Test;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Entry;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Kind;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Layout;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Row;
import su.plo.voice.platform.forge.client.hud.HudOptions.OverlayStyle;

import static org.junit.Assert.*;

/** Group Overlay styles (name and skin, skin, name) and the background opacity. */
public class GroupHudStyleTest {
    /** Six pixels per character. */
    private static final ToIntFunction<String> WIDTH = text -> text.length() * 6;
    private static final IntFunction<String> MORE = count -> "+" + count + " more";

    private static Layout layout(List<Entry> entries, OverlayStyle style, boolean right, boolean bottom) {
        return GroupHudModel.layout(entries, right, bottom, style, WIDTH, MORE);
    }

    /** PlayerOne quiet, PlayerTwo speaking through group voice. */
    private static List<Entry> members() {
        return Arrays.asList(new Entry(UUID.randomUUID(), "PlayerOne", false),
                new Entry(UUID.randomUUID(), "PlayerTwo", true));
    }

    private static int height(int rows, int rowHeight) {
        return 2 * GroupHudModel.PADDING + rows * rowHeight + (rows - 1) * GroupHudModel.ROW_GAP;
    }

    @Test
    public void nameAndSkinIsTheUnchangedDefaultLayout() {
        Layout styled = layout(members(), OverlayStyle.NAME_SKIN, true, false);
        Layout legacy = GroupHudModel.layout(members(), true, false, WIDTH, MORE);
        assertEquals(OverlayStyle.NAME_SKIN, legacy.style);
        assertEquals(legacy.width, styled.width);
        assertEquals(legacy.height, styled.height);
        for (int i = 0; i < styled.rows.size(); i++) {
            assertEquals(legacy.rows.get(i).headX, styled.rows.get(i).headX);
            assertEquals(legacy.rows.get(i).textX, styled.rows.get(i).textX);
            assertEquals(-1, styled.rows.get(i).underlineY);
        }
        assertEquals(OverlayStyle.NAME_SKIN, GroupHudModel.layout(members(), false, false, null, WIDTH, MORE).style);
    }

    @Test
    public void skinStyleIsHeadsOnlyAndKeepsTheOutline() {
        Layout layout = layout(members(), OverlayStyle.SKIN, false, false);
        assertEquals(2 * GroupHudModel.PADDING + GroupHudModel.HEAD_SLOT, layout.width);
        assertEquals(height(2, GroupHudModel.ROW_HEIGHT), layout.height);
        for (Row row : layout.rows) {
            assertEquals(GroupHudModel.PADDING + GroupHudModel.OUTLINE, row.headX);
            assertTrue(row.headY - GroupHudModel.OUTLINE >= row.y);
            assertEquals(-1, row.underlineY);
        }
        assertFalse(layout.rows.get(0).speaking);
        assertTrue(layout.rows.get(1).speaking);
        assertTrue(PlayerHeads.Style.of(true, layout.rows.get(1).speaking).outlined);
        assertFalse(layout.style.hasName());

        Layout right = layout(members(), OverlayStyle.SKIN, true, false);
        assertEquals(right.width - GroupHudModel.PADDING - GroupHudModel.OUTLINE - GroupHudModel.HEAD_SIZE,
                right.rows.get(0).headX);
    }

    @Test
    public void nameStyleIsNamesOnlyWithAnUnderlineWhileSpeaking() {
        Layout layout = layout(members(), OverlayStyle.NAME, false, false);
        assertEquals(2 * GroupHudModel.PADDING + 9 * 6, layout.width);
        assertEquals(height(2, GroupHudModel.NAME_ROW_HEIGHT), layout.height);
        Row quiet = layout.rows.get(0);
        Row speaking = layout.rows.get(1);
        assertEquals(-1, quiet.headX);
        assertEquals(-1, quiet.headY);
        assertEquals(GroupHudModel.PADDING, quiet.textX);
        assertEquals(-1, quiet.underlineY);
        // a one pixel line under the text, inside the row, never touching the next row
        assertEquals(speaking.textY + GroupHudModel.FONT_HEIGHT + 1, speaking.underlineY);
        assertTrue(speaking.underlineY + 1 <= speaking.y + GroupHudModel.NAME_ROW_HEIGHT);
        assertEquals(GroupHudModel.NAME_ROW_HEIGHT + GroupHudModel.ROW_GAP, speaking.y - quiet.y);
        assertFalse(layout.style.hasSkin());
    }

    @Test
    public void nameStyleFollowsTheAnchorSides() {
        Layout right = layout(members(), OverlayStyle.NAME, true, true);
        Row first = right.rows.get(right.rows.size() - 1);
        assertEquals("PlayerOne", first.text);
        assertEquals(right.width - GroupHudModel.PADDING, first.textX + WIDTH.applyAsInt(first.text));
        assertEquals("PlayerTwo", right.rows.get(0).text);
        assertTrue(right.rows.get(0).underlineY > 0);
    }

    @Test
    public void theMoreRowFitsEveryStyle() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(new Entry(UUID.randomUUID(), "P" + i, false));
        for (OverlayStyle style : OverlayStyle.values()) {
            Layout layout = layout(many, style, false, false);
            Row last = layout.rows.get(layout.rows.size() - 1);
            assertEquals(Kind.MORE, last.kind);
            assertTrue(style.name(), layout.width >= 2 * GroupHudModel.PADDING + WIDTH.applyAsInt(last.text));
            assertEquals(-1, last.headX);
            assertEquals(-1, last.underlineY);
        }
    }

    @Test
    public void opacityOnlyChangesThePanelAlpha() {
        assertEquals(0, GroupHudModel.backgroundColor(0));
        assertEquals(0x40000000, GroupHudModel.backgroundColor(GroupHudModel.DEFAULT_OPACITY));
        assertEquals(0xFF000000, GroupHudModel.backgroundColor(100));
        assertEquals(0x80000000, GroupHudModel.backgroundColor(50));
        assertEquals(0, GroupHudModel.backgroundColor(-10));
        assertEquals(0xFF000000, GroupHudModel.backgroundColor(400));
        // The speaking marks stay fully opaque white, whatever the panel opacity.
        assertEquals(0xFFFFFFFF, PlayerHeads.OUTLINE_COLOR);
    }

    @Test
    public void anEmptyGroupStaysEmptyInEveryStyle() {
        for (OverlayStyle style : OverlayStyle.values()) {
            Layout layout = layout(new ArrayList<Entry>(), style, false, false);
            assertEquals(0, layout.width);
            assertEquals(0, layout.height);
            assertEquals(style, layout.style);
        }
    }
}
