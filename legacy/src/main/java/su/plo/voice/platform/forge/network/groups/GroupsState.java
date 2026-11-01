package su.plo.voice.platform.forge.network.groups;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server to client: everything the Groups GUI and Group HUD need for one player, as the server sees it right now.
 * Each snapshot replaces the previous one completely. Never contains a password, only {@code hasPassword}.
 * Capability bits are advisory (they drive enabled/disabled controls); the server re-checks every action.
 */
public final class GroupsState {
    // Player-level capabilities (not tied to the current group).
    public static final int CAN_BROWSE = 1;
    public static final int CAN_CREATE = 1 << 1;
    public static final int CAN_JOIN = 1 << 2;
    public static final int CAN_CREATE_NAME = 1 << 3;
    public static final int CAN_CREATE_PASSWORD = 1 << 4;
    public static final int CAN_CREATE_PERSISTENT = 1 << 5;
    public static final int CAN_CREATE_PERMISSIONS = 1 << 6;

    // Capabilities in the current group.
    public static final int CAN_LEAVE = 1;
    public static final int CAN_INVITE = 1 << 1;
    public static final int CAN_KICK = 1 << 2;
    public static final int CAN_BAN = 1 << 3;
    public static final int CAN_UNBAN = 1 << 4;
    public static final int CAN_TRANSFER = 1 << 5;
    public static final int CAN_DELETE = 1 << 6;
    public static final int CAN_SET_NAME = 1 << 7;
    public static final int CAN_SET_PASSWORD = 1 << 8;
    public static final int CAN_SET_PERSISTENT = 1 << 9;
    public static final int CAN_SET_PERMISSIONS = 1 << 10;

    public static final GroupsState EMPTY = new GroupsState(0, 0, 0, null, Collections.emptyList(), 0);

    public final int capabilities;
    public final int nameMin;
    public final int nameMax;
    /** Null when the player is not in a group. */
    public final CurrentGroup current;
    /** Groups this player may see, server order (most online first), capped at {@link GroupsProtocol#MAX_BROWSE}. */
    public final List<BrowseEntry> browse;
    /** Visible groups before the cap. */
    public final int browseTotal;

    public GroupsState(int capabilities, int nameMin, int nameMax, CurrentGroup current, List<BrowseEntry> browse,
                       int browseTotal) {
        this.capabilities = capabilities;
        this.nameMin = nameMin;
        this.nameMax = nameMax;
        this.current = current;
        this.browse = Collections.unmodifiableList(Objects.requireNonNull(browse));
        this.browseTotal = browseTotal;
    }

    public boolean can(int capability) {
        return (capabilities & capability) != 0;
    }

    public static final class CurrentGroup {
        public final UUID id;
        public final String name;
        /** Null for an ownerless (for example persistent, admin-made) group. */
        public final UUID ownerId;
        public final String ownerName;
        public final boolean hasPassword;
        public final boolean persistent;
        public final List<String> permissions;
        /** All known members, online and offline. */
        public final List<Member> members;
        public final List<Member> banned;
        public final int capabilities;

        public CurrentGroup(UUID id, String name, UUID ownerId, String ownerName, boolean hasPassword,
                            boolean persistent, List<String> permissions, List<Member> members, List<Member> banned,
                            int capabilities) {
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.hasPassword = hasPassword;
            this.persistent = persistent;
            this.permissions = Collections.unmodifiableList(Objects.requireNonNull(permissions));
            this.members = Collections.unmodifiableList(Objects.requireNonNull(members));
            this.banned = Collections.unmodifiableList(Objects.requireNonNull(banned));
            this.capabilities = capabilities;
        }

        public boolean can(int capability) {
            return (capabilities & capability) != 0;
        }

        public boolean isOwner(UUID playerId) {
            return ownerId != null && ownerId.equals(playerId);
        }
    }

    public static final class Member {
        public final UUID id;
        public final String name;
        public final boolean online;

        public Member(UUID id, String name, boolean online) {
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            this.online = online;
        }
    }

    public static final class BrowseEntry {
        public final UUID id;
        public final String name;
        public final String ownerName;
        public final int online;
        public final int members;
        public final boolean hasPassword;
        public final boolean persistent;
        /** False when the server already knows a join would fail (banned or missing permission). */
        public final boolean joinable;

        public BrowseEntry(UUID id, String name, String ownerName, int online, int members, boolean hasPassword,
                           boolean persistent, boolean joinable) {
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            this.ownerName = ownerName;
            this.online = online;
            this.members = members;
            this.hasPassword = hasPassword;
            this.persistent = persistent;
            this.joinable = joinable;
        }
    }
}
