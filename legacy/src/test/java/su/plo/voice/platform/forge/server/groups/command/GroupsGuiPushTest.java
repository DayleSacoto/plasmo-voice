package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;
import su.plo.voice.platform.forge.network.groups.GroupsState;
import su.plo.voice.platform.forge.server.groups.Group;

/** Snapshots are pushed when something changed, to everyone who is voice-connected, and only then. */
public class GroupsGuiPushTest {
    private final GuiHarness h = new GuiHarness();
    private final FakeServer.Source alice = h.player("Alice", false);
    private final FakeServer.Source bob = h.player("Bob", false);

    private Group aliceGroup() {
        return h.manager.create("Alpha", null, false, Collections.<String>emptySet(), alice.profile);
    }

    @Test
    public void connectingPushesTheStateImmediately() {
        h.service.connected(alice.id());
        assertEquals(1, h.statesOf(alice).size());
        assertNull(h.lastState(alice).current);
        assertEquals(0, h.statesOf(bob).size());
    }

    @Test
    public void tickSendsOnlyWhatChanged() {
        h.service.connected(alice.id());
        h.service.connected(bob.id());
        h.service.tick();
        assertEquals(1, h.statesOf(alice).size());
        assertEquals(1, h.statesOf(bob).size());

        Group group = aliceGroup();
        h.service.tick();
        assertEquals(2, h.statesOf(alice).size());
        assertEquals(group.getId(), h.lastState(alice).current.id);
        // bob sees the new group in the browse list
        assertEquals(2, h.statesOf(bob).size());
        assertEquals(1, h.lastState(bob).browse.size());

        h.service.tick();
        h.service.tick();
        assertEquals(2, h.statesOf(alice).size());
        assertEquals(2, h.statesOf(bob).size());
    }

    @Test
    public void everyKindOfMutationMarksTheStateDirty() {
        Group group = aliceGroup();
        long version = h.manager.version();
        Runnable[] mutations = {
                () -> h.manager.rename(group, "Beta"),
                () -> h.manager.setPassword(group, "pw"),
                () -> h.manager.setPersistent(group, true),
                () -> h.manager.setPermissions(group, Collections.singleton("vip")),
                () -> h.manager.join(bob.profile, group),
                () -> h.manager.transfer(group, bob.profile),
                () -> h.manager.ban(group, alice.profile),
                () -> h.manager.unban(group, alice.profile),
                () -> h.manager.playerOffline(bob.id()),
                () -> h.manager.playerOnline(bob.profile),
                () -> h.manager.leave(bob.id()),
                () -> h.manager.delete(group),
        };
        for (Runnable mutation : mutations) {
            mutation.run();
            assertTrue(h.manager.version() > version);
            version = h.manager.version();
        }
        assertNotNull(h.manager.create("Gamma", null, false, Collections.<String>emptySet(), null));
        assertTrue(h.manager.version() > version);
    }

    @Test
    public void aPermissionChangeIsCaughtBySafetyNetTicks() {
        h.service.connected(alice.id());
        h.service.tick();
        assertFalse(h.lastState(alice).can(GroupsState.CAN_CREATE_PERSISTENT));

        alice.grant("pv.addon.groups.flag.persistent");
        h.service.tick();
        assertEquals(1, h.statesOf(alice).size());
        for (int i = 0; i < 100; i++) h.service.tick();
        assertEquals(2, h.statesOf(alice).size());
        assertTrue(h.lastState(alice).can(GroupsState.CAN_CREATE_PERSISTENT));
    }

    @Test
    public void disconnectingForgetsWhatWasSent() {
        h.service.connected(alice.id());
        h.service.disconnected(alice.id());
        h.service.connected(alice.id());
        assertEquals(2, h.statesOf(alice).size());
    }

    @Test
    public void playersWhoAreNotVoiceConnectedGetNothing() {
        h.connected.remove(bob.id());
        aliceGroup();
        h.service.tick();
        assertEquals(0, h.statesOf(bob).size());
        assertEquals(1, h.statesOf(alice).size());
    }

    @Test
    public void membersGoingOfflineAreShownWithTheirNames() {
        Group group = aliceGroup();
        h.manager.join(bob.profile, group);
        h.service.tick();
        assertTrue(h.lastState(alice).current.members.get(1).online);

        h.server.logout(bob);
        h.connected.remove(bob.id());
        h.manager.playerOffline(bob.id());
        h.service.tick();

        GroupsState.CurrentGroup current = h.lastState(alice).current;
        assertEquals(2, current.members.size());
        assertEquals("Bob", current.members.get(1).name);
        assertFalse(current.members.get(1).online);
    }

    @Test
    public void deletedGroupSendsFormerMembersTheirEmptyState() {
        Group group = aliceGroup();
        h.manager.join(bob.profile, group);
        h.service.tick();
        h.manager.delete(group);
        h.service.tick();
        assertNull(h.lastState(alice).current);
        assertNull(h.lastState(bob).current);
    }
}
