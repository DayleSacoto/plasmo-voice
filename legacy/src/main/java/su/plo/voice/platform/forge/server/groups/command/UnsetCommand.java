package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class UnsetCommand extends SubCommand {
    private static final List<String> FLAGS = Arrays.asList("password", "permissions");

    UnsetCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "unset";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && mayManage(source, "unset", member.owner);
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String flag : FLAGS) {
            if (flag.startsWith(args[1]) && hasFlag(source, flag)) result.add(flag);
        }
        return result;
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.unset(source, args.length > 1 ? args[1] : null));
    }
}
