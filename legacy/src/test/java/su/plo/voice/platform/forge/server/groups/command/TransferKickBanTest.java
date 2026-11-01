package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.notice;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.Group;

public class TransferKickBanTest extends CommandTestBase {
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);
    private final FakeServer.Source carol = server.player("Carol", false);
    private final Group group;

    public TransferKickBanTest() {
        group = created(alice, "Alpha");
        joined(bob, group);
        clear(alice, bob, carol);
    }

    // ---- transfer ----

    @Test
    public void transferMakesTheTargetOwnerWithASingleAnnouncement() {
        run(alice, "transfer bob");

        assertTrue(group.isOwner(bob.id()));
        assertFalse(group.isOwner(alice.id()));
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.new_owner", "Bob")), alice.texts());
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.new_owner", "Bob")), bob.texts());
    }

    @Test
    public void transferRejectsNonMembersUnknownPlayersAndSelf() {
        run(alice, "transfer Carol");
        run(alice, "transfer Nobody");
        run(alice, "transfer Alice");
        run(alice, "transfer");

        assertEquals(Arrays.asList(msg("command.transfer.error.not_in_group"), msg("error.player_not_found"),
                msg("command.transfer.error.already_owner"), msg("command.transfer.error.usage")), alice.texts());
        assertTrue(group.isOwner(alice.id()));
    }

    @Test
    public void transferToAnOfflineMemberIsUnknown() {
        server.logout(bob);
        run(alice, "transfer Bob");
        assertEquals(Collections.singletonList(msg("error.player_not_found")), alice.texts());
    }

    // ---- kick ----

    @Test
    public void kickRemovesTheMemberAndTheManagerAnnouncesIt() {
        run(alice, "kick bob");

        assertNull(manager.groupOf(bob.id()));
        assertFalse(group.isBanned(bob.id()));
        assertEquals(1, bob.count(msg("notifications.kicked")));
        assertEquals(1, alice.count(notice("Alpha", "notifications.player_kicked", "Bob")));
        assertEquals(1, alice.received.size());
        assertEquals(1, bob.received.size());
    }

    @Test
    public void kickRejectsNonMembersAndUnknownPlayers() {
        run(alice, "kick Carol");
        run(alice, "kick Nobody");
        run(alice, "kick");
        assertEquals(Arrays.asList(msg("command.kick.error.not_in_group"), msg("error.player_not_found"),
                msg("command.kick.error.usage")), alice.texts());
    }

    // ---- ban / unban ----

    @Test
    public void banRemovesAndBlocksTheMember() {
        run(alice, "ban Bob");

        assertNull(manager.groupOf(bob.id()));
        assertTrue(group.isBanned(bob.id()));
        assertEquals(1, bob.count(msg("notifications.banned")));
        assertEquals(1, alice.count(notice("Alpha", "notifications.player_banned", "Bob")));

        clear(bob);
        run(bob, "join " + group.getId());
        assertEquals(Collections.singletonList(msg("command.join.error.banned")), bob.texts());
    }

    @Test
    public void banWorksOnOnlinePlayersOutsideTheGroup() {
        run(alice, "ban carol");
        assertTrue(group.isBanned(carol.id()));
        assertTrue(carol.received.isEmpty());
        assertEquals(1, alice.count(notice("Alpha", "notifications.player_banned", "Carol")));
    }

    @Test
    public void banRejectsRepeatsAndUnknownPlayers() {
        run(alice, "ban Bob");
        clear(alice);
        run(alice, "ban Bob");
        run(alice, "ban Nobody");
        run(alice, "ban");
        assertEquals(Arrays.asList(msg("command.ban.error.already_banned"), msg("error.player_not_found"),
                msg("command.ban.error.usage")), alice.texts());
    }

    @Test
    public void unbanLiftsTheBanCaseInsensitively() {
        run(alice, "ban Bob");
        clear(alice, bob);

        run(alice, "unban BOB");

        assertFalse(group.isBanned(bob.id()));
        assertEquals(1, alice.count(notice("Alpha", "notifications.player_unbanned", "Bob")));
        run(bob, "join " + group.getId());
        assertSame(group, manager.groupOf(bob.id()));
    }

    @Test
    public void unbanWorksWhileTheBannedPlayerIsOffline() {
        run(alice, "ban Bob");
        server.logout(bob);
        clear(alice);

        run(alice, "unban Bob");

        assertFalse(group.isBanned(bob.id()));
    }

    @Test
    public void unbanRejectsPlayersThatAreNotBanned() {
        run(alice, "unban Bob");
        run(alice, "unban");
        assertEquals(Arrays.asList(msg("command.unban.error.not_banned"), msg("command.unban.error.usage")),
                alice.texts());
    }

    @Test
    public void moderationNeedsTheOwnerNodeOrAll() {
        alice.deny("pv.addon.groups.kick.owner").deny("pv.addon.groups.ban.owner");
        run(alice, "kick Bob");
        run(alice, "ban Bob");
        run(alice, "unban Bob");
        assertEquals(Arrays.asList(msg("error.no_permission", "pv.addon.groups.kick.owner"),
                msg("error.no_permission", "pv.addon.groups.ban.owner"),
                msg("error.no_permission", "pv.addon.groups.ban.owner")), alice.texts());
        assertSame(group, manager.groupOf(bob.id()));

        alice.grant("pv.addon.groups.kick.all");
        run(alice, "kick Bob");
        assertNull(manager.groupOf(bob.id()));
    }
}
