package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.UUID;

import net.minecraft.util.ChatComponentText;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupsListener;

/** Upstream Group.notifyPlayers: each online member gets the message in their own language. */
public final class GroupsNotifier implements GroupsListener {
    static final String PREFIX = "pv.addon.groups.";

    private final GroupsPlatform platform;

    public GroupsNotifier(GroupsPlatform platform) {
        this.platform = platform;
    }

    /** @param key relative to "pv.addon.groups.", e.g. "notifications.new_owner" */
    @Override
    public void notifyGroup(Group group, String key, Object... args) {
        for (UUID member : group.onlineMembers()) {
            String text = platform.translate(member, PREFIX + "format.group_name", group.getName())
                    + " " + platform.translate(member, PREFIX + key, args);
            platform.send(member, new ChatComponentText(text));
        }
    }

    @Override
    public void notifyPlayer(UUID player, String key, Object... args) {
        platform.send(player, new ChatComponentText(platform.translate(player, PREFIX + key, args)));
    }
}
