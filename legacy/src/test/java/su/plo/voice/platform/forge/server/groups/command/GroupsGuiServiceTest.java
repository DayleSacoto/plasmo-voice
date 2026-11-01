package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.server.groups.Group;

/** The Groups GUI actions go through the same validation as /groups; a hostile client gets nothing extra. */
public class GroupsGuiServiceTest {
    private final GuiHarness h = new GuiHarness();
    private final FakeServer.Source alice = h.player("Alice", false);
    private final FakeServer.Source bob = h.player("Bob", false);
    private final FakeServer.Source carol = h.player("Carol", false);
    private final FakeServer.Source root = h.player("Root", true);

    private static void assertOk(GroupsResult result, String key) {
        assertNotNull(result);
        assertTrue(result.key, result.ok);
        assertEquals(key, result.key);
    }

    private static void assertError(GroupsResult result, String key) {
        assertNotNull(result);
        assertFalse(result.key, result.ok);
        assertEquals(key, result.key);
    }

    private Group createdByAlice(String name) {
        assertOk(h.act(alice, GroupsAction.Type.CREATE, b -> b.name(name)), "command.create.success");
        return h.manager.groupOf(alice.id());
    }

    private Group joined(FakeServer.Source who, Group group) {
        assertOk(h.act(who, GroupsAction.Type.JOIN, b -> b.group(group.getId()).password(group.getPassword())),
                "command.join.success");
        return group;
    }

    private void assertInGroup(Group group, FakeServer.Source who) {
        assertSame(who.name() + " should be in " + group.getName(), group, h.manager.groupOf(who.id()));
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // ---- create / join / leave ----

    @Test
    public void createMakesTheCallerTheOwnerAndAnswersLikeTheCommand() {
        GroupsResult result = h.act(alice, GroupsAction.Type.CREATE, b -> b.name("Alpha"));

        assertOk(result, "command.create.success");
        assertEquals(msg("command.create.success", "Alpha"), result.message);
        Group group = h.manager.groupOf(alice.id());
        assertEquals("Alpha", group.getName());
        assertTrue(group.isOwner(alice.id()));
        assertEquals(group.getId(), h.lastState(alice).current.id);
    }

    @Test
    public void createPersistentOrPermissionsWithoutThePermissionIsRefused() {
        assertError(h.act(alice, GroupsAction.Type.CREATE, b -> b.name("Alpha").persistent(true)),
                "error.no_permission");
        assertError(h.act(alice, GroupsAction.Type.CREATE, b -> b.name("Alpha").permissions("vip")),
                "error.no_permission");
        assertNull(h.manager.groupOf(alice.id()));
        assertTrue(h.manager.groups().isEmpty());

        assertOk(h.act(root, GroupsAction.Type.CREATE, b -> b.name("Rooted").persistent(true).permissions("vip")),
                "command.create.success");
        Group group = h.manager.groupOf(root.id());
        assertTrue(group.isPersistent());
        assertEquals(Collections.singleton("vip"), group.getPermissions());
    }

    @Test
    public void createPasswordIsItsFirstWordAndBadNamesAreRefused() {
        assertOk(h.act(alice, GroupsAction.Type.CREATE, b -> b.name("Alpha").password("  hunter2 and more ")),
                "command.create.success");
        assertEquals("hunter2", h.manager.groupOf(alice.id()).getPassword());

        assertError(h.act(bob, GroupsAction.Type.CREATE, b -> b.name("ab")), "error.name_length");
        assertError(h.act(bob, GroupsAction.Type.CREATE, b -> b.name(repeat('x', 33))), "error.name_length");
        assertError(h.act(bob, GroupsAction.Type.CREATE, b -> b.name("Bad§cName")), "command.set.error.invalid_value");
        assertError(h.act(bob, GroupsAction.Type.CREATE, b -> b.name("Bad\nName")), "command.set.error.invalid_value");
        assertNull(h.manager.groupOf(bob.id()));
    }

    @Test
    public void joinChecksPasswordBanAndExistence() {
        Group group = createdByAlice("Secret");
        h.manager.setPassword(group, "pw");

        assertError(h.act(bob, GroupsAction.Type.JOIN, b -> b.group(group.getId())), "command.join.error.password_arg_missing");
        assertError(h.act(bob, GroupsAction.Type.JOIN, b -> b.group(group.getId()).password("nope")),
                "command.join.error.wrong_password");
        assertNull(h.manager.groupOf(bob.id()));
        assertOk(h.act(bob, GroupsAction.Type.JOIN, b -> b.group(group.getId()).password("pw")), "command.join.success");
        assertInGroup(group, bob);

        assertError(h.act(bob, GroupsAction.Type.JOIN, b -> b.group(group.getId()).password("pw")),
                "command.join.error.already_joined");
        assertError(h.act(carol, GroupsAction.Type.JOIN, b -> b.group(UUID.randomUUID())), "error.group_not_found");
        assertError(h.act(carol, GroupsAction.Type.JOIN), "command.join.error.usage");
    }

    @Test
    public void aBannedPlayerCannotJoinAndKeepsHisOldGroup() {
        Group alpha = createdByAlice("Alpha");
        assertOk(h.act(bob, GroupsAction.Type.CREATE, b -> b.name("Bravo")), "command.create.success");
        Group bravo = h.manager.groupOf(bob.id());
        h.manager.ban(alpha, bob.profile);

        assertError(h.act(bob, GroupsAction.Type.JOIN, b -> b.group(alpha.getId())), "command.join.error.banned");
        assertInGroup(bravo, bob);
    }

    @Test
    public void leaveWorksOnlyInsideAGroup() {
        assertError(h.act(alice, GroupsAction.Type.LEAVE), "error.not_in_group");
        Group group = createdByAlice("Alpha");
        joined(bob, group);

        GroupsResult result = h.act(bob, GroupsAction.Type.LEAVE);
        assertOk(result, "command.leave.success");
        assertEquals(msg("command.leave.success", "Alpha"), result.message);
        assertNull(h.manager.groupOf(bob.id()));
    }

    // ---- invite ----

    @Test
    public void inviteSendsTheInvitationToAnOnlinePlayer() {
        createdByAlice("Alpha");
        bob.clear();

        GroupsResult result = h.act(alice, GroupsAction.Type.INVITE, b -> b.player(bob.id()));

        assertOk(result, "command.invite.success");
        assertEquals(msg("command.invite.success", "Bob"), result.message);
        assertEquals(4, bob.received.size());
    }

    @Test
    public void inviteRefusesSelfOfflineUnknownAndMembers() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);

