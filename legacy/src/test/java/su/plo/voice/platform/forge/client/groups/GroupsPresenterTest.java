package su.plo.voice.platform.forge.client.groups;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.Reason;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.Screen;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.Status;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.View;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.network.groups.GroupsState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GroupsPresenterTest {
    private static final UUID SELF = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAT = new UUID(0, 3);
    private static final UUID DAN = new UUID(0, 4);
    private static final UUID GROUP = new UUID(1, 1);

    private static final int ALL_PLAYER = GroupsState.CAN_BROWSE | GroupsState.CAN_CREATE | GroupsState.CAN_JOIN
            | GroupsState.CAN_CREATE_NAME | GroupsState.CAN_CREATE_PASSWORD | GroupsState.CAN_CREATE_PERSISTENT
            | GroupsState.CAN_CREATE_PERMISSIONS;
    private static final int OWNER = GroupsState.CAN_LEAVE | GroupsState.CAN_INVITE | GroupsState.CAN_KICK
            | GroupsState.CAN_BAN | GroupsState.CAN_UNBAN | GroupsState.CAN_TRANSFER | GroupsState.CAN_DELETE
            | GroupsState.CAN_SET_NAME | GroupsState.CAN_SET_PASSWORD | GroupsState.CAN_SET_PERSISTENT
            | GroupsState.CAN_SET_PERMISSIONS;

    private static GroupsState.Member member(UUID id, String name, boolean online) {
        return new GroupsState.Member(id, name, online);
    }

    private static GroupsState browse(int caps, GroupsState.BrowseEntry... entries) {
        return new GroupsState(caps, 3, 16, null, Arrays.asList(entries), entries.length);
    }

    private static GroupsState.BrowseEntry entry(String name, boolean password, boolean joinable) {
        return new GroupsState.BrowseEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, "Owner", 2, 4, password, false,
                joinable);
    }

    private static GroupsState inGroup(int groupCaps, UUID ownerId, boolean password, GroupsState.Member... members) {
        return inGroup(groupCaps, ownerId, password, Collections.<GroupsState.Member>emptyList(), members);
    }

    private static GroupsState inGroup(int groupCaps, UUID ownerId, boolean password, List<GroupsState.Member> banned,
                                       GroupsState.Member... members) {
        GroupsState.CurrentGroup group = new GroupsState.CurrentGroup(GROUP, "Team", ownerId, null, password, false,
                Arrays.asList("a.b", "c.d"), Arrays.asList(members), banned, groupCaps);
        return new GroupsState(ALL_PLAYER, 3, 16, group, Collections.<GroupsState.BrowseEntry>emptyList(), 0);
    }

    private static View view(GroupsState state, boolean busy) {
        return GroupsPresenter.build(true, state, SELF, busy, Collections.<UUID>emptySet(), false,
                Collections.<UUID, String>emptyMap());
    }

    @Test
    public void serverWithoutGroupsIsUnavailable() {
        View view = GroupsPresenter.build(false, browse(ALL_PLAYER), SELF, false, null, false, null);
        assertEquals(Screen.UNAVAILABLE, view.screen);
        assertFalse(view.create.enabled);
        assertTrue(view.groups.isEmpty());
    }

    @Test
    public void beforeFirstSnapshotIsLoading() {
        View view = GroupsPresenter.build(true, null, SELF, false, null, false, null);
        assertEquals(Screen.LOADING, view.screen);
        assertNull(view.group);
        assertFalse(view.create.enabled);
    }

    @Test
    public void createDisabledWithoutCapability() {
        View view = view(browse(GroupsState.CAN_BROWSE | GroupsState.CAN_JOIN), false);
        assertEquals(Screen.BROWSE, view.screen);
        assertFalse(view.create.enabled);
        assertEquals(Reason.NO_PERMISSION, view.create.reason);
    }

    @Test
    public void createFieldCapabilitiesAreIndependent() {
        View view = view(browse(GroupsState.CAN_CREATE | GroupsState.CAN_CREATE_NAME), false);
        assertTrue(view.create.enabled);
        assertTrue(view.createName.enabled);
        assertFalse(view.createPassword.enabled);
        assertFalse(view.createPersistent.enabled);
        assertEquals(Reason.NO_PERMISSION, view.createPermissions.reason);
        assertEquals(16, view.nameMax);
        assertEquals(3, view.nameMin);
    }

    @Test
    public void missingNameLimitFallsBackToDefault() {
        View view = view(new GroupsState(ALL_PLAYER, 0, 0, null, Collections.<GroupsState.BrowseEntry>emptyList(), 0), false);
        assertEquals(GroupsPresenter.DEFAULT_NAME_MAX, view.nameMax);
    }

    @Test
    public void busyDisablesCreateAndJoin() {
        View view = view(browse(ALL_PLAYER, entry("A", false, true)), true);
        assertEquals(Reason.BUSY, view.create.reason);
        assertEquals(Reason.BUSY, view.groups.get(0).join.reason);
    }

    @Test
    public void joinStatesAndPasswordRequirement() {
        View view = view(browse(ALL_PLAYER, entry("Open", false, true), entry("Locked", true, true),
                entry("Banned", false, false)), false);
        assertTrue(view.groups.get(0).join.enabled);
        assertFalse(view.groups.get(0).needsPassword());
        assertTrue(view.groups.get(1).join.enabled);
        assertTrue(view.groups.get(1).needsPassword());
        assertEquals(Reason.NOT_JOINABLE, view.groups.get(2).join.reason);
        assertNotNull(view.groupRow(view.groups.get(1).id));
        assertNull(view.groupRow(UUID.randomUUID()));
    }

    @Test
    public void joinDisabledWithoutJoinCapability() {
        View view = view(browse(GroupsState.CAN_BROWSE | GroupsState.CAN_CREATE, entry("A", false, true)), false);
        assertEquals(Reason.NO_PERMISSION, view.groups.get(0).join.reason);
    }

    @Test
    public void emptyAndDeniedBrowse() {
        View empty = view(browse(ALL_PLAYER), false);
        assertTrue(empty.emptyBrowse());
        View denied = view(browse(GroupsState.CAN_CREATE), false);
        assertFalse(denied.canBrowse);
        assertFalse(denied.emptyBrowse());
    }

    @Test
    public void truncatedBrowseIsReported() {
        GroupsState state = new GroupsState(ALL_PLAYER, 3, 16, null, Arrays.asList(entry("A", false, true)), 50);
        View view = view(state, false);
        assertTrue(view.browseTruncated());
        assertEquals(50, view.browseTotal);
    }

    @Test
    public void ownerSeesEveryControlAndMembersAreOrdered() {
        GroupsState state = inGroup(OWNER, SELF, false, member(DAN, "dan", false), member(BOB, "bob", true),
                member(SELF, "Zed", true), member(CAT, "Cat", true));
        View view = view(state, false);
        assertEquals(Screen.GROUP, view.screen);
        GroupsPresenter.GroupView group = view.group;
        assertTrue(group.selfIsOwner);
        assertTrue(group.showSettings());
        assertTrue(group.showTransfer());
        assertTrue(group.showDelete());
        assertTrue(group.leave.enabled);
        assertEquals(Arrays.asList("Zed", "bob", "Cat", "dan"), names(group));
        assertTrue(group.members.get(0).owner);
        assertTrue(group.members.get(0).self);
    }

    private static List<String> names(GroupsPresenter.GroupView group) {
        List<String> names = new ArrayList<>();
        for (GroupsPresenter.MemberRow row : group.members) names.add(row.name);
        return names;
    }

    @Test
    public void plainMemberHasNoOwnerControls() {
        GroupsState state = inGroup(GroupsState.CAN_LEAVE, BOB, false, member(BOB, "bob", true), member(SELF, "me", true));
        GroupsPresenter.GroupView group = view(state, false).group;
        assertFalse(group.selfIsOwner);
        assertFalse(group.showSettings());
        assertFalse(group.showTransfer());
        assertFalse(group.showDelete());
        assertEquals(Reason.NO_PERMISSION, group.invite.reason);
        assertTrue(group.leave.enabled);
        assertEquals(Reason.NO_PERMISSION, group.members.get(0).kick.reason);
    }

    @Test
    public void kickAndBanRulesForSelfOfflineAndOnline() {
        GroupsState state = inGroup(OWNER, SELF, false, member(SELF, "me", true), member(BOB, "bob", true),
                member(DAN, "dan", false));
        Map<String, GroupsPresenter.MemberRow> rows = new LinkedHashMap<>();
        for (GroupsPresenter.MemberRow row : view(state, false).group.members) rows.put(row.name, row);
        assertEquals(Reason.SELF, rows.get("me").kick.reason);
        assertEquals(Reason.SELF, rows.get("me").ban.reason);
        assertTrue(rows.get("bob").kick.enabled);
        assertTrue(rows.get("bob").ban.enabled);
        assertEquals(Reason.OFFLINE, rows.get("dan").kick.reason);
        assertEquals(Reason.OFFLINE, rows.get("dan").ban.reason);
        assertFalse(rows.get("dan").online);
    }

    @Test
    public void busyDisablesGroupActionsButKeepsThemVisible() {
        GroupsState state = inGroup(OWNER, SELF, false, member(SELF, "me", true), member(BOB, "bob", true));
        GroupsPresenter.GroupView group = view(state, true).group;
        assertEquals(Reason.BUSY, group.leave.reason);
        assertEquals(Reason.BUSY, group.delete.reason);
        assertTrue(group.showDelete());
        assertTrue(group.showSettings());
        assertEquals(Reason.BUSY, group.members.get(1).kick.reason);
    }

    @Test
    public void settingsCapabilitiesAreIndependent() {
        GroupsState state = inGroup(GroupsState.CAN_LEAVE | GroupsState.CAN_SET_NAME, SELF, true,
                member(SELF, "me", true));
        GroupsPresenter.GroupView group = view(state, false).group;
        assertTrue(group.showSettings());
        assertTrue(group.rename.enabled);
        assertEquals(Reason.NO_PERMISSION, group.setPassword.reason);
        assertEquals(Reason.NO_PERMISSION, group.setPersistent.reason);
        assertEquals(Reason.NO_PERMISSION, group.setPermissions.reason);
    }

    @Test
    public void clearPasswordNeedsAPassword() {
        GroupsState without = inGroup(OWNER, SELF, false, member(SELF, "me", true));
        assertEquals(Reason.NO_PASSWORD, view(without, false).group.clearPassword.reason);
        assertTrue(view(without, false).group.setPassword.enabled);
        GroupsState with = inGroup(OWNER, SELF, true, member(SELF, "me", true));
        assertTrue(view(with, false).group.clearPassword.enabled);
        assertTrue(view(with, false).group.hasPassword);
    }

    @Test
    public void inviteCandidatesExcludeSelfAndMembers() {
        GroupsState state = inGroup(OWNER, SELF, false, member(SELF, "me", true), member(BOB, "bob", false));
        Map<UUID, String> players = new LinkedHashMap<>();
        players.put(SELF, "me");
        players.put(BOB, "bob");
        players.put(DAN, "dan");
        players.put(CAT, "Cat");
        GroupsPresenter.GroupView group = GroupsPresenter.build(true, state, SELF, false,
                Collections.<UUID>emptySet(), false, players).group;
        assertEquals(2, group.inviteCandidates.size());
        assertEquals("Cat", group.inviteCandidates.get(0).name);
        assertEquals("dan", group.inviteCandidates.get(1).name);
        assertTrue(group.invite.enabled);
    }

    @Test
    public void inviteDisabledWithoutCandidates() {
        GroupsState state = inGroup(OWNER, SELF, false, member(SELF, "me", true));
        GroupsPresenter.GroupView group = view(state, false).group;
        assertEquals(Reason.NO_CANDIDATES, group.invite.reason);
    }

    @Test
    public void transferCandidatesAreOnlineOthers() {
        GroupsState state = inGroup(OWNER, SELF, false, member(SELF, "me", true), member(BOB, "bob", true),
                member(DAN, "dan", false));
        GroupsPresenter.GroupView group = view(state, false).group;
        assertEquals(1, group.transferCandidates.size());
        assertEquals(BOB, group.transferCandidates.get(0).id);
        assertTrue(group.transfer.enabled);
        GroupsState alone = inGroup(OWNER, SELF, false, member(SELF, "me", true), member(DAN, "dan", false));
        assertEquals(Reason.NO_CANDIDATES, view(alone, false).group.transfer.reason);
        assertTrue(view(alone, false).group.showTransfer());
    }

    @Test
    public void bannedListIsSortedAndUnbanFollowsCapability() {
        GroupsState state = inGroup(OWNER, SELF, false, Arrays.asList(member(DAN, "dan", false), member(BOB, "Bob", true)),
                member(SELF, "me", true));
        GroupsPresenter.GroupView group = view(state, false).group;
        assertTrue(group.showUnban());
        assertEquals("Bob", group.banned.get(0).name);
        assertTrue(group.banned.get(0).unban.enabled);
        GroupsState noUnban = inGroup(GroupsState.CAN_LEAVE, SELF, false, Arrays.asList(member(DAN, "dan", false)),
                member(SELF, "me", true));
        assertEquals(Reason.NO_PERMISSION, view(noUnban, false).group.banned.get(0).unban.reason);
    }

    @Test
    public void speakingFlagsFollowAudioAndOnlineState() {
        GroupsState state = inGroup(OWNER, SELF, false, member(SELF, "me", true), member(BOB, "bob", true),
                member(DAN, "dan", false));
        Set<UUID> speaking = new HashSet<>(Arrays.asList(BOB, DAN));
        GroupsPresenter.GroupView group = GroupsPresenter.build(true, state, SELF, false, speaking, true,
                Collections.<UUID, String>emptyMap()).group;
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (GroupsPresenter.MemberRow row : group.members) flags.put(row.name, row.speaking);
        assertTrue(flags.get("me"));
        assertTrue(flags.get("bob"));
        assertFalse("offline players never speak", flags.get("dan"));
        assertFalse(GroupsPresenter.isSpeaking(true, true, SELF, speaking, false));
        assertFalse(GroupsPresenter.isSpeaking(true, false, CAT, null, true));
    }

    @Test
    public void ownerlessGroupAndOwnerNameFallback() {
        GroupsState ownerless = inGroup(GroupsState.CAN_LEAVE, null, false, member(SELF, "me", true));
        assertNull(view(ownerless, false).group.ownerName);
        GroupsState owned = inGroup(GroupsState.CAN_LEAVE, BOB, false, member(BOB, "bob", true));
        assertEquals("bob", view(owned, false).group.ownerName);
    }

    @Test
    public void newSnapshotReplacesTheModel() {
        View before = view(inGroup(OWNER, SELF, false, member(SELF, "me", true), member(BOB, "bob", true)), false);
        assertEquals(Screen.GROUP, before.screen);
        assertEquals(2, before.group.members.size());
        View afterKick = view(inGroup(OWNER, SELF, false, member(SELF, "me", true)), false);
        assertEquals(1, afterKick.group.members.size());
        View afterLeave = view(browse(ALL_PLAYER, entry("A", false, true)), false);
        assertEquals(Screen.BROWSE, afterLeave.screen);
        assertNull(afterLeave.group);
        assertEquals(1, afterLeave.groups.size());
        View afterOwnerChange = view(inGroup(GroupsState.CAN_LEAVE, BOB, false, member(SELF, "me", true),
                member(BOB, "bob", true)), false);
        assertFalse(afterOwnerChange.group.selfIsOwner);
        assertFalse(afterOwnerChange.group.showSettings());
    }

    @Test
    public void statusPriority() {
        GroupsResult ok = new GroupsResult(1, true, "k", "done");
        GroupsResult bad = new GroupsResult(2, false, "k", "nope");
        assertEquals(Status.NONE, GroupsPresenter.status(null, false, false));
        assertEquals(Status.OK, GroupsPresenter.status(ok, false, false));
        assertEquals(Status.ERROR, GroupsPresenter.status(bad, false, false));
        assertEquals(Status.TIMED_OUT, GroupsPresenter.status(ok, false, true));
        assertEquals(Status.WAITING, GroupsPresenter.status(ok, true, true));
    }

    @Test
    public void stripFormattingRemovesColourCodes() {
        assertEquals("Joined Team", GroupsPresenter.stripFormatting("§aJoined §lTeam§r"));
        assertEquals("", GroupsPresenter.stripFormatting(null));
        assertEquals("x", GroupsPresenter.stripFormatting("x§"));
    }

    @Test
    public void clientGroupsAvailabilityNeedsTheActivation() {
        assertFalse(ClientGroups.available(null));
        assertFalse(ClientGroups.available(Collections.<su.plo.voice.proto.data.audio.capture.VoiceActivation>emptyList()));
    }
}
