package su.plo.voice.platform.forge.server.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

public class GroupsManagerTest {
    private RecordingListener listener;
    private GroupsManager manager;
    private final GroupProfile alice = profile("Alice");
    private final GroupProfile bob = profile("Bob");
    private final GroupProfile carol = profile("Carol");

    static GroupProfile profile(String name) {
        return new GroupProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
    }

    @Before
    public void setUp() {
        listener = new RecordingListener();
        manager = new GroupsManager(listener);
    }

    private Group create(GroupProfile owner) {
        return manager.create("g", null, false, Collections.<String>emptySet(), owner);
    }

    @Test
    public void profileEqualityIsById() {
        assertEquals(new GroupProfile(alice.getId(), "Other"), alice);
        assertEquals(alice.hashCode(), new GroupProfile(alice.getId(), "Other").hashCode());
        assertNotEquals(alice, bob);
    }

    @Test
    public void createWithOwnerJoinsOwner() {
        Group g = create(alice);
        assertSame(g, manager.group(g.getId()));
        assertSame(g, manager.groupOf(alice.getId()));
        assertEquals(alice, g.getOwner());
        assertTrue(g.isOwner(alice.getId()));
        assertFalse(g.isOwner(bob.getId()));
        assertEquals(1, g.memberCount());
        assertEquals(1, g.onlineCount());
        assertNotNull(manager.route(alice.getId()));
        assertEquals(1, manager.groups().size());
        assertEquals(0, listener.changed);
    }

    @Test
    public void consoleCreateHasNoOwner() {
        Group g = create(null);
        assertNull(g.getOwner());
        assertEquals(0, g.memberCount());
        assertNull(manager.groupOf(alice.getId()));
    }

    @Test
    public void createPersistentSignalsChange() {
        manager.create("p", "pw", true, new HashSet<>(Collections.singleton("group.x")), null);
        assertEquals(1, listener.changed);
    }

    @Test
    public void joinAddsMemberAndOnline() {
        Group g = create(alice);
        manager.join(bob, g);
        assertEquals(2, g.memberCount());
        assertEquals(2, g.onlineCount());
        assertSame(g, manager.groupOf(bob.getId()));
        assertEquals(2, g.onlineMembers().size());
    }

    @Test
    public void joiningAnotherGroupLeavesTheOldOne() {
        Group g1 = create(alice);
        manager.join(bob, g1);
        Group g2 = create(carol);
        manager.join(bob, g2);
        assertSame(g2, manager.groupOf(bob.getId()));
        assertFalse(g1.getMembers().contains(bob.getId()));
        assertTrue(g2.getMembers().contains(bob.getId()));
        assertFalse(manager.route(alice.getId()).getRecipients().contains(bob.getId()));
    }

    @Test
    public void duplicateJoinIsHarmless() {
        Group g = create(alice);
        manager.join(alice, g);
        assertEquals(1, g.memberCount());
        assertSame(g, manager.group(g.getId()));
        assertNotNull(manager.route(alice.getId()));
    }

    @Test
    public void leaveWithoutGroupReturnsFalse() {
        assertFalse(manager.leave(alice.getId()));
    }

    @Test
    public void leaveRemovesMemberRouteAndFiresSourceRemoved() {
        Group g = create(alice);
        manager.join(bob, g);
        UUID source = manager.route(bob.getId()).getSourceId();
        assertTrue(manager.leave(bob.getId()));
        assertNull(manager.groupOf(bob.getId()));
        assertNull(manager.route(bob.getId()));
        assertNull(manager.routeBySource(source));
        assertEquals(1, listener.removedSources.size());
        assertEquals(source, listener.removedSources.get(0).getSourceId());
        assertEquals(bob.getId(), listener.removedSources.get(0).getSpeaker());
        assertTrue(manager.route(alice.getId()).getRecipients().isEmpty());
    }

