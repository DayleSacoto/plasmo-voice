package su.plo.voice.platform.forge.client.groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.network.groups.GroupsState;

/**
 * Pure presentation logic of the Groups tab: which section is shown, which controls are enabled and why, how members
 * are ordered and flagged. No Minecraft types, so it is unit tested. The server stays authoritative; capability bits
 * only decide whether a control is enabled.
 */
public final class GroupsPresenter {
    /** Used when the server did not send a name limit. */
    public static final int DEFAULT_NAME_MAX = 32;

    private GroupsPresenter() {
    }

    public enum Screen {
        /** The server does not offer groups. */
        UNAVAILABLE,
        /** No snapshot received yet. */
        LOADING,
        /** Not in a group: create and browse. */
        BROWSE,
        /** In a group. */
        GROUP
    }

    /** Why a control is disabled; the GUI maps it to a tooltip. */
    public enum Reason {
        NONE,
        BUSY,
        NO_PERMISSION,
        NOT_JOINABLE,
        SELF,
        OFFLINE,
        NO_CANDIDATES,
        NO_PASSWORD
    }

    public enum Status {
        NONE,
        WAITING,
        TIMED_OUT,
        OK,
        ERROR
    }

    public static final class Control {
        public static final Control ON = new Control(true, Reason.NONE);

        public final boolean enabled;
        public final Reason reason;

        private Control(boolean enabled, Reason reason) {
            this.enabled = enabled;
            this.reason = reason;
        }

        public static Control off(Reason reason) {
            return new Control(false, reason);
        }

        /** A permission check first (that reason is the more useful one), then the busy state. */
        static Control cap(boolean allowed, boolean busy) {
            if (!allowed) return off(Reason.NO_PERMISSION);
            return busy ? off(Reason.BUSY) : ON;
        }
    }

    public static final class GroupRow {
        public final UUID id;
        public final String name;
        public final String ownerName;
        public final int online;
        public final int members;
        public final boolean hasPassword;
        public final boolean persistent;
        public final boolean joinable;
        public final Control join;

        GroupRow(GroupsState.BrowseEntry entry, Control join) {
            this.id = entry.id;
            this.name = entry.name;
            this.ownerName = entry.ownerName;
            this.online = entry.online;
            this.members = entry.members;
            this.hasPassword = entry.hasPassword;
            this.persistent = entry.persistent;
            this.joinable = entry.joinable;
            this.join = join;
        }

        /** Joining a password group asks for the password first. */
        public boolean needsPassword() {
            return hasPassword;
        }
    }

    public static final class MemberRow {
        public final UUID id;
        public final String name;
        public final boolean online;
        public final boolean speaking;
        public final boolean self;
        public final boolean owner;
        public final Control kick;
        public final Control ban;

        MemberRow(UUID id, String name, boolean online, boolean speaking, boolean self, boolean owner, Control kick,
                  Control ban) {
            this.id = id;
            this.name = name;
            this.online = online;
            this.speaking = speaking;
            this.self = self;
            this.owner = owner;
            this.kick = kick;
            this.ban = ban;
        }
    }

    public static final class BannedRow {
        public final UUID id;
        public final String name;
        public final Control unban;

        BannedRow(UUID id, String name, Control unban) {
            this.id = id;
            this.name = name;
            this.unban = unban;
        }
    }

    /** A player that can be picked from a list. */
    public static final class Candidate {
        public final UUID id;
        public final String name;

