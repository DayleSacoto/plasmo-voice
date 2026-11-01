package su.plo.voice.platform.forge.client.groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.network.groups.GroupsState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** What the Groups tab polls: snapshot replacement, version changes, busy and timeout. */
public class ClientGroupsGuiStateTest {
    private final ClientGroups groups = ClientGroups.get();
    private final List<GroupsAction> sent = new ArrayList<>();

    @Before
    public void setUp() {
        groups.reset();
        groups.setSender(sent::add);
    }

    @After
    public void tearDown() {
        groups.reset();
        groups.setSender(action -> { });
    }

    private static GroupsState snapshot(int caps) {
        return new GroupsState(caps, 3, 16, null, Collections.<GroupsState.BrowseEntry>emptyList(), 0);
    }

    @Test
    public void notLoadedUntilFirstSnapshotThenReplaced() {
        assertFalse(groups.loaded());
        assertNull(groups.state());
        int version = groups.version();
        GroupsState first = snapshot(1);
        groups.accept(first);
        assertTrue(groups.loaded());
        assertSame(first, groups.state());
        assertTrue(groups.version() != version);
        GroupsState second = snapshot(2);
        int afterFirst = groups.version();
        groups.accept(second);
        assertSame(second, groups.state());
        assertTrue(groups.version() != afterFirst);
    }

    @Test
    public void refreshNeverBlocksButActionsDoUntilTheResult() {
        groups.submit(GroupsAction.simple(groups.nextRequestId(), GroupsAction.Type.REFRESH), 0);
        assertFalse(groups.busy(1));
        int id = groups.nextRequestId();
        groups.submit(GroupsAction.simple(id, GroupsAction.Type.LEAVE), 0);
        assertEquals(2, sent.size());
        assertTrue(groups.busy(1));
        assertFalse(groups.timedOut(1));
        groups.accept(new GroupsResult(id, true, "k", "ok"));
        assertFalse(groups.busy(2));
        assertEquals("ok", groups.lastResult().message);
    }

    @Test
    public void unansweredActionTimesOut() {
        groups.submit(GroupsAction.simple(groups.nextRequestId(), GroupsAction.Type.LEAVE), 1000);
        assertTrue(groups.busy(1000 + ClientGroups.REQUEST_TIMEOUT_MS - 1));
        assertFalse(groups.busy(1000 + ClientGroups.REQUEST_TIMEOUT_MS));
        assertTrue(groups.timedOut(1000 + ClientGroups.REQUEST_TIMEOUT_MS));
    }

    @Test
    public void resetForgetsTheSnapshotAndResult() {
        groups.accept(snapshot(1));
        groups.accept(new GroupsResult(99, false, "k", "bad"));
        groups.reset();
        assertFalse(groups.loaded());
        assertNull(groups.lastResult());
    }
}
