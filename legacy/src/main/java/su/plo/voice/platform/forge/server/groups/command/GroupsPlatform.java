package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.List;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

/** Everything /groups needs from the running server. 1.7.10 has no vanish API, so every online player is visible. */
public interface GroupsPlatform {
    /** @return the online player with this name (case-insensitive, exact), or null */
    GroupProfile onlinePlayer(String name);

    /** @return the online player with this id, or null */
    default GroupProfile onlinePlayer(UUID id) {
        for (GroupProfile profile : onlinePlayers()) {
            if (profile.getId().equals(id)) return profile;
        }
        return null;
    }

    List<GroupProfile> onlinePlayers();

    GroupsSettings settings();

    /** @param receiver null for the console / default language */
    String translate(UUID receiver, String key, Object... args);

    /** No-op when the receiver is offline. */
    void send(UUID receiver, IChatComponent message);

    /** Permission of an online player (used to re-check a changed permission filter); false when offline. */
    boolean hasPermission(UUID player, String node);

    /** Wraps the sender of a vanilla command. */
    GroupsCommandSource source(ICommandSender sender);
}
