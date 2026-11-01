package su.plo.voice.platform.forge.server.groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import su.plo.voice.platform.forge.network.groups.GroupsProtocol;
import su.plo.voice.platform.forge.network.groups.GroupsState;

/**
 * Builds the {@link GroupsState} one player sees. Pure: reads the manager and asks the viewer's permissions through
 * the same {@link GroupRules} the /groups commands and {@link su.plo.voice.platform.forge.server.groups.command.GroupActions}
 * use, so a capability bit is on exactly when the matching action would be accepted. Never includes a password.
 */
public final class GroupsSnapshots {
    private GroupsSnapshots() {
    }

    /**
     * @param isOnline whether a (banned) player is on the server right now
     * @return the state of a viewer without a profile (the console) is just the player-level capabilities
     */
    public static GroupsState build(GroupsManager manager, GroupsSettings settings, GroupActor viewer,
                                    Predicate<UUID> isOnline) {
        int capabilities = 0;
        if (GroupRules.has(viewer, "browse")) capabilities |= GroupsState.CAN_BROWSE;
        if (GroupRules.has(viewer, "create")) capabilities |= GroupsState.CAN_CREATE;
        if (GroupRules.has(viewer, "join")) capabilities |= GroupsState.CAN_JOIN;
        if (GroupRules.hasFlag(viewer, "name")) capabilities |= GroupsState.CAN_CREATE_NAME;
        if (GroupRules.hasFlag(viewer, "password")) capabilities |= GroupsState.CAN_CREATE_PASSWORD;
        if (GroupRules.hasFlag(viewer, "persistent")) capabilities |= GroupsState.CAN_CREATE_PERSISTENT;
        if (GroupRules.hasFlag(viewer, "permissions")) capabilities |= GroupsState.CAN_CREATE_PERMISSIONS;

        GroupProfile self = viewer.profile();
        GroupsState.CurrentGroup current = null;
        if (self != null) {
            Group group = manager.groupOf(self.getId());
            if (group != null) current = current(manager, group, viewer, self, isOnline);
        }

        List<GroupsState.BrowseEntry> browse = new ArrayList<>();
        List<Group> visible = GroupRules.browseVisible(manager.groups(), viewer);
        UUID selfId = self == null ? null : self.getId();
        for (Group group : visible) {
            if (browse.size() >= GroupsProtocol.MAX_BROWSE) break;
            GroupAccess.Result access = GroupAccess.checkJoin(group, selfId, null, viewer::hasPermission);
            boolean joinable = access != GroupAccess.Result.BANNED && access != GroupAccess.Result.NO_PERMISSION;
            GroupProfile owner = group.getOwner();
            browse.add(new GroupsState.BrowseEntry(group.getId(), group.getName(), owner == null ? null : owner.getName(),
                    group.onlineCount(), group.memberCount(), group.getPassword() != null, group.isPersistent(),
                    joinable));
        }
        return new GroupsState(capabilities, settings.getMinimumNameLength(), settings.getMaximumNameLength(), current,
                browse, visible.size());
    }

    private static GroupsState.CurrentGroup current(GroupsManager manager, Group group, GroupActor viewer,
                                                    GroupProfile self, Predicate<UUID> isOnline) {
        boolean owner = group.isOwner(self.getId());
        int capabilities = 0;
        if (GroupRules.has(viewer, "leave")) capabilities |= GroupsState.CAN_LEAVE;
        if (GroupRules.maySee(viewer, "invite", owner)) capabilities |= GroupsState.CAN_INVITE;
        if (GroupRules.mayManage(viewer, "kick", owner)) capabilities |= GroupsState.CAN_KICK;
        if (GroupRules.mayManage(viewer, "ban", owner)) capabilities |= GroupsState.CAN_BAN | GroupsState.CAN_UNBAN;
        if (GroupRules.mayManage(viewer, "transfer", owner)) capabilities |= GroupsState.CAN_TRANSFER;
        if (GroupRules.mayManage(viewer, "delete", owner)) capabilities |= GroupsState.CAN_DELETE;
        if (GroupRules.mayManage(viewer, "set", owner)) {
            if (GroupRules.hasFlag(viewer, "name")) capabilities |= GroupsState.CAN_SET_NAME;
            if (GroupRules.hasFlag(viewer, "password")) capabilities |= GroupsState.CAN_SET_PASSWORD;
            if (GroupRules.hasFlag(viewer, "persistent")) capabilities |= GroupsState.CAN_SET_PERSISTENT;
            if (GroupRules.hasFlag(viewer, "permissions")) capabilities |= GroupsState.CAN_SET_PERMISSIONS;
        }

        Set<UUID> online = group.onlineMembers();
        List<GroupsState.Member> members = new ArrayList<>();
        for (UUID id : group.getMembers()) {
            members.add(new GroupsState.Member(id, manager.nameOf(id), online.contains(id)));
        }
        Collections.sort(members, MEMBER_ORDER);

        List<GroupsState.Member> banned = new ArrayList<>();
        for (GroupProfile profile : group.getBanned()) {
            banned.add(new GroupsState.Member(profile.getId(), profile.getName(), isOnline.test(profile.getId())));
        }

        GroupProfile groupOwner = group.getOwner();
        return new GroupsState.CurrentGroup(group.getId(), group.getName(),
                groupOwner == null ? null : groupOwner.getId(), groupOwner == null ? null : groupOwner.getName(),
                group.getPassword() != null, group.isPersistent(), new ArrayList<>(new TreeSet<>(group.getPermissions())),
                members, banned, capabilities);
    }

    /** Online members first, then by name: the same order for the same state, so unchanged snapshots encode equally. */
    private static final Comparator<GroupsState.Member> MEMBER_ORDER = new Comparator<GroupsState.Member>() {
        @Override
        public int compare(GroupsState.Member a, GroupsState.Member b) {
            if (a.online != b.online) return a.online ? -1 : 1;
            int byName = a.name.compareToIgnoreCase(b.name);
            return byName != 0 ? byName : a.id.compareTo(b.id);
        }
    };
}
