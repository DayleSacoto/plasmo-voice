package su.plo.voice.platform.forge.network.groups;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Client to server: one Groups GUI request. Purely input; the server validates every field again with the same rules
 * as the {@code /groups} commands. Unused fields are null.
 */
public final class GroupsAction {
    public enum Type {
        /** Ask for a fresh {@link GroupsState}. */
        REFRESH,
        /** name?, password?, persistent?, permissions? */
        CREATE,
        /** groupId, password? */
        JOIN,
        LEAVE,
        /** playerId */
        INVITE,
        /** playerId */
        KICK,
        /** playerId */
        BAN,
        /** playerId (from the banned list) */
        UNBAN,
        /** playerId */
        TRANSFER,
        DELETE,
        /** Any of: name, password (or clearPassword), persistent, permissions (empty list clears). Null = unchanged. */
        UPDATE
    }

    public final int requestId;
    public final Type type;
    public final UUID groupId;
    public final UUID playerId;
    public final String name;
    public final String password;
    public final boolean clearPassword;
    public final Boolean persistent;
    public final List<String> permissions;

    public GroupsAction(int requestId, Type type, UUID groupId, UUID playerId, String name, String password,
                        boolean clearPassword, Boolean persistent, List<String> permissions) {
        this.requestId = requestId;
        this.type = Objects.requireNonNull(type);
        this.groupId = groupId;
        this.playerId = playerId;
        this.name = name;
        this.password = password;
        this.clearPassword = clearPassword;
        this.persistent = persistent;
        this.permissions = permissions == null ? null : Collections.unmodifiableList(permissions);
    }

    public static GroupsAction simple(int requestId, Type type) {
        return new GroupsAction(requestId, type, null, null, null, null, false, null, null);
    }

    public static GroupsAction player(int requestId, Type type, UUID playerId) {
        return new GroupsAction(requestId, type, null, playerId, null, null, false, null, null);
    }

    public static GroupsAction join(int requestId, UUID groupId, String password) {
        return new GroupsAction(requestId, Type.JOIN, groupId, null, null, password, false, null, null);
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "GroupsAction{" + requestId + " " + type + (groupId != null ? " group=" + groupId : "")
                + (playerId != null ? " player=" + playerId : "") + (password != null ? " password=***" : "") + "}";
    }
}
