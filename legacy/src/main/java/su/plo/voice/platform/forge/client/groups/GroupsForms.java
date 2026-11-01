package su.plo.voice.platform.forge.client.groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import su.plo.voice.platform.forge.network.groups.GroupsAction;

/**
 * Turns what the player typed into {@link GroupsAction}s. Pure; passwords go straight into the action and are never
 * kept or printed here. Empty optional fields become null so the server applies its defaults.
 */
public final class GroupsForms {
    private GroupsForms() {
    }

    /** Null for blank text, otherwise the trimmed text. */
    public static String blankToNull(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Comma (or semicolon) separated permission names, trimmed, without empties and duplicates, in order. */
    public static List<String> parsePermissions(String text) {
        if (text == null) return Collections.emptyList();
        Set<String> permissions = new LinkedHashSet<>();
        for (String part : text.split("[,;]")) {
            String permission = part.trim();
            if (!permission.isEmpty()) permissions.add(permission);
        }
        return new ArrayList<>(permissions);
    }

    public static String formatPermissions(List<String> permissions) {
        return permissions == null ? "" : String.join(", ", permissions);
    }

    /**
     * A CREATE action. Fields the player may not set (the matching capability is missing) are not sent at all, so the
     * server's defaults apply and no rejected value is shown as an error.
     *
     * @param password sent only when non-empty and allowed; not trimmed because spaces may be part of it
     */
    public static GroupsAction create(int requestId, String name, String password, boolean persistent,
                                      String permissions, boolean canName, boolean canPassword, boolean canPersistent,
                                      boolean canPermissions) {
        List<String> parsed = canPermissions ? parsePermissions(permissions) : Collections.<String>emptyList();
        return new GroupsAction(requestId, GroupsAction.Type.CREATE, null, null,
                canName ? blankToNull(name) : null,
                canPassword && password != null && !password.isEmpty() ? password : null,
                false,
                canPersistent && persistent ? Boolean.TRUE : null,
                parsed.isEmpty() ? null : parsed);
    }

    /** JOIN; an empty password is sent as null. */
    public static GroupsAction join(int requestId, UUID groupId, String password) {
        return GroupsAction.join(requestId, groupId, password == null || password.isEmpty() ? null : password);
    }

    public static GroupsAction rename(int requestId, String name) {
        return new GroupsAction(requestId, GroupsAction.Type.UPDATE, null, null, blankToNull(name), null, false, null, null);
    }

    public static GroupsAction setPassword(int requestId, String password) {
        return new GroupsAction(requestId, GroupsAction.Type.UPDATE, null, null, null,
                password == null || password.isEmpty() ? null : password, false, null, null);
    }

    public static GroupsAction clearPassword(int requestId) {
        return new GroupsAction(requestId, GroupsAction.Type.UPDATE, null, null, null, null, true, null, null);
    }

    public static GroupsAction setPersistent(int requestId, boolean persistent) {
        return new GroupsAction(requestId, GroupsAction.Type.UPDATE, null, null, null, null, false, persistent, null);
    }

    /** An empty text clears the required permissions (the server reads an empty list as "clear"). */
    public static GroupsAction setPermissions(int requestId, String permissions) {
        return new GroupsAction(requestId, GroupsAction.Type.UPDATE, null, null, null, null, false, null,
                parsePermissions(permissions));
    }

    /** Whether a name is long enough for the server; blank (default name) is always fine. */
    public static boolean nameLengthOk(String name, int min, int max) {
        String trimmed = blankToNull(name);
        if (trimmed == null) return true;
        return trimmed.length() >= min && trimmed.length() <= max;
    }
}
