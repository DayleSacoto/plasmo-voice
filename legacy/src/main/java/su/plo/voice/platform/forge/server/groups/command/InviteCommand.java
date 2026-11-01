package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class InviteCommand extends SubCommand {
    InviteCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "invite";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && maySee(source, "invite", member.owner);
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        final Member member = memberOf(source);
        if (member == null) return Collections.emptyList();
        final Set<UUID> online = member.group.onlineMembers();
        return playerNames(args[1], new Predicate<GroupProfile>() {
            @Override
            public boolean test(GroupProfile profile) {
                return !online.contains(profile.getId()) && !profile.equals(member.profile);
            }
        });
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.invite(source, GroupActions.Target.byName(args.length > 1 ? args[1] : null)));
    }
}