    @Test
    public void nonPersistentOwnerLeaveHandsOverToOnlineMember() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.leave(alice.getId());
        assertEquals(bob, g.getOwner());
        assertTrue(listener.groupNotices.contains("notifications.new_owner:Bob"));
        assertSame(g, manager.group(g.getId()));
    }

    @Test
    public void nonPersistentOwnerHandoverSkipsOfflineMembers() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.join(carol, g);
        manager.playerOffline(bob.getId());
        manager.leave(alice.getId());
        assertEquals(carol, g.getOwner());
    }

    @Test
    public void persistentOwnerLeaveKeepsOwnerAndGroup() {
        Group g = manager.create("p", null, true, Collections.<String>emptySet(), alice);
        manager.join(bob, g);
        manager.leave(alice.getId());
        assertEquals(alice, g.getOwner());
        assertSame(g, manager.group(g.getId()));
        assertFalse(listener.noticed("notifications.new_owner"));
    }

    @Test
    public void nonPersistentGroupDeletedWhenLastOnlineLeaves() {
        Group g = create(alice);
        manager.leave(alice.getId());
        assertNull(manager.group(g.getId()));
        assertNull(manager.groupOf(alice.getId()));
        assertTrue(manager.groups().isEmpty());
    }

    @Test
    public void nonPersistentGroupDeletedWhenLastOnlineLeavesEvenWithOfflineMembers() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.playerOffline(bob.getId());
        manager.leave(alice.getId());
        assertNull(manager.group(g.getId()));
        assertNull(manager.groupOf(bob.getId()));
    }

    @Test
    public void nonPersistentGroupDeletedWhenLastOnlineGoesOffline() {
        Group g = create(alice);
        manager.playerOffline(alice.getId());
        assertNull(manager.group(g.getId()));
        assertNull(manager.groupOf(alice.getId()));
        assertNull(manager.route(alice.getId()));
    }

    @Test
    public void persistentGroupSurvivesEmptyLeaveAndOffline() {
        Group g = manager.create("p", null, true, Collections.<String>emptySet(), alice);
        manager.playerOffline(alice.getId());
        assertSame(g, manager.group(g.getId()));
        assertSame(g, manager.groupOf(alice.getId()));
        assertEquals(0, g.onlineCount());
        assertEquals(1, g.memberCount());
        manager.playerOnline(alice);
        manager.leave(alice.getId());
        assertSame(g, manager.group(g.getId()));
        assertEquals(0, g.memberCount());
    }

    @Test
    public void reconnectRestoresMembershipWithNewSourceAndOwnerName() {
        Group g = create(alice);
        manager.join(bob, g);
        UUID oldSource = manager.route(alice.getId()).getSourceId();
        manager.playerOffline(alice.getId());
        assertNull(manager.route(alice.getId()));
        assertNull(manager.routeBySource(oldSource));
        assertEquals(1, g.onlineCount());
        assertEquals(2, g.memberCount());
        assertFalse(manager.route(bob.getId()).getRecipients().contains(alice.getId()));

        manager.playerOnline(new GroupProfile(alice.getId(), "AliceRenamed"));
        assertEquals(2, g.onlineCount());
        assertEquals("AliceRenamed", g.getOwner().getName());
        UUID newSource = manager.route(alice.getId()).getSourceId();
        assertNotEquals(oldSource, newSource);
        assertNotNull(manager.routeBySource(newSource));
        assertTrue(manager.route(bob.getId()).getRecipients().contains(alice.getId()));
    }

    @Test
    public void playerOnlineWithoutGroupDoesNothing() {
        manager.playerOnline(alice);
        assertNull(manager.route(alice.getId()));
    }

    @Test
    public void transferChangesOwnerAndNotifies() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.transfer(g, bob);
        assertEquals(bob, g.getOwner());
        assertTrue(g.isOwner(bob.getId()));
        assertFalse(g.isOwner(alice.getId()));
        assertTrue(listener.groupNotices.contains("notifications.new_owner:Bob"));
    }

    @Test
    public void deleteSendsNoNewOwnerAndClearsEverything() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.playerOffline(bob.getId());
        manager.delete(g);
        assertFalse(listener.noticed("notifications.new_owner"));
        assertNull(manager.group(g.getId()));
        assertNull(manager.groupOf(alice.getId()));
        assertNull(manager.groupOf(bob.getId()));
        assertNull(manager.route(alice.getId()));
        assertEquals(0, g.memberCount());
        assertEquals(0, g.onlineCount());
        assertEquals(2, listener.removedSources.size());
    }

    @Test
    public void deletePersistentSignalsChange() {
        Group g = manager.create("p", null, true, Collections.<String>emptySet(), alice);
        int before = listener.changed;
        manager.delete(g);
        assertTrue(listener.changed > before);
    }

    @Test
    public void deleteDoesNotTouchMembersNowInAnotherGroup() {
        Group g1 = create(alice);
        manager.join(bob, g1);
        Group g2 = create(carol);
        manager.join(bob, g2);
        manager.delete(g1);
        assertSame(g2, manager.groupOf(bob.getId()));
        assertNotNull(manager.route(bob.getId()));
    }

    @Test
    public void kickRemovesButAllowsRejoin() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.kick(g, bob);
        assertNull(manager.groupOf(bob.getId()));
        assertTrue(listener.playerNotices.contains(bob.getId() + "/notifications.kicked"));
        assertTrue(listener.groupNotices.contains("notifications.player_kicked:Bob"));
        assertFalse(g.isBanned(bob.getId()));
        manager.join(bob, g);
        assertSame(g, manager.groupOf(bob.getId()));
    }

    @Test
    public void banRemovesAndRecordsBan() {
        Group g = create(alice);
        manager.join(bob, g);
        manager.ban(g, bob);
        assertNull(manager.groupOf(bob.getId()));
        assertTrue(g.isBanned(bob.getId()));
        assertEquals(1, g.getBanned().size());
        assertTrue(listener.groupNotices.contains("notifications.player_banned:Bob"));
        assertTrue(listener.playerNotices.contains(bob.getId() + "/notifications.banned"));
    }

    @Test
    public void banNoDuplicatesAndUnban() {
        Group g = create(alice);
        manager.ban(g, bob);
        manager.ban(g, bob);
        assertEquals(1, g.getBanned().size());
        assertFalse(listener.playerNotices.contains(bob.getId() + "/notifications.banned"));
        assertTrue(manager.unban(g, bob));
        assertFalse(g.isBanned(bob.getId()));
        assertTrue(listener.groupNotices.contains("notifications.player_unbanned:Bob"));
        assertFalse(manager.unban(g, bob));
    }

    @Test
    public void banOfNonMemberDoesNotTouchTheirOtherGroup() {
        Group g1 = create(alice);
        Group g2 = create(carol);
        manager.join(bob, g2);
        manager.ban(g1, bob);
        assertSame(g2, manager.groupOf(bob.getId()));
        assertNotNull(manager.route(bob.getId()));
        assertTrue(g1.isBanned(bob.getId()));
        assertFalse(listener.playerNotices.contains(bob.getId() + "/notifications.banned"));
    }

    @Test
    public void flagUpdatesSignalChangeOnlyForPersistentGroups() {
        Group g = create(alice);
        manager.rename(g, "new");
        manager.setPassword(g, "pw");
        manager.setPermissions(g, new HashSet<>(Collections.singleton("a")));
        assertEquals("new", g.getName());
        assertEquals("pw", g.getPassword());
        assertEquals(Collections.singleton("a"), g.getPermissions());
        assertEquals(0, listener.changed);

        manager.setPersistent(g, true);
        assertTrue(g.isPersistent());
        assertEquals(1, listener.changed);
        manager.rename(g, "again");
        assertEquals(2, listener.changed);
        manager.setPersistent(g, false);
        assertEquals(3, listener.changed);
        manager.setPassword(g, null);
        assertNull(g.getPassword());
        assertEquals(3, listener.changed);
    }

    @Test
    public void routeContainsOtherOnlineMembersOnly() {
        Group g = create(alice);
        manager.join(bob, g);
        Group other = create(carol);
        GroupRoute a = manager.route(alice.getId());
        assertTrue(a.getRecipients().contains(bob.getId()));
        assertFalse(a.getRecipients().contains(alice.getId()));
        assertFalse(a.getRecipients().contains(carol.getId()));
        assertEquals(1, a.getSourceState());
        assertSame(manager.route(bob.getId()), manager.routeBySource(manager.route(bob.getId()).getSourceId()));
        assertEquals(2, manager.routes(g).size());
        assertEquals(1, manager.routes(other).size());
        assertTrue(manager.route(carol.getId()).getRecipients().isEmpty());
    }

    @Test
    public void routesAreRepublishedAndNotified() {
        Group g = create(alice);
        int before = listener.routesChanged;
        manager.join(bob, g);
        assertTrue(listener.routesChanged > before);
        UUID source = manager.route(alice.getId()).getSourceId();
        manager.leave(bob.getId());
        assertEquals(source, manager.route(alice.getId()).getSourceId());
    }

    @Test
    public void snapshotAndRestoreRoundTripPersistentOnly() {
        Group p = manager.create("p", "pw", true, new HashSet<>(Collections.singleton("perm")), alice);
        manager.join(bob, p);
        manager.ban(p, carol);
        Group tmp = create(profile("Dave"));

        GroupsStore.Data data = manager.snapshot();
        assertEquals(1, data.getGroups().size());
        assertEquals(2, data.getGroupByPlayer().size());
        assertFalse(data.getGroupByPlayer().containsKey(profile("Dave").getId()));

        GroupsManager other = new GroupsManager(new RecordingListener());
        other.restore(data);
        Group r = other.group(p.getId());
        assertNotNull(r);
        assertNull(other.group(tmp.getId()));
        assertEquals("p", r.getName());
        assertEquals("pw", r.getPassword());
        assertTrue(r.isPersistent());
        assertEquals(alice, r.getOwner());
        assertEquals(Collections.singleton("perm"), r.getPermissions());
        assertEquals(2, r.memberCount());
        assertEquals(0, r.onlineCount());
        assertTrue(r.isBanned(carol.getId()));
        assertSame(r, other.groupOf(alice.getId()));
        assertSame(r, other.groupOf(bob.getId()));
        assertNull(other.route(alice.getId()));

        other.playerOnline(alice);
        assertNotNull(other.route(alice.getId()));
    }

    @Test
    public void clearForgetsEverything() {
        Group g = create(alice);
        manager.clear();
        assertTrue(manager.groups().isEmpty());
        assertNull(manager.groupOf(alice.getId()));
        assertNull(manager.route(alice.getId()));
        assertNull(manager.group(g.getId()));
    }
}
