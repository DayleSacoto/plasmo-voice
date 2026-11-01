package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.network.groups.GroupsProtocol;
import su.plo.voice.platform.forge.network.groups.GroupsState;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;
import su.plo.voice.platform.forge.server.groups.GroupsSnapshots;

public class GroupsSnapshotsTest {
    private final FakeServer server = new FakeServer();
    private final GroupsManager manager = new GroupsManager(new GroupsNotifier(server));
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);
    private final FakeServer.Source op = server.player("Root", true);

    private GroupsState state(FakeServer.Source viewer) {
        return GroupsSnapshots.build(manager, server.settings(), viewer, id -> server.online.containsKey(id));
    }

    private Group create(FakeServer.Source owner, String name) {
        manager.playerOnline(owner.profile);
        return manager.create(name, null, false, Collections.<String>emptySet(), owner.profile);
    }

    private void join(FakeServer.Source who, Group group) {
        manager.playerOnline(who.profile);
        manager.join(who.profile, group);
    }

    @Test
    public void thePasswordNeverLeavesTheServer() throws Exception {
        Group group = create(alice, "Alpha");
        manager.setPassword(group, "hunter2-secret");
        join(bob, group);

        for (FakeServer.Source viewer : Arrays.asList(alice, bob, op)) {
            GroupsState state = state(viewer);
            byte[] bytes = GroupsProtocol.encode(state);
            assertEquals(-1, indexOf(bytes, "hunter2-secret".getBytes(StandardCharsets.UTF_8)));
            assertEquals(-1, indexOf(bytes, "hunter2".getBytes(StandardCharsets.UTF_8)));
            assertTrue(state.browse.get(0).hasPassword);
        }
        assertTrue(state(alice).current.hasPassword);
    }

    @Test
    public void ownerAndMemberGetTheirOwnCapabilities() {
        Group group = create(alice, "Alpha");
        join(bob, group);

        GroupsState.CurrentGroup owner = state(alice).current;
        for (int cap : new int[] {GroupsState.CAN_LEAVE, GroupsState.CAN_INVITE, GroupsState.CAN_KICK,
                GroupsState.CAN_BAN, GroupsState.CAN_UNBAN, GroupsState.CAN_TRANSFER, GroupsState.CAN_DELETE,
                GroupsState.CAN_SET_NAME, GroupsState.CAN_SET_PASSWORD}) {
            assertTrue("owner cap " + cap, owner.can(cap));
        }
        assertFalse(owner.can(GroupsState.CAN_SET_PERSISTENT));
        assertFalse(owner.can(GroupsState.CAN_SET_PERMISSIONS));
        assertTrue(owner.isOwner(alice.id()));

        GroupsState.CurrentGroup member = state(bob).current;
        assertTrue(member.can(GroupsState.CAN_LEAVE));
        assertTrue(member.can(GroupsState.CAN_INVITE));
        for (int cap : new int[] {GroupsState.CAN_KICK, GroupsState.CAN_BAN, GroupsState.CAN_UNBAN,
                GroupsState.CAN_TRANSFER, GroupsState.CAN_DELETE, GroupsState.CAN_SET_NAME,
                GroupsState.CAN_SET_PASSWORD, GroupsState.CAN_SET_PERSISTENT, GroupsState.CAN_SET_PERMISSIONS}) {
            assertFalse("member cap " + cap, member.can(cap));
        }

        GroupsState.CurrentGroup root = joinedState(op, group);
        assertTrue(root.can(GroupsState.CAN_SET_PERSISTENT));
        assertTrue(root.can(GroupsState.CAN_KICK));
    }

    private GroupsState.CurrentGroup joinedState(FakeServer.Source who, Group group) {
        join(who, group);
        return state(who).current;
    }

    @Test
    public void playerCapabilitiesFollowTheNodes() {
        GroupsState user = state(alice);
        assertTrue(user.can(GroupsState.CAN_BROWSE));
        assertTrue(user.can(GroupsState.CAN_CREATE));
        assertTrue(user.can(GroupsState.CAN_JOIN));
        assertTrue(user.can(GroupsState.CAN_CREATE_NAME));
        assertTrue(user.can(GroupsState.CAN_CREATE_PASSWORD));
        assertFalse(user.can(GroupsState.CAN_CREATE_PERSISTENT));
        assertFalse(user.can(GroupsState.CAN_CREATE_PERMISSIONS));

        alice.deny("pv.addon.groups.create");
        assertFalse(state(alice).can(GroupsState.CAN_CREATE));
        assertTrue(state(op).can(GroupsState.CAN_CREATE_PERSISTENT));
        assertEquals(3, user.nameMin);
        assertEquals(32, user.nameMax);
    }

    @Test
    public void browseFollowsTheCommandVisibilityAndJoinability() {
        Group open = create(alice, "Open");
        Group vip = manager.create("Vip", null, false, new HashSet<>(Collections.singleton("vip")), null);
        Group banned = manager.create("Banned", null, false, Collections.<String>emptySet(), null);
        manager.ban(banned, bob.profile);

        GroupsState seen = state(bob);
        assertEquals(2, seen.browse.size());
        assertEquals("Open", seen.browse.get(0).name);
        assertTrue(seen.browse.get(0).joinable);
        assertEquals("Banned", seen.browse.get(1).name);
        assertFalse(seen.browse.get(1).joinable);
        assertNull(seen.browse.get(1).ownerName);
        assertEquals("Alice", seen.browse.get(0).ownerName);
        assertEquals(2, seen.browseTotal);

        bob.grant("vip");
        assertEquals(3, state(bob).browse.size());

        // browse.all shows the group but the join is still known to fail
        bob.granted.remove("vip");
        bob.grant("pv.addon.groups.browse.all");
        GroupsState all = state(bob);
        assertEquals(3, all.browse.size());
        GroupsState.BrowseEntry vipEntry = null;
        for (GroupsState.BrowseEntry entry : all.browse) {
            if (entry.id.equals(vip.getId())) vipEntry = entry;
        }
        assertNotNull(vipEntry);
        assertFalse(vipEntry.joinable);
        assertTrue(state(op).browse.get(0).joinable);
        assertEquals(open.getId(), state(op).browse.get(0).id);
    }

    @Test
    public void withoutTheBrowseNodeTheListIsEmpty() {
        create(alice, "Open");
        bob.deny("pv.addon.groups.browse");
        GroupsState seen = state(bob);
        assertTrue(seen.browse.isEmpty());
        assertEquals(0, seen.browseTotal);
        assertFalse(seen.can(GroupsState.CAN_BROWSE));
    }

    @Test
    public void browseIsCappedWithTheTotalKept() {
        for (int i = 0; i < GroupsProtocol.MAX_BROWSE + 5; i++) {
            manager.create(String.format("Group %03d", i), null, false, Collections.<String>emptySet(), null);
        }
        GroupsState seen = state(bob);
        assertEquals(GroupsProtocol.MAX_BROWSE, seen.browse.size());
        assertEquals(GroupsProtocol.MAX_BROWSE + 5, seen.browseTotal);
        assertEquals("Group 000", seen.browse.get(0).name);
    }

    @Test
    public void offlineMembersAreListedWithNamesFromEveryFallback() {
        Group group = create(alice, "Alpha");
        join(bob, group);
        UUID stranger = UUID.fromString("12345678-0000-0000-0000-000000000001");
        // a persistent group restored from a file knows its members only by id
        UUID nameless = UUID.fromString("abcdef01-0000-0000-0000-000000000002");
        Group stored = restoredGroupWith(nameless, stranger);

        manager.playerOffline(bob.id());
        GroupsState.CurrentGroup current = state(alice).current;
        assertEquals(2, current.members.size());
        assertEquals("Alice", current.members.get(0).name);
        assertTrue(current.members.get(0).online);
        assertEquals("Bob", current.members.get(1).name);
        assertFalse(current.members.get(1).online);

        manager.setNameFallback(id -> id.equals(stranger) ? "FromCache" : null);
        GroupsState.CurrentGroup restored = restoredViewer(stored);
        assertEquals(3, restored.members.size());
        for (GroupsState.Member member : restored.members) {
            if (member.id.equals(op.id())) continue;
            assertFalse(member.online);
            assertEquals(member.id.equals(stranger) ? "FromCache" : "abcdef01", member.name);
        }
    }

    private Group restoredGroupWith(UUID... members) {
        UUID id = UUID.randomUUID();
        su.plo.voice.platform.forge.server.groups.GroupsStore.Entry entry =
                new su.plo.voice.platform.forge.server.groups.GroupsStore.Entry(id, "Stored", null, true,
                        new HashSet<>(Arrays.asList(members)), Collections.<GroupProfile>emptyList(), null,
                        Collections.<String>emptySet());
        java.util.Map<UUID, UUID> byPlayer = new java.util.LinkedHashMap<>();
        for (UUID member : members) byPlayer.put(member, id);
        manager.restore(new su.plo.voice.platform.forge.server.groups.GroupsStore.Data(
                Collections.singletonList(entry), byPlayer));
        return manager.group(id);
    }

    /** Views a group through a viewer that is one of its members (an op standing in). */
    private GroupsState.CurrentGroup restoredViewer(Group group) {
        join(op, group);
        return state(op).current;
    }

    @Test
    public void anOwnerlessGroupHasNoOwnerAndADeletedGroupIsGone() {
        Group group = create(alice, "Alpha");
        join(bob, group);
        assertEquals("Alice", state(bob).current.ownerName);

        manager.delete(group);
        assertNull(state(alice).current);
        assertNull(state(bob).current);
        assertTrue(state(alice).browse.isEmpty());

        Group ownerless = manager.create("Nobody", null, false, Collections.<String>emptySet(), null);
        join(bob, ownerless);
        GroupsState.CurrentGroup current = state(bob).current;
        assertNull(current.ownerId);
        assertNull(current.ownerName);
    }

    @Test
    public void theConsoleHasNoCurrentGroup() {
        create(alice, "Alpha");
        GroupsState seen = GroupsSnapshots.build(manager, GroupsSettings.defaults(), server.console(), id -> false);
        assertNull(seen.current);
        assertEquals(1, seen.browse.size());
    }

    @Test
    public void unchangedStateEncodesToIdenticalBytes() {
        Group group = create(alice, "Alpha");
        join(bob, group);
        manager.setPermissions(group, new HashSet<>(Arrays.asList("b", "a", "c")));
        byte[] first = GroupsProtocol.encode(state(alice));
        byte[] second = GroupsProtocol.encode(state(alice));
        assertTrue(Arrays.equals(first, second));
        assertEquals(Arrays.asList("a", "b", "c"), state(alice).current.permissions);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
