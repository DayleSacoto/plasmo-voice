package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupAccess;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class JoinCommand extends SubCommand {
    JoinCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "join";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        return has(source, "join");
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (!canExecute(source)) return Collections.emptyList();
        if (args.length == 2) return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.groups_uuid"));
        if (args.length == 3) {
            UUID id;
            try {
                id = UUID.fromString(args[1]);
            } catch (IllegalArgumentException e) {
                return Collections.emptyList();
            }
            Group group = manager.group(id);
            if (group == null || group.getPassword() == null) return Collections.emptyList();
            return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.password"));
        }
        return Collections.emptyList();
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.join(source, args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null));
    }
}
