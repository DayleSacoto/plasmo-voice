package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class BanCommand extends SubCommand {
    BanCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "ban";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && mayManage(source, "ban", member.owner);
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        final Member member = memberOf(source);
        if (member == null) return Collections.emptyList();
        return playerNames(args[1], new Predicate<GroupProfile>() {
            @Override
            public boolean test(GroupProfile profile) {
                return !member.group.isBanned(profile.getId()) && !profile.equals(member.profile);
            }
        });
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.ban(source, GroupActions.Target.byName(args.length > 1 ? args[1] : null)));
    }
}
