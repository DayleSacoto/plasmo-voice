package su.plo.voice.platform.forge.server.groups;

import java.util.UUID;

/**
 * Callbacks from {@link GroupsManager} into the Minecraft/voice integration. All are called on the server thread and
 * default to no-ops.
 */
public interface GroupsListener {
    /**
     * Upstream Group.notifyPlayersTranslatable: message to every online member of the group.
     *
     * @param key relative to "pv.addon.groups.", e.g. "notifications.new_owner"
     */
    default void notifyGroup(Group group, String key, Object... args) {
    }

    /** Message to a single player; same key convention as {@link #notifyGroup}. */
    default void notifyPlayer(UUID player, String key, Object... args) {
    }

    /** Members, online members or sources of the group changed: the integration should resend source infos. */
    default void routesChanged(Group group) {
    }

    /** A speaker's source was discarded (leave, offline, delete). */
    default void sourceRemoved(GroupRoute route) {
    }

    /** Persistent state changed: the caller should save. */
    default void changed() {
    }
}
