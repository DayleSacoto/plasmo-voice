package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.notice;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

public class CreateJoinLeaveTest extends CommandTestBase {
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);
    private final FakeServer.Source op = server.player("Root", true);

    // ---- create ----

    @Test
    public void createWithoutFlagsUsesTheDefaultNameAndOwnsTheGroup() {
        run(alice, "create");

        Group group = manager.groupOf(alice.id());
        assertNotNull(group);
        assertEquals("Alice's group", group.getName());
        assertTrue(group.isOwner(alice.id()));
        assertNull(group.getPassword());
        assertFalse(group.isPersistent());
        assertTrue(group.getPermissions().isEmpty());
        assertEquals(msg("command.create.success", "Alice's group"), alice.texts().get(0));
        assertEquals(msg("divider"), alice.texts().get(1));
        assertEquals(msg("divider"), alice.texts().get(alice.received.size() - 1));
        assertEquals(msg("button.leave") + "  " + msg("button.invite"), alice.texts().get(alice.received.size() - 2));
    }

    @Test
    public void createReadsEveryFlag() {
        run(op, "create name:My Team password:secret permissions:vip.a, vip.b persistent:true");

        Group group = manager.groupOf(op.id());
        assertEquals("My Team", group.getName());
        assertEquals("secret", group.getPassword());
        assertEquals(new HashSet<>(Arrays.asList("vip.a", "vip.b")), group.getPermissions());
        assertTrue(group.isPersistent());
    }

    @Test
    public void createFlagsNeedTheirOwnPermissionAndShowTheFullNode() {
        run(alice, "create name:Alpha persistent:true");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.flag.persistent")), alice.texts());
        assertNull(manager.groupOf(alice.id()));
        assertTrue(manager.groups().isEmpty());

        clear(alice);
        run(alice, "create permissions:vip");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.flag.permissions")), alice.texts());

        clear(alice);
        alice.deny("pv.addon.groups.flag.name");
        run(alice, "create name:Alpha");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.flag.name")), alice.texts());
    }

    @Test
    public void createNeedsTheCreatePermission() {
        alice.deny("pv.addon.groups.create");
        run(alice, "create");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.create")), alice.texts());
        assertTrue(manager.groups().isEmpty());
    }

    @Test
    public void createRejectsBadNameLengths() {
        run(alice, "create name:ab");
        assertEquals(Collections.singletonList(msg("error.name_length", 3, 32)), alice.texts());
        clear(alice);
        run(alice, "create name:" + new String(new char[33]).replace('\0', 'x'));
        assertEquals(Collections.singletonList(msg("error.name_length", 3, 32)), alice.texts());
        clear(alice);
        run(alice, "create name:");
        assertEquals(Collections.singletonList(msg("error.name_length", 3, 32)), alice.texts());
        assertTrue(manager.groups().isEmpty());
    }

    @Test
    public void createChecksTheDefaultNameLengthToo() {
        server.settings = new GroupsSettings(true, 10, 10, "%player%'s group", 3, 8);
        run(alice, "create");
        assertEquals(Collections.singletonList(msg("error.name_length", 3, 8)), alice.texts());
        assertTrue(manager.groups().isEmpty());
    }

    @Test
    public void emptyPasswordCreatesAnOpenGroup() {
        run(alice, "create name:Alpha password:");
        assertNull(manager.groupOf(alice.id()).getPassword());
    }

    @Test
    public void passwordIsOneWord() {
        run(alice, "create name:Alpha password:one two");
        assertEquals("one", manager.groupOf(alice.id()).getPassword());
    }

    @Test
    public void invalidPersistentValueIsAnError() {
        run(op, "create name:Alpha persistent:maybe");
        assertEquals(Collections.singletonList(msg("command.set.error.invalid_value", "maybe")), op.texts());
        assertTrue(manager.groups().isEmpty());
    }

    @Test
    public void emptyPermissionEntriesMeanNoFilter() {
        run(op, "create name:Alpha permissions:,");
        assertTrue(manager.groupOf(op.id()).getPermissions().isEmpty());
    }

    @Test
    public void consoleCreatesAnOwnerlessGroup() {
        FakeServer.Source console = server.console();
        run(console, "create name:Lobby");

        Group group = manager.groups().iterator().next();
        assertEquals("Lobby", group.getName());
        assertNull(group.getOwner());
        assertTrue(group.getMembers().isEmpty());
        assertEquals(msg("command.create.success", "Lobby"), console.texts().get(0));
        // the console is not a member, so it gets the join button
        assertEquals(msg("button.join"), console.texts().get(console.received.size() - 2));
    }

    @Test
    public void consoleCreateUsesServerAsTheDefaultPlayerName() {
        run(server.console(), "create");
        assertEquals("Server's group", manager.groups().iterator().next().getName());
    }

    @Test
    public void createLeavesTheOldGroupAndTellsIt() {
        Group first = created(alice, "Alpha");
        joined(bob, first);

        run(bob, "create name:Beta");

        assertNotSame(first, manager.groupOf(bob.id()));
        assertEquals("Beta", manager.groupOf(bob.id()).getName());
        assertFalse(first.getMembers().contains(bob.id()));
        assertEquals(1, alice.count(notice("Alpha", "notifications.player_left", "Bob")));
    }

    private static void assertNotSame(Object a, Object b) {
        assertFalse(a == b);
    }

    // ---- join ----

    @Test
    public void joinUsageAndUuidErrors() {
        run(bob, "join");
        run(bob, "join not-a-uuid");
        assertEquals(Arrays.asList(msg("command.join.error.usage"), msg("error.uuid_parse", "not-a-uuid")), bob.texts());
    }

    @Test
    public void joinUnknownGroup() {
        run(bob, "join " + UUID.randomUUID());
        assertEquals(Collections.singletonList(msg("error.group_not_found")), bob.texts());
    }

    @Test
    public void joinNeedsThePermissionAndAPlayer() {
        Group group = created(alice, "Alpha");
        bob.deny("pv.addon.groups.join");
        run(bob, "join " + group.getId());
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.join")), bob.texts());

        FakeServer.Source console = server.console();
        run(console, "join " + group.getId());
        assertEquals(Collections.singletonList("pv.error.player_only_command"), console.texts());
    }

    @Test
    public void joinOpenGroupNotifiesMembersAndConfirms() {
        Group group = created(alice, "Alpha");
        clear(alice);

        run(bob, "join " + group.getId());

        assertSame(group, manager.groupOf(bob.id()));
        assertEquals(Collections.singletonList(msg("command.join.success", "Alpha")), bob.texts());
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.player_join", "Bob")), alice.texts());
    }

    @Test
    public void joinTwiceIsRejected() {
        Group group = created(alice, "Alpha");
        run(alice, "join " + group.getId());
        assertEquals(msg("command.join.error.already_joined"), alice.texts().get(alice.received.size() - 1));
    }

    @Test
    public void joinBannedPlayerIsRejected() {
        Group group = created(alice, "Alpha");
        manager.ban(group, server.onlinePlayer("Bob"));
        clear(alice, bob);

        run(bob, "join " + group.getId());

        assertEquals(Collections.singletonList(msg("command.join.error.banned")), bob.texts());
        assertNull(manager.groupOf(bob.id()));
    }

    @Test
    public void joinPasswordProtectedGroup() {
        run(alice, "create name:Alpha password:secret");
        Group group = manager.groupOf(alice.id());

        run(bob, "join " + group.getId());
        run(bob, "join " + group.getId() + " wrong");
        assertEquals(Arrays.asList(msg("command.join.error.password_arg_missing"),
                msg("command.join.error.wrong_password")), bob.texts());
        assertNull(manager.groupOf(bob.id()));

        clear(bob);
        run(bob, "join " + group.getId() + " secret");
        assertSame(group, manager.groupOf(bob.id()));
        assertEquals(Collections.singletonList(msg("command.join.success", "Alpha")), bob.texts());
    }

    @Test
    public void joinGroupWithRequiredPermissions() {
        run(op, "create name:Vips permissions:vip.a,vip.b");
        Group group = manager.groupOf(op.id());

        run(bob, "join " + group.getId());
        assertEquals(Collections.singletonList(msg("command.join.error.no_permission")), bob.texts());
        assertNull(manager.groupOf(bob.id()));

        bob.grant("vip.b");
        run(bob, "join " + group.getId());
        assertSame(group, manager.groupOf(bob.id()));
    }

    @Test
    public void joinAllBypassesBansPasswordsAndPermissions() {
        run(op, "create name:Vips password:secret permissions:vip.a");
        Group group = manager.groupOf(op.id());
        manager.ban(group, server.onlinePlayer("Bob"));

        bob.grant("pv.addon.groups.join.all");
        run(bob, "join " + group.getId() + " wrong");

        assertSame(group, manager.groupOf(bob.id()));
    }

    @Test
    public void joinWildcardsBypassToo() {
        run(op, "create name:Vips password:secret");
        Group group = manager.groupOf(op.id());
        alice.grant("pv.addon.groups.*");
        run(alice, "join " + group.getId());
        assertSame(group, manager.groupOf(alice.id()));

        bob.grant("pv.addon.groups.join.*");
        run(bob, "join " + group.getId());
        assertSame(group, manager.groupOf(bob.id()));
    }

    @Test
    public void failedJoinKeepsTheOldGroup() {
        Group home = created(alice, "Home");
        joined(bob, home);
        run(op, "create name:Locked password:secret");
        Group locked = manager.groupOf(op.id());
        clear(alice, bob, op);

        run(bob, "join " + locked.getId() + " nope");

        assertSame(home, manager.groupOf(bob.id()));
        assertEquals(Collections.singletonList(msg("command.join.error.wrong_password")), bob.texts());
        assertTrue(alice.received.isEmpty());
        assertTrue(op.received.isEmpty());
    }

    @Test
    public void successfulJoinMovesBetweenGroupsAndNotifiesBoth() {
        Group home = created(alice, "Home");
        joined(bob, home);
        run(op, "create name:Other");
        Group other = manager.groupOf(op.id());
        clear(alice, bob, op);

        run(bob, "join " + other.getId());

        assertSame(other, manager.groupOf(bob.id()));
        assertFalse(home.getMembers().contains(bob.id()));
        assertEquals(Collections.singletonList(notice("Home", "notifications.player_left", "Bob")), alice.texts());
        assertEquals(Collections.singletonList(notice("Other", "notifications.player_join", "Bob")), op.texts());
        assertEquals(Collections.singletonList(msg("command.join.success", "Other")), bob.texts());
    }

    // ---- leave ----

    @Test
    public void leaveConfirmsAndNotifiesTheRest() {
        Group group = created(alice, "Alpha");
        joined(bob, group);

        run(bob, "leave");

        assertNull(manager.groupOf(bob.id()));
        assertEquals(Collections.singletonList(msg("command.leave.success", "Alpha")), bob.texts());
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.player_left", "Bob")), alice.texts());
    }

    @Test
    public void leaveOutsideAGroupOrAsConsole() {
        run(bob, "leave");
        FakeServer.Source console = server.console();
        run(console, "leave");
        assertEquals(Collections.singletonList(msg("error.not_in_group")), bob.texts());
        assertEquals(Collections.singletonList("pv.error.player_only_command"), console.texts());
    }

    @Test
    public void leaveNeedsThePermission() {
        created(alice, "Alpha");
        alice.deny("pv.addon.groups.leave");
        clear(alice);
        run(alice, "leave");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.leave")), alice.texts());
        assertNotNull(manager.groupOf(alice.id()));
    }

    @Test
    public void unknownSubcommandListsTheSubcommandsInOrder() {
        run(alice, "frobnicate");
        assertEquals(Collections.singletonList(msg("error.unknown_subcommand",
                "browse, create, join, leave, invite, info, set, unset, delete, transfer, kick, ban, unban")),
                alice.texts());
    }
}