        Candidate(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class GroupView {
        public final UUID id;
        public final String name;
        /** Null for an ownerless group. */
        public final String ownerName;
        public final boolean selfIsOwner;
        public final boolean hasPassword;
        public final boolean persistent;
        public final List<String> permissions;
        public final List<MemberRow> members;
        public final List<BannedRow> banned;
        public final List<Candidate> inviteCandidates;
        public final List<Candidate> transferCandidates;
        public final Control leave;
        public final Control invite;
        public final Control transfer;
        public final Control delete;
        public final Control rename;
        public final Control setPassword;
        public final Control clearPassword;
        public final Control setPersistent;
        public final Control setPermissions;

        GroupView(GroupsState.CurrentGroup group, boolean selfIsOwner, List<MemberRow> members, List<BannedRow> banned,
                  List<Candidate> inviteCandidates, List<Candidate> transferCandidates, boolean busy) {
            this.id = group.id;
            this.name = group.name;
            this.ownerName = ownerName(group);
            this.selfIsOwner = selfIsOwner;
            this.hasPassword = group.hasPassword;
            this.persistent = group.persistent;
            this.permissions = group.permissions;
            this.members = members;
            this.banned = banned;
            this.inviteCandidates = inviteCandidates;
            this.transferCandidates = transferCandidates;
            this.leave = Control.cap(group.can(GroupsState.CAN_LEAVE), busy);
            Control invite = Control.cap(group.can(GroupsState.CAN_INVITE), busy);
            this.invite = invite.enabled && inviteCandidates.isEmpty() ? Control.off(Reason.NO_CANDIDATES) : invite;
            Control transfer = Control.cap(group.can(GroupsState.CAN_TRANSFER), busy);
            this.transfer = transfer.enabled && transferCandidates.isEmpty() ? Control.off(Reason.NO_CANDIDATES) : transfer;
            this.delete = Control.cap(group.can(GroupsState.CAN_DELETE), busy);
            this.rename = Control.cap(group.can(GroupsState.CAN_SET_NAME), busy);
            this.setPassword = Control.cap(group.can(GroupsState.CAN_SET_PASSWORD), busy);
            Control clear = Control.cap(group.can(GroupsState.CAN_SET_PASSWORD), busy);
            this.clearPassword = clear.enabled && !group.hasPassword ? Control.off(Reason.NO_PASSWORD) : clear;
            this.setPersistent = Control.cap(group.can(GroupsState.CAN_SET_PERSISTENT), busy);
            this.setPermissions = Control.cap(group.can(GroupsState.CAN_SET_PERMISSIONS), busy);
        }

        /** Whether the player may change any group setting; the settings section is hidden otherwise. */
        public boolean showSettings() {
            return held(rename) || held(setPassword) || held(setPersistent) || held(setPermissions);
        }

        /** Owner-only actions are hidden for players without the permission instead of shown disabled. */
        public boolean showTransfer() {
            return held(transfer);
        }

        public boolean showDelete() {
            return held(delete);
        }

        public boolean showUnban() {
            return !banned.isEmpty();
        }

        /** The player holds the permission (the control may still be disabled for being busy). */
        private static boolean held(Control control) {
            return control.enabled || control.reason != Reason.NO_PERMISSION;
        }
    }

    public static final class View {
        public final Screen screen;
        public final boolean busy;
        public final int nameMin;
        public final int nameMax;
        public final Control create;
        public final Control createName;
        public final Control createPassword;
        public final Control createPersistent;
        public final Control createPermissions;
        public final List<GroupRow> groups;
        public final int browseTotal;
        /** The player may see the group list at all. */
        public final boolean canBrowse;
        /** Null unless {@link #screen} is {@link Screen#GROUP}. */
        public final GroupView group;

        View(Screen screen, boolean busy, int nameMin, int nameMax, Control create, Control createName,
             Control createPassword, Control createPersistent, Control createPermissions, List<GroupRow> groups,
             int browseTotal, boolean canBrowse, GroupView group) {
            this.canBrowse = canBrowse;
            this.screen = screen;
            this.busy = busy;
            this.nameMin = nameMin;
            this.nameMax = nameMax;
            this.create = create;
            this.createName = createName;
            this.createPassword = createPassword;
            this.createPersistent = createPersistent;
            this.createPermissions = createPermissions;
            this.groups = groups;
            this.browseTotal = browseTotal;
            this.group = group;
        }

        /** Loaded, not in a group, allowed to browse and nothing to join. */
        public boolean emptyBrowse() {
            return screen == Screen.BROWSE && canBrowse && groups.isEmpty();
        }

        /** The server has more visible groups than it sent. */
        public boolean browseTruncated() {
            return browseTotal > groups.size();
        }

        public GroupRow groupRow(UUID id) {
            for (GroupRow row : groups) {
                if (row.id.equals(id)) return row;
            }
            return null;
        }
    }

    /**
     * @param available    the server offers groups ({@link ClientGroups#available})
     * @param state        the latest snapshot, null before the first one
     * @param self         the server id of the local player
     * @param busy         an action waits for its result
     * @param speaking     players producing group audio now
     * @param selfSpeaking the local microphone feeds the groups activation
     * @param voicePlayers all voice players of the server, id to name
     */
    public static View build(boolean available, GroupsState state, UUID self, boolean busy, Set<UUID> speaking,
                             boolean selfSpeaking, Map<UUID, String> voicePlayers) {
        if (!available) return empty(Screen.UNAVAILABLE);
        if (state == null) return empty(Screen.LOADING);

        int nameMax = state.nameMax > 0 ? state.nameMax : DEFAULT_NAME_MAX;
        int nameMin = Math.max(0, Math.min(state.nameMin, nameMax));
        Control create = Control.cap(state.can(GroupsState.CAN_CREATE), busy);
        Control createName = Control.cap(state.can(GroupsState.CAN_CREATE_NAME), false);
        Control createPassword = Control.cap(state.can(GroupsState.CAN_CREATE_PASSWORD), false);
        Control createPersistent = Control.cap(state.can(GroupsState.CAN_CREATE_PERSISTENT), false);
        Control createPermissions = Control.cap(state.can(GroupsState.CAN_CREATE_PERMISSIONS), false);

        if (state.current != null) {
            GroupView group = group(state.current, self, busy,
                    speaking == null ? Collections.<UUID>emptySet() : speaking, selfSpeaking, voicePlayers);
            return new View(Screen.GROUP, busy, nameMin, nameMax, create, createName, createPassword, createPersistent,
                    createPermissions, Collections.<GroupRow>emptyList(), 0, state.can(GroupsState.CAN_BROWSE), group);
        }

        List<GroupRow> groups = new ArrayList<>();
        boolean browse = state.can(GroupsState.CAN_BROWSE);
        boolean join = state.can(GroupsState.CAN_JOIN);
        if (browse) {
            for (GroupsState.BrowseEntry entry : state.browse) {
                Control control;
                if (!join) control = Control.off(Reason.NO_PERMISSION);
                else if (!entry.joinable) control = Control.off(Reason.NOT_JOINABLE);
                else control = busy ? Control.off(Reason.BUSY) : Control.ON;
                groups.add(new GroupRow(entry, control));
            }
        }
        return new View(Screen.BROWSE, busy, nameMin, nameMax, create, createName, createPassword, createPersistent,
                createPermissions, Collections.unmodifiableList(groups), browse ? state.browseTotal : 0, browse, null);
    }

    private static View empty(Screen screen) {
        Control off = Control.off(Reason.NO_PERMISSION);
        return new View(screen, false, 0, DEFAULT_NAME_MAX, off, off, off, off, off,
                Collections.<GroupRow>emptyList(), 0, false, null);
    }

    private static GroupView group(GroupsState.CurrentGroup group, UUID self, boolean busy, Set<UUID> speaking,
                                   boolean selfSpeaking, Map<UUID, String> voicePlayers) {
        boolean selfIsOwner = group.isOwner(self);
        Set<UUID> memberIds = new HashSet<>();
        List<MemberRow> members = new ArrayList<>();
        for (GroupsState.Member member : group.members) {
            memberIds.add(member.id);
            boolean isSelf = member.id.equals(self);
            boolean isOwner = group.isOwner(member.id);
            boolean isSpeaking = isSpeaking(member.online, isSelf, member.id, speaking, selfSpeaking);
            members.add(new MemberRow(member.id, member.name, member.online, isSpeaking, isSelf, isOwner,
                    target(group.can(GroupsState.CAN_KICK), busy, isSelf, member.online),
                    target(group.can(GroupsState.CAN_BAN), busy, isSelf, member.online)));
        }
        members.sort(MEMBER_ORDER);

        List<BannedRow> banned = new ArrayList<>();
        Control unban = Control.cap(group.can(GroupsState.CAN_UNBAN), busy);
        for (GroupsState.Member member : group.banned) banned.add(new BannedRow(member.id, member.name, unban));
        banned.sort((a, b) -> a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT)));

