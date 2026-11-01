package su.plo.voice.platform.forge.server.groups;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.UUID;
import java.util.function.Predicate;

/** Upstream JoinCommand.checkCantJoin as a pure decision. */
public final class GroupAccess {
    public enum Result {
        OK, BANNED, NO_PERMISSION, PASSWORD_MISSING, WRONG_PASSWORD
    }

    private GroupAccess() {
    }

    /**
     * @param password the password the player supplied, or null
     * @param has      permission check of the joining player
     */
    public static Result checkJoin(Group group, UUID player, String password, Predicate<String> has) {
        // upstream checked the typo "join.add", so join.all never bypassed
        if (has.test(GroupPermissions.addon("join.all")) || has.test(GroupPermissions.addon("join.*"))
                || has.test(GroupPermissions.addon("*"))) {
            return Result.OK;
        }
        if (group.isBanned(player)) return Result.BANNED;
        if (!group.hasPermission(has)) return Result.NO_PERMISSION;

        String required = group.getPassword();
        if (required != null) {
            if (password == null) return Result.PASSWORD_MISSING;
            if (!required.equals(password)) return Result.WRONG_PASSWORD;
        }
        return Result.OK;
    }
}
