package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.server.groups.GroupActor;
import su.plo.voice.platform.forge.server.groups.GroupProfile;

/** Whoever ran /groups: a player, the console or a command block, detached from Minecraft types for testing. */
public interface GroupsCommandSource extends Translator, GroupActor {
    @Override
    GroupProfile profile();

    @Override
    boolean hasPermission(String node);

    @Override
    String translate(String key, Object... args);

    void send(IChatComponent message);
}
