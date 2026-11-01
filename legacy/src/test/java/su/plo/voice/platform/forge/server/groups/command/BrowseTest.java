package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.click;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import net.minecraft.event.ClickEvent;
import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.Group;

public class BrowseTest extends CommandTestBase {
    private final FakeServer.Source viewer = server.player("Viewer", false);
    private final FakeServer.Source op = server.player("Root", true);

    /** A group owned by a fresh online player, plus extra online members. */
    private Group group(String name, String... extraMembers) {
        FakeServer.Source owner = server.player("Own" + name, false);
        Group group = manager.create(name, null, false, Collections.<String>emptySet(), owner.profile);
        for (String member : extraMembers) manager.join(server.player(member, false).profile, group);
        return group;
    }

    /** The group titles (first line of each entry) in page order. */
    private static List<String> titles(FakeServer.Source source) {
        List<String> titles = new ArrayList<>();
        for (String text : source.texts()) {
            if (text.startsWith("pv.addon.groups.format.group_name(")) {
                titles.add(text);
            }
        }
        return titles;
    }

    private static String title(String name) {
        return msg("format.group_name", name);
    }

    @Test
    public void emptyServerHasNoGroups() {
        run(viewer, "");
        assertEquals(Arrays.asList(msg("divider"), msg("command.browse.error.no_groups"), msg("divider")), viewer.texts());
    }

    @Test
    public void noArgumentsBrowsesPageOne() {
        group("Alpha");
        run(viewer, "");
        assertEquals(Arrays.asList(msg("divider"), title("Alpha"), msg("format.players_and_owner", 1, "OwnAlpha"),
                msg("button.join"), msg("divider"), msg("format.page", 1, 1)), viewer.texts());
    }

    @Test
    public void groupsAreSortedByOnlineCountThenName() {
        group("Delta");
        group("Charlie");
        group("Bravo");
        group("Echo", "E1", "E2");
        group("Alpha");

        run(viewer, "browse");
        assertEquals(Arrays.asList(title("Echo"), title("Alpha"), title("Bravo")), titles(viewer));
        assertEquals(msg("format.page", 1, 2) + " " + msg("button.next"), viewer.texts().get(viewer.received.size() - 1));
        assertEquals(ClickEvent.Action.RUN_COMMAND, click(last(viewer), msg("button.next")).getAction());
        assertEquals("/groups browse 2", click(last(viewer), msg("button.next")).getValue());

        clear(viewer);
        run(viewer, "browse 2");
        assertEquals(Arrays.asList(title("Charlie"), title("Delta")), titles(viewer));
        assertEquals("/groups browse 1", click(last(viewer), msg("button.prev")).getValue());
    }

    @Test
    public void invalidPageTextFallsBackToPageOne() {
        group("Alpha");
        group("Bravo");
        run(viewer, "browse abc");
        assertEquals(Arrays.asList(title("Alpha"), title("Bravo")), titles(viewer));
    }

    @Test
    public void pagesOutOfRangeAreNotFound() {
        group("Alpha");
        run(viewer, "browse 2");
        run(viewer, "browse 0");
        run(viewer, "browse -1");
        assertEquals(Arrays.asList(msg("command.browse.error.page_not_found"),
                msg("command.browse.error.page_not_found"), msg("command.browse.error.page_not_found")), viewer.texts());
    }

    @Test
    public void groupsWithRequiredPermissionsAreHiddenFromPlayersWithoutThem() {
        Group vip = group("Vips");
        manager.setPermissions(vip, new HashSet<>(Collections.singletonList("vip.a")));
        group("Open");

        run(viewer, "browse");
        assertEquals(Collections.singletonList(title("Open")), titles(viewer));

        clear(viewer);
        viewer.grant("vip.a");
        run(viewer, "browse");
        assertEquals(Arrays.asList(title("Open"), title("Vips")), titles(viewer));
    }

    @Test
    public void browseAllAndOperatorsSeeEverything() {
        Group vip = group("Vips");
        manager.setPermissions(vip, new HashSet<>(Collections.singletonList("vip.a")));
        group("Open");

        run(op, "browse");
        assertEquals(Arrays.asList(title("Open"), title("Vips")), titles(op));

        viewer.grant("pv.addon.groups.browse.all");
        run(viewer, "browse");
        assertEquals(Arrays.asList(title("Open"), title("Vips")), titles(viewer));
    }

    @Test
    public void ifEverythingIsHiddenTheListIsEmpty() {
        Group vip = group("Vips");
        manager.setPermissions(vip, new HashSet<>(Collections.singletonList("vip.a")));
        run(viewer, "browse");
        assertEquals(Arrays.asList(msg("divider"), msg("command.browse.error.no_groups"), msg("divider")), viewer.texts());
    }

    @Test
    public void browseNeedsItsPermission() {
        viewer.deny("pv.addon.groups.browse");
        run(viewer, "");
        run(viewer, "browse 2");
        assertEquals(Arrays.asList(msg("error.no_permission", "pv.addon.groups.browse"),
                msg("error.no_permission", "pv.addon.groups.browse")), viewer.texts());
    }

    @Test
    public void passwordGroupsGetATypingJoinButton() {
        Group locked = group("Locked");
        manager.setPassword(locked, "zq9");
        run(viewer, "browse");

        assertEquals(msg("icons.password_protected") + " " + title("Locked"), viewer.texts().get(1));
        ClickEvent join = click(viewer.received.get(3), msg("button.join"));
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, join.getAction());
        assertEquals("/groups join " + locked.getId() + " ", join.getValue());
        assertTrue(!viewer.texts().toString().contains("zq9"));
    }

    @Test
    public void openGroupsGetARunJoinButton() {
        Group open = group("Open");
        run(viewer, "browse");
        ClickEvent join = click(viewer.received.get(3), msg("button.join"));
        assertEquals(ClickEvent.Action.RUN_COMMAND, join.getAction());
        assertEquals("/groups join " + open.getId(), join.getValue());
    }
}