        List<Candidate> invite = new ArrayList<>();
        if (voicePlayers != null) {
            for (Map.Entry<UUID, String> player : voicePlayers.entrySet()) {
                UUID id = player.getKey();
                if (id == null || player.getValue() == null || id.equals(self) || memberIds.contains(id)) continue;
                invite.add(new Candidate(id, player.getValue()));
            }
        }
        invite.sort(CANDIDATE_ORDER);

        List<Candidate> transfer = new ArrayList<>();
        for (MemberRow member : members) {
            if (member.online && !member.self) transfer.add(new Candidate(member.id, member.name));
        }
        transfer.sort(CANDIDATE_ORDER);

        return new GroupView(group, selfIsOwner, Collections.unmodifiableList(members),
                Collections.unmodifiableList(banned), Collections.unmodifiableList(invite),
                Collections.unmodifiableList(transfer), busy);
    }

    /**
     * Whether a member shows the speaking marker: only online members, the local player by the microphone, the others
     * by the group audio they produce. The GUI calls this every frame so the marker follows the audio without
     * rebuilding the tab.
     */
    public static boolean isSpeaking(boolean online, boolean self, UUID id, Set<UUID> speaking, boolean selfSpeaking) {
        if (!online) return false;
        return self ? selfSpeaking : speaking != null && speaking.contains(id);
    }

    /** Kick and ban need the target online (the server requires it) and never apply to yourself. */
    private static Control target(boolean allowed, boolean busy, boolean self, boolean online) {
        if (!allowed) return Control.off(Reason.NO_PERMISSION);
        if (self) return Control.off(Reason.SELF);
        if (!online) return Control.off(Reason.OFFLINE);
        return busy ? Control.off(Reason.BUSY) : Control.ON;
    }

    private static final Comparator<MemberRow> MEMBER_ORDER = (a, b) -> {
        if (a.owner != b.owner) return a.owner ? -1 : 1;
        if (a.online != b.online) return a.online ? -1 : 1;
        return a.name.compareToIgnoreCase(b.name);
    };

    private static final Comparator<Candidate> CANDIDATE_ORDER = (a, b) -> a.name.compareToIgnoreCase(b.name);

    private static String ownerName(GroupsState.CurrentGroup group) {
        if (group.ownerId == null) return null;
        if (group.ownerName != null && !group.ownerName.isEmpty()) return group.ownerName;
        for (GroupsState.Member member : group.members) {
            if (member.id.equals(group.ownerId)) return member.name;
        }
        return null;
    }

    /** The line under the tab: waiting beats a timeout, which beats the last result. */
    public static Status status(GroupsResult result, boolean busy, boolean timedOut) {
        if (busy) return Status.WAITING;
        if (timedOut) return Status.TIMED_OUT;
        if (result == null) return Status.NONE;
        return result.ok ? Status.OK : Status.ERROR;
    }

    /** Server text without section-sign colour codes; the GUI colours the line by {@link Status} instead. */
    public static String stripFormatting(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
