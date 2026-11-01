package su.plo.voice.platform.forge.client.hud;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import org.junit.Test;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Entry;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Kind;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Layout;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Row;
import su.plo.voice.platform.forge.network.groups.GroupsState;

import static org.junit.Assert.*;

public class GroupHudModelTest {
    /** Six pixels per character. */
    private static final ToIntFunction<String> WIDTH = text -> text.length() * 6;
    private static final IntFunction<String> MORE = count -> "+" + count + " more";
    private static final int ROW_STEP = GroupHudModel.ROW_HEIGHT + GroupHudModel.ROW_GAP;

    private static GroupsState.Member member(UUID id, String name, boolean online) {
        return new GroupsState.Member(id, name, online);
    }

    private static Layout layout(List<Entry> entries, boolean right, boolean bottom) {
        return GroupHudModel.layout(entries, right, bottom, WIDTH, MORE);
    }

    private static List<Entry> entries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(new Entry(UUID.randomUUID(), "Player" + i, false));
        return entries;
    }

    private static int panelHeight(int rows) {
        return 2 * GroupHudModel.PADDING + rows * GroupHudModel.ROW_HEIGHT + (rows - 1) * GroupHudModel.ROW_GAP;
    }

    @Test
    public void onlyOnlineMembersAreShown() {
        UUID self = UUID.randomUUID();
        List<GroupsState.Member> members = Arrays.asList(member(self, "Dayle", true),
                member(UUID.randomUUID(), "Gone", false), member(UUID.randomUUID(), "Player2", true));
        List<Entry> entries = GroupHudModel.entries(members, Collections.<UUID>emptySet(), self, false);
        assertEquals(2, entries.size());
        assertEquals("Dayle", entries.get(0).name);
        assertEquals("Player2", entries.get(1).name);
    }

    @Test
    public void groupSpeakingComesFromTheGroupLineAndTheLocalGroupActivation() {
        UUID self = UUID.randomUUID();
        UUID talker = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        List<GroupsState.Member> members = Arrays.asList(member(self, "Dayle", true), member(talker, "Player2", true),
                member(quiet, "Player3", true));
        Set<UUID> groupSpeaking = new HashSet<>(Collections.singleton(talker));

        List<Entry> idle = GroupHudModel.entries(members, groupSpeaking, self, false);
        assertFalse(idle.get(0).speaking);
        assertTrue(idle.get(1).speaking);
        assertFalse(idle.get(2).speaking);

        // The local player is outlined while transmitting to the group, exactly like a remote member.
        assertTrue(GroupHudModel.entries(members, groupSpeaking, self, true).get(0).speaking);

        // Speaking ended: the group line is silent, nobody is outlined.
        for (Entry entry : GroupHudModel.entries(members, Collections.<UUID>emptySet(), self, false)) {
            assertFalse(entry.speaking);
        }

        // The local player follows only its own group activation, not its audio echoed on the line.
        groupSpeaking.add(self);
        assertFalse(GroupHudModel.entries(members, groupSpeaking, self, false).get(0).speaking);
    }

    @Test
    public void speakingMembersCarryTheOutlineFlagIntoTheirRow() {
        List<Entry> members = Arrays.asList(new Entry(UUID.randomUUID(), "PlayerOne", false),
                new Entry(UUID.randomUUID(), "PlayerTwo", true));
        Layout layout = layout(members, false, false);
        assertFalse(layout.rows.get(0).speaking);
        assertTrue(layout.rows.get(1).speaking);
        assertTrue(PlayerHeads.Style.of(true, layout.rows.get(1).speaking).outlined);
        assertFalse(PlayerHeads.Style.of(true, layout.rows.get(0).speaking).outlined);
    }

    @Test
    public void thereIsNoTitleRowOnlyMembers() {
        Layout layout = layout(entries(3), true, true);
        assertEquals(3, layout.rows.size());
        for (Row row : layout.rows) assertEquals(Kind.MEMBER, row.kind);
        assertEquals(EnumSetOf.kinds(), "MEMBER,MORE");
        // The first row starts right after the panel padding: no title spacing above it.
        assertEquals(GroupHudModel.PADDING, layout.rows.get(0).y);
    }

    @Test
    public void noIconStateRemainsInTheModel() throws Exception {
        for (Field field : Row.class.getFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name, name.contains("icon") || name.contains("indicator"));
        }
        for (Field field : Entry.class.getFields()) {
            assertFalse(field.getName(), field.getName().toLowerCase().contains("indicator"));
        }
    }

    @Test
    public void panelSizeFollowsTheMemberRows() {
        List<Entry> members = Arrays.asList(new Entry(UUID.randomUUID(), "A", false),
                new Entry(UUID.randomUUID(), "A fairly long name", false));
        Layout layout = layout(members, false, false);
        assertEquals(2 * GroupHudModel.PADDING + GroupHudModel.HEAD_SLOT + GroupHudModel.TEXT_GAP + 18 * 6,
                layout.width);
        assertEquals(panelHeight(2), layout.height);
        assertEquals(panelHeight(1), layout(entries(1), false, false).height);
    }

    @Test
    public void headsKeepRoomForTheOutlineInsideTheirRow() {
        Layout layout = layout(entries(3), false, false);
        for (int i = 0; i < layout.rows.size(); i++) {
            Row row = layout.rows.get(i);
            // the outline (one pixel around the head) stays inside the row and the panel
            assertTrue(row.headY - GroupHudModel.OUTLINE >= row.y);
            assertTrue(row.headY + GroupHudModel.HEAD_SIZE + GroupHudModel.OUTLINE <= row.y + GroupHudModel.ROW_HEIGHT);
            assertTrue(row.headX - GroupHudModel.OUTLINE >= 0);
            assertTrue(row.headX + GroupHudModel.HEAD_SIZE + GroupHudModel.OUTLINE <= layout.width);
            if (i > 0) assertEquals(ROW_STEP, row.y - layout.rows.get(i - 1).y);
        }
    }

    @Test
    public void anEmptyGroupHasNoBox() {
        Layout layout = layout(Collections.<Entry>emptyList(), false, false);
        assertEquals(0, layout.rows.size());
        assertEquals(0, layout.width);
        assertEquals(0, layout.height);
    }

    @Test
    public void longListsAreCappedWithAMoreRow() {
        Layout layout = layout(entries(13), false, false);
        assertEquals(GroupHudModel.MAX_VISIBLE + 1, layout.rows.size());
        Row last = layout.rows.get(layout.rows.size() - 1);
        assertEquals(Kind.MORE, last.kind);
        assertEquals("+3 more", last.text);
        assertEquals(panelHeight(layout.rows.size()), layout.height);

        Layout exact = layout(entries(GroupHudModel.MAX_VISIBLE), false, false);
        assertEquals(GroupHudModel.MAX_VISIBLE, exact.rows.size());
        assertNotEquals(Kind.MORE, exact.rows.get(exact.rows.size() - 1).kind);
    }

    @Test
    public void topAnchorsListTheFirstMemberFirstAndBottomAnchorsReverse() {
        List<Entry> members = entries(2);
        Layout top = layout(members, false, false);
        assertEquals("Player0", top.rows.get(0).text);
        assertEquals("Player1", top.rows.get(1).text);

        Layout bottom = layout(members, false, true);
        assertEquals("Player1", bottom.rows.get(0).text);
        assertEquals("Player0", bottom.rows.get(1).text);
        assertEquals(top.width, bottom.width);
        assertEquals(top.height, bottom.height);
    }

    @Test
    public void rightAnchorsPutTheHeadOnTheRightWithTheNameBesideIt() {
        List<Entry> members = entries(1);
        Layout left = layout(members, false, false);
        Layout right = layout(members, true, false);
        Row l = left.rows.get(0);
        Row r = right.rows.get(0);
        assertEquals(GroupHudModel.PADDING + GroupHudModel.OUTLINE, l.headX);
        assertTrue(l.textX > l.headX + GroupHudModel.HEAD_SIZE);
        assertEquals(right.width - GroupHudModel.PADDING - GroupHudModel.OUTLINE - GroupHudModel.HEAD_SIZE, r.headX);
        assertEquals(r.headX - GroupHudModel.OUTLINE - GroupHudModel.TEXT_GAP, r.textX + WIDTH.applyAsInt(r.text));
        assertEquals(left.width, right.width);
    }

    @Test
    public void longNamesAreCutToTheMaximumTextWidth() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 40; i++) name.append('x');
        Layout layout = layout(Collections.singletonList(new Entry(UUID.randomUUID(), name.toString(), false)),
                false, false);
        String shown = layout.rows.get(0).text;
        assertTrue(shown.endsWith("..."));
        assertTrue(WIDTH.applyAsInt(shown) <= GroupHudModel.MAX_TEXT_WIDTH);
        assertEquals("Dayle", GroupHudModel.fit("Dayle", WIDTH));
    }

    @Test
    public void positioningStillPlacesTheNewPanelSizeCorrectly() {
        Layout layout = layout(entries(3), true, false);
        int screenW = 427;
        int screenH = 240;
        // default TOP_RIGHT: right edge and top edge 4 px in
        GroupHudPosition.Point origin = GroupHudPosition.origin(GroupHudPosition.DEFAULT, layout.width, layout.height,
                screenW, screenH);
        assertEquals(screenW - GroupHudPosition.MARGIN, origin.x + layout.width);
        assertEquals(GroupHudPosition.MARGIN, origin.y);
        // a drag into the bottom left quarter round-trips through anchor + offset
        GroupHudPosition.Placement dropped = GroupHudPosition.fromDrag(30, 150, layout.width, layout.height,
                screenW, screenH);
        assertEquals(GroupHudPosition.Anchor.BOTTOM_LEFT, dropped.anchor);
        GroupHudPosition.Point back = GroupHudPosition.origin(dropped, layout.width, layout.height, screenW, screenH);
        assertEquals(30, back.x);
        assertEquals(150, back.y);
        // a smaller screen keeps the whole panel visible
        GroupHudPosition.Point small = GroupHudPosition.origin(dropped, layout.width, layout.height, 100, 60);
        assertTrue(small.x >= 0 && small.x + layout.width <= Math.max(100, layout.width));
        assertTrue(small.y >= 0 && small.y + layout.height <= Math.max(60, layout.height));
    }

    /** The HUD row kinds, as one string: guards against a title kind coming back. */
    private static final class EnumSetOf {
        static String kinds() {
            StringBuilder names = new StringBuilder();
            for (Kind kind : Kind.values()) {
                if (names.length() > 0) names.append(',');
                names.append(kind.name());
            }
            return names.toString();
        }
    }
}
