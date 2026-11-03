package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupsClientSupport;

/** Players whose current client does not support Groups: invites, /groups and their stored memberships. */
public class GroupsCompatibilityTest extends CommandTestBase {
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);

    @Test
    public void invitingAnOlderClientIsRejectedWithAClearMessage() {
        created(alice, "Alpha");
        server.clients.put(bob.id(), GroupsClientSupport.OUTDATED);
        clear(alice, bob);

        run(alice, "invite Bob");

        assertEquals(Collections.singletonList(msg("command.invite.error.client_outdated", "Bob")), alice.texts());
        // the target hears about it in plain chat, and gets no invitation or join button
        assertEquals(Collections.singletonList(msg("notifications.invite_client_outdated", "Alice")), bob.texts());
        assertNull(manager.groupOf(bob.id()));
    }

    @Test
    public void aPlayerStillConnectingIsNeverCalledOutdated() {
        created(alice, "Alpha");
        server.clients.put(bob.id(), GroupsClientSupport.CONNECTING);
        clear(alice, bob);

        run(alice, "invite Bob");

        assertEquals(Collections.singletonList(msg("command.invite.error.client_connecting", "Bob")), alice.texts());
        assertTrue(bob.received.isEmpty());
    }

    @Test
    public void theGuiInviteGivesTheSameError() {
        GuiHarness h = new GuiHarness();
        FakeServer.Source owner = h.player("Alice", false);
        FakeServer.Source older = h.server.player("Bob", false);
        h.server.clients.put(older.id(), GroupsClientSupport.OUTDATED);
        h.act(owner, GroupsAction.Type.CREATE, b -> b.name("Alpha"));
        older.clear();

        GroupsResult result = h.act(owner, GroupsAction.Type.INVITE, b -> b.player(older.id()));

        assertFalse(result.ok);
        assertEquals("command.invite.error.client_outdated", result.key);
        assertEquals(msg("command.invite.error.client_outdated", "Bob"), result.message);
        assertEquals(Collections.singletonList(msg("notifications.invite_client_outdated", "Alice")), older.texts());
    }

    @Test
    public void anOlderClientCannotUseGroupsCommandsButTheConsoleCan() {
        Group group = created(alice, "Alpha");
        server.clients.put(bob.id(), GroupsClientSupport.OUTDATED);
        clear(alice, bob);

        run(bob, "join " + group.getId());
        run(bob, "");

        assertEquals(Arrays.asList(msg("error.client_outdated"), msg("error.client_outdated")), bob.texts());
        assertNull(manager.groupOf(bob.id()));
        assertTrue(suggest(bob, "").isEmpty());
        assertFalse(suggest(alice, "").isEmpty());

        FakeServer.Source console = server.console();
        run(console, "info");
        assertFalse(console.texts().contains(msg("error.client_outdated")));
    }

    @Test
    public void anOlderClientKeepsItsStoredMembershipWhileInactive() {
        Group group = created(alice, "Alpha");
        manager.setPersistent(group, true);
        joined(bob, group);
        // Bob reconnects with an older client: offline for Groups, and not brought back online
        manager.playerOffline(bob.id());
        server.clients.put(bob.id(), GroupsClientSupport.OUTDATED);
        clear(alice, bob);

        assertSame(group, manager.groupOf(bob.id()));
        assertTrue(group.getMembers().contains(bob.id()));
        assertFalse(group.onlineMembers().contains(bob.id()));
        run(alice, "invite Bob");
        assertEquals(Collections.singletonList(msg("command.invite.error.already_joined")), alice.texts());
        assertSame(group, manager.groupOf(bob.id()));
    }
}