        assertError(h.act(alice, GroupsAction.Type.INVITE, b -> b.player(alice.id())), "command.invite.error.invite_self");
        assertError(h.act(alice, GroupsAction.Type.INVITE, b -> b.player(UUID.randomUUID())), "error.player_not_found");
        assertError(h.act(alice, GroupsAction.Type.INVITE), "command.invite.error.usage");
        assertError(h.act(alice, GroupsAction.Type.INVITE, b -> b.player(bob.id())), "command.invite.error.already_joined");
        assertError(h.act(carol, GroupsAction.Type.INVITE, b -> b.player(alice.id())), "error.not_in_group");
        h.server.logout(carol);
        assertError(h.act(alice, GroupsAction.Type.INVITE, b -> b.player(carol.id())), "error.player_not_found");
    }

    // ---- update ----

    @Test
    public void ownerUpdatesNameAndPasswordInOneRequest() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);
        bob.clear();

        GroupsResult result = h.act(alice, GroupsAction.Type.UPDATE, b -> b.name("Beta").password("pw second"));

        assertOk(result, "gui.updated");
        assertEquals("Beta", group.getName());
        assertEquals("pw", group.getPassword());
        assertTrue(bob.texts().contains(FakeServer.notice("Beta", "notifications.password_changed")));
    }

    @Test
    public void updateValidatesEveryFieldBeforeApplyingAny() {
        Group group = createdByAlice("Alpha");

        assertError(h.act(alice, GroupsAction.Type.UPDATE, b -> b.name("Gamma").persistent(true)), "error.no_permission");
        assertEquals("Alpha", group.getName());
        assertFalse(group.isPersistent());

        assertError(h.act(alice, GroupsAction.Type.UPDATE, b -> b.password("pw").name("x")), "error.name_length");
        assertNull(group.getPassword());

        assertError(h.act(alice, GroupsAction.Type.UPDATE, b -> b.name("Gamma").permissions("vip")), "error.no_permission");
        assertEquals("Alpha", group.getName());
        assertTrue(group.getPermissions().isEmpty());
    }

    @Test
    public void anOperatorMayUpdatePersistentAndPermissionsAndClearThem() {
        assertOk(h.act(root, GroupsAction.Type.CREATE, b -> b.name("Rooted")), "command.create.success");
        Group group = h.manager.groupOf(root.id());

        assertOk(h.act(root, GroupsAction.Type.UPDATE, b -> b.persistent(true).permissions("vip", "staff")), "gui.updated");
        assertTrue(group.isPersistent());
        assertEquals(2, group.getPermissions().size());

        assertOk(h.act(root, GroupsAction.Type.UPDATE, b -> b.permissions()), "gui.updated");
        assertTrue(group.getPermissions().isEmpty());
    }

    @Test
    public void identicalValuesAreSkippedUnlessEverythingIsIdentical() {
        Group group = createdByAlice("Alpha");

        assertError(h.act(alice, GroupsAction.Type.UPDATE, b -> b.name("Alpha")), "command.set.error.identical_value");
        assertOk(h.act(alice, GroupsAction.Type.UPDATE, b -> b.name("Alpha").password("pw")), "gui.updated");
        assertEquals("pw", group.getPassword());
        assertEquals("Alpha", group.getName());

        assertOk(h.act(alice, GroupsAction.Type.UPDATE, b -> b.clearPassword()), "gui.updated");
        assertNull(group.getPassword());
        assertError(h.act(alice, GroupsAction.Type.UPDATE, b -> b.clearPassword()), "command.unset.error.password_not_set");
        assertError(h.act(alice, GroupsAction.Type.UPDATE), "command.set.error.usage");
    }

    @Test
    public void aMemberCannotUpdateAndPasswordPlusClearIsRejected() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);

        assertError(h.act(bob, GroupsAction.Type.UPDATE, b -> b.name("Hijack")), "error.no_permission");
        assertEquals("Alpha", group.getName());
        assertError(h.act(alice, GroupsAction.Type.UPDATE, b -> b.password("pw").clearPassword()),
                "command.set.error.invalid_value");
        assertError(h.act(carol, GroupsAction.Type.UPDATE, b -> b.name("Nothing")), "error.not_in_group");
    }

    @Test
    public void tighteningThePermissionFilterKicksWhoNoLongerPasses() {
        assertOk(h.act(root, GroupsAction.Type.CREATE, b -> b.name("Rooted")), "command.create.success");
        Group group = h.manager.groupOf(root.id());
        bob.grant("vip");
        joined(bob, group);
        assertOk(h.act(root, GroupsAction.Type.UPDATE, b -> b.permissions("vip")), "gui.updated");
        assertInGroup(group, bob);

        assertOk(h.act(root, GroupsAction.Type.UPDATE, b -> b.permissions("other")), "gui.updated");
        assertNull(h.manager.groupOf(bob.id()));
    }

    // ---- transfer / kick / ban / unban / delete ----

    @Test
    public void ownerTransfersToAnOnlineMemberOnly() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);

        assertError(h.act(alice, GroupsAction.Type.TRANSFER, b -> b.player(carol.id())), "command.transfer.error.not_in_group");
        assertError(h.act(alice, GroupsAction.Type.TRANSFER, b -> b.player(alice.id())), "command.transfer.error.already_owner");
        assertError(h.act(alice, GroupsAction.Type.TRANSFER, b -> b.player(UUID.randomUUID())), "error.player_not_found");
        assertOk(h.act(alice, GroupsAction.Type.TRANSFER, b -> b.player(bob.id())), "gui.done");
        assertTrue(group.isOwner(bob.id()));
    }

    @Test
    public void aMemberCannotTransferDeleteBanOrKick() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);
        joined(carol, group);

        assertError(h.act(bob, GroupsAction.Type.TRANSFER, b -> b.player(bob.id())), "error.no_permission");
        assertError(h.act(bob, GroupsAction.Type.DELETE), "error.no_permission");
        assertError(h.act(bob, GroupsAction.Type.BAN, b -> b.player(carol.id())), "error.no_permission");
        assertError(h.act(bob, GroupsAction.Type.KICK, b -> b.player(carol.id())), "error.no_permission");
        assertError(h.act(bob, GroupsAction.Type.UNBAN, b -> b.player(carol.id())), "error.no_permission");
        assertTrue(group.isOwner(alice.id()));
        assertEquals(3, group.getMembers().size());
        assertSame(group, h.manager.group(group.getId()));
    }

    @Test
    public void outsidersGetNotInGroupForEveryManagementAction() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);
        for (GroupsAction.Type type : new GroupsAction.Type[] {GroupsAction.Type.KICK, GroupsAction.Type.BAN,
                GroupsAction.Type.UNBAN, GroupsAction.Type.TRANSFER, GroupsAction.Type.INVITE}) {
            assertError(h.act(carol, type, b -> b.player(bob.id())), "error.not_in_group");
        }
        assertError(h.act(carol, GroupsAction.Type.DELETE), "error.not_in_group");
        assertEquals(2, group.getMembers().size());
    }

    @Test
    public void kickRemovesAMemberButNotAnOutsiderOrAnOfflinePlayer() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);

        assertError(h.act(alice, GroupsAction.Type.KICK, b -> b.player(carol.id())), "command.kick.error.not_in_group");
        assertError(h.act(alice, GroupsAction.Type.KICK, b -> b.player(UUID.randomUUID())), "error.player_not_found");
        assertOk(h.act(alice, GroupsAction.Type.KICK, b -> b.player(bob.id())), "gui.done");
        assertNull(h.manager.groupOf(bob.id()));
        assertTrue(bob.texts().contains(msg("notifications.kicked")));
        h.service.tick();
        assertNull(h.lastState(bob).current);
    }

    @Test
    public void banUnbanAndTheBannedListSurviveGoingOffline() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);

        assertOk(h.act(alice, GroupsAction.Type.BAN, b -> b.player(bob.id())), "gui.done");
        assertTrue(group.isBanned(bob.id()));
        assertNull(h.manager.groupOf(bob.id()));
        assertError(h.act(alice, GroupsAction.Type.BAN, b -> b.player(bob.id())), "command.ban.error.already_banned");

        h.server.logout(bob);
        h.manager.playerOffline(bob.id());
        h.service.tick();
        assertEquals(1, h.lastState(alice).current.banned.size());
        assertFalse(h.lastState(alice).current.banned.get(0).online);
        assertError(h.act(alice, GroupsAction.Type.UNBAN, b -> b.player(carol.id())), "command.unban.error.not_banned");
        assertOk(h.act(alice, GroupsAction.Type.UNBAN, b -> b.player(bob.id())), "gui.done");
        assertFalse(group.isBanned(bob.id()));
    }

    @Test
    public void banNeedsAnOnlineTarget() {
        createdByAlice("Alpha");
        h.server.logout(carol);
        assertError(h.act(alice, GroupsAction.Type.BAN, b -> b.player(carol.id())), "error.player_not_found");
        assertError(h.act(alice, GroupsAction.Type.BAN), "command.ban.error.usage");
    }

    @Test
    public void deleteRemovesTheGroupAndFormerMembersGetAnEmptyState() {
        Group group = createdByAlice("Alpha");
        joined(bob, group);
        h.service.tick();

        assertOk(h.act(alice, GroupsAction.Type.DELETE), "gui.done");
        h.service.tick();

        assertNull(h.manager.group(group.getId()));
        assertNull(h.lastState(alice).current);
        assertNull(h.lastState(bob).current);
        assertTrue(bob.texts().contains(FakeServer.notice("Alpha", "notifications.group_deleted")));
    }

    // ---- robustness ----

    @Test
    public void notConnectedPlayersAreIgnoredAndNothingIsAnswered() {
        h.connected.remove(alice.id());
        assertNull(h.act(alice, GroupsAction.Type.CREATE, b -> b.name("Alpha")));
        assertTrue(h.manager.groups().isEmpty());
    }

    @Test
    public void actionsAboveTheRateLimitAreDropped() {
        for (int i = 0; i < 20; i++) {
            h.service.handle(alice.id(), GroupsAction.simple(i, GroupsAction.Type.LEAVE));
        }
        assertEquals(8, h.resultsOf(alice).size());

        h.now += 1000;
        h.service.handle(alice.id(), GroupsAction.simple(99, GroupsAction.Type.LEAVE));
        assertEquals(9, h.resultsOf(alice).size());
    }

    @Test
    public void refreshAlwaysSendsTheCurrentState() {
        h.service.connected(alice.id());
        int before = h.statesOf(alice).size();
        h.service.handle(alice.id(), GroupsAction.simple(1, GroupsAction.Type.REFRESH));
        h.service.handle(alice.id(), GroupsAction.simple(2, GroupsAction.Type.REFRESH));
        assertEquals(before + 2, h.statesOf(alice).size());
        assertTrue(h.resultsOf(alice).isEmpty());
    }

    @Test
    public void garbageFieldsNeverCrashAndNeverChangeState() {
        Group group = createdByAlice("Alpha");
        for (GroupsAction.Type type : GroupsAction.Type.values()) {
            if (type == GroupsAction.Type.REFRESH) continue;
            h.now += 1000;
            h.service.handle(carol.id(), new GroupsAction(1, type, new UUID(0, 0), new UUID(0, 0), repeat('\u0000', 60),
                    repeat('p', 64), true, Boolean.TRUE, Arrays.asList("", " ", "§", repeat('n', 120))));
        }
        assertEquals(1, h.manager.groups().size());
        assertEquals("Alpha", group.getName());
        assertEquals(1, group.getMembers().size());
        assertNull(h.manager.groupOf(carol.id()));
    }

    @Test
    public void resultMessageIsTranslatedForTheRequester() {
        h.server.languages.put(bob.id(), "ru_ru");
        GroupsResult result = h.act(bob, GroupsAction.Type.LEAVE);
        assertError(result, "error.not_in_group");
        assertEquals("ru_ru:" + msg("error.not_in_group"), result.message);
    }
}
