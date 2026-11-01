package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class DeleteCommand extends SubCommand {
    DeleteCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "delete";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && mayManage(source, "delete", member.owner);
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.delete(source));
    }
}
