package su.plo.voice.platform.forge.server.groups;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Forge 1.7.10 has no permission API, so nodes resolve from the upstream defaults: PermissionDefault.TRUE nodes are
 * open to everyone, every other node (including unknown ones, as for unregistered Bukkit nodes) is op-only.
 */
public final class GroupPermissions {
    public static final String PREFIX = "pv.addon.groups.";

    private static final Set<String> EVERYONE;

    static {
        Set<String> nodes = new HashSet<>();
        nodes.add("pv.activation.groups");
        for (String sub : Arrays.asList("browse", "create", "join", "leave", "invite.owner", "invite.member",
                "info.owner", "info.member", "set.owner", "unset.owner", "delete.owner", "transfer.owner",
                "kick.owner", "ban.owner", "flag.name", "flag.password")) {
            nodes.add(PREFIX + sub);
        }
        EVERYONE = Collections.unmodifiableSet(nodes);
    }

    private GroupPermissions() {
    }

    /** @return the full node for a sub-node, e.g. "join" to "pv.addon.groups.join". */
    public static String addon(String sub) {
        return PREFIX + sub;
    }

    public static boolean has(String node, boolean op) {
        return op || EVERYONE.contains(node);
    }
}
