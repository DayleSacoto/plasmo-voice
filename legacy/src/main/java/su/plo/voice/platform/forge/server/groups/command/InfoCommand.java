package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class InfoCommand extends SubCommand {
    InfoCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "info";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && maySee(source, "info", member.owner);
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        Member member = requireMember(source);
        if (member == null) return;
        if (!maySee(source, "info", member.owner)) {
            noPermission(source, member.owner ? "info.owner" : "info.member");
            return;
        }
        divider(source);
        send(source, GroupComponents.describe(member.group, member.profile, manager, platform, source));
        divider(source);
    }
}
