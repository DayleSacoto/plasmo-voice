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

import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.Group;

public class SetUnsetDeleteTest extends CommandTestBase {
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);
    private final FakeServer.Source op = server.player("Root", true);

    private Group aliceGroup() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        return group;
    }

    // ---- ownership and permissions ----

    @Test
    public void setIsRefusedOutsideAGroupAndForConsole() {
        run(alice, "set name Beta");
        FakeServer.Source console = server.console();
        run(console, "set name Beta");
        assertEquals(Collections.singletonList(msg("error.not_in_group")), alice.texts());
        assertEquals(Collections.singletonList("pv.error.player_only_command"), console.texts());
    }

    @Test
    public void memberWithoutAllCannotManageTheGroup() {
        Group group = aliceGroup();

        run(bob, "set name Beta");
        run(bob, "unset password");
        run(bob, "delete");
        run(bob, "transfer Bob");
        run(bob, "kick Alice");
        run(bob, "ban Alice");
        run(bob, "unban Alice");

        assertEquals(Arrays.asList(
                msg("error.no_permission", "pv.addon.groups.set.all"),
                msg("error.no_permission", "pv.addon.groups.unset.all"),
                msg("error.no_permission", "pv.addon.groups.delete.all"),
                msg("error.no_permission", "pv.addon.groups.transfer.all"),
                msg("error.no_permission", "pv.addon.groups.kick.all"),
                msg("error.no_permission", "pv.addon.groups.ban.all"),
                msg("error.no_permission", "pv.addon.groups.ban.all")), bob.texts());
        assertEquals("Alpha", group.getName());
        assertSame(group, manager.group(group.getId()));
        assertTrue(group.isOwner(alice.id()));
    }

    @Test
    public void ownerNeedsTheOwnerNode() {
        aliceGroup();
        alice.deny("pv.addon.groups.set.owner").deny("pv.addon.groups.delete.owner");
        clear(alice);
        run(alice, "set name Beta");
        run(alice, "delete");
        assertEquals(Arrays.asList(msg("error.no_permission", "pv.addon.groups.set.owner"),
                msg("error.no_permission", "pv.addon.groups.delete.owner")), alice.texts());
    }

    @Test
    public void allNodeLetsAMemberManageTheGroup() {
        Group group = aliceGroup();
        bob.grant("pv.addon.groups.set.all");
        run(bob, "set name Beta");
        assertEquals("Beta", group.getName());
    }

    // ---- set ----

    @Test
    public void setUsageUnknownFlagAndFlagPermission() {
        aliceGroup();
        clear(alice);

        run(alice, "set");
        run(alice, "set colour red");
        run(alice, "set permissions vip");
        run(alice, "set name");

        assertEquals(Arrays.asList(
                msg("command.set.error.usage"),
                msg("command.set.error.unknown_flag", "colour", "name, password"),
                msg("error.no_permission", "pv.addon.groups.flag.permissions"),
                msg("command.set.error.usage")), alice.texts());
    }

    @Test
    public void setNameRenamesAndNotifiesEveryone() {
        Group group = aliceGroup();
        clear(alice);

        run(alice, "set name Big Team");

        assertEquals("Big Team", group.getName());
        String expected = notice("Big Team", "notifications.name_change", "Alpha", "Big Team");
        assertEquals(Collections.singletonList(expected), alice.texts());
        assertEquals(Collections.singletonList(expected), bob.texts());
    }

    @Test
    public void setNameRejectsIdenticalAndBadLength() {
        Group group = aliceGroup();
        clear(alice);

        run(alice, "set name Alpha");
        run(alice, "set name ab");

        assertEquals(Arrays.asList(msg("command.set.error.identical_value"), msg("error.name_length", 3, 32)),
                alice.texts());
        assertEquals("Alpha", group.getName());
        assertTrue(bob.received.isEmpty());
    }

    @Test
    public void setPasswordTakesOneWord() {
        Group group = aliceGroup();
        clear(alice);

        run(alice, "set password hunter2 extra words");

        assertEquals("hunter2", group.getPassword());
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.password_changed")), alice.texts());
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.password_changed")), bob.texts());

        clear(alice);
        run(alice, "set password hunter2");
        assertEquals(Collections.singletonList(msg("command.set.error.identical_value")), alice.texts());
    }

    @Test
    public void setPasswordThenJoinWithItWorks() {
        Group group = aliceGroup();
        run(alice, "set password hunter2");
        FakeServer.Source carol = server.player("Carol", false);
        run(carol, "join " + group.getId() + " hunter2");
        assertSame(group, manager.groupOf(carol.id()));
    }

    @Test
    public void setPermissionsKicksMembersWhoNoLongerQualify() {
        Group group = created(op, "Alpha");
        joined(alice, group);
        joined(bob, group);
        alice.grant("vip.a");

        run(op, "set permissions vip.a, vip.b");

        assertEquals(new HashSet<>(Arrays.asList("vip.a", "vip.b")), group.getPermissions());
        assertNull(manager.groupOf(bob.id()));
        assertSame(group, manager.groupOf(alice.id()));
        assertSame(group, manager.groupOf(op.id()));
        assertEquals(1, bob.count(msg("notifications.kicked")));
        assertEquals(1, alice.count(notice("Alpha", "notifications.player_kicked", "Bob")));
        assertEquals(1, alice.count(notice("Alpha", "notifications.permissions_changed")));
        assertEquals(0, bob.count(notice("Alpha", "notifications.player_kicked", "Bob")));
    }

    @Test
    public void setPermissionsRejectsIdenticalAndEmptyValues() {
        Group group = created(op, "Alpha");
        clear(op);

        run(op, "set permissions vip");
        run(op, "set permissions vip");
        run(op, "set permissions ,");

        assertEquals(Arrays.asList(notice("Alpha", "notifications.permissions_changed"),
                msg("command.set.error.identical_value"), msg("command.set.error.invalid_value", ",")), op.texts());
        assertEquals(Collections.singleton("vip"), group.getPermissions());
    }

    @Test
    public void setPersistentIsStrict() {
        Group group = created(op, "Alpha");
        clear(op);

        run(op, "set persistent yes");
        run(op, "set persistent true");
        run(op, "set persistent true");
        run(op, "set persistent false");

        assertEquals(Arrays.asList(msg("command.set.error.invalid_value", "yes"),
                notice("Alpha", "notifications.persistent_true"), msg("command.set.error.identical_value"),
                notice("Alpha", "notifications.persistent_false")), op.texts());
        assertFalse(group.isPersistent());
    }

    @Test
    public void setPersistentNeedsItsFlagPermission() {
        created(alice, "Alpha");
        clear(alice);
        run(alice, "set persistent true");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.flag.persistent")), alice.texts());
    }

    // ---- unset ----

    @Test
    public void unsetPasswordAndPermissions() {
        Group group = created(op, "Alpha");
        run(op, "set password pw");
        run(op, "set permissions vip");
        clear(op);

        run(op, "unset password");
        run(op, "unset permissions");

        assertNull(group.getPassword());
        assertTrue(group.getPermissions().isEmpty());
        assertEquals(Arrays.asList(notice("Alpha", "notifications.password_unset"),
                notice("Alpha", "notifications.permissions_unset")), op.texts());
    }

    @Test
    public void unsetUsesTheUnsetErrorKeys() {
        created(op, "Alpha");
        clear(op);

        run(op, "unset");
        run(op, "unset name");
        run(op, "unset password");
        run(op, "unset permissions");

        assertEquals(Arrays.asList(msg("command.unset.error.usage"),
                msg("command.unset.error.unknown_flag", "name", "password, permissions"),
                msg("command.unset.error.password_not_set"), msg("command.unset.error.permissions_not_set")),
                op.texts());
    }

    @Test
    public void unsetNeedsTheFlagPermission() {
        created(alice, "Alpha");
        clear(alice);
        alice.deny("pv.addon.groups.flag.password");
        run(alice, "unset password");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.flag.password")), alice.texts());
    }

    // ---- delete ----

    @Test
    public void ownerDeletesTheGroupAndEveryoneIsTold() {
        Group group = aliceGroup();
        clear(alice);

        run(alice, "delete");

        assertNull(manager.group(group.getId()));
        assertNull(manager.groupOf(alice.id()));
        assertNull(manager.groupOf(bob.id()));
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.group_deleted")), alice.texts());
        assertEquals(Collections.singletonList(notice("Alpha", "notifications.group_deleted")), bob.texts());
    }

    @Test
    public void allNodeLetsANonOwnerDelete() {
        Group group = aliceGroup();
        bob.grant("pv.addon.groups.delete.all");
        run(bob, "delete");
        assertNull(manager.group(group.getId()));
        assertNotNull(group);
    }
}
