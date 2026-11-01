package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class LeaveCommand extends SubCommand {
    LeaveCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "leave";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        return memberOf(source) != null;
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.leave(source));
    }
}
