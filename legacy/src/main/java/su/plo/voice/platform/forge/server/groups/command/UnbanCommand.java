package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

/** Shares the ban.* permissions, like upstream. */
final class UnbanCommand extends SubCommand {
    UnbanCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "unban";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && mayManage(source, "ban", member.owner);
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        Member member = memberOf(source);
        if (member == null) return Collections.emptyList();
        String prefix = args[1].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (GroupProfile banned : member.group.getBanned()) {
            if (!banned.equals(member.profile) && banned.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(banned.getName());
            }
        }
        return names;
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.unban(source, GroupActions.Target.byName(args.length > 1 ? args[1] : null)));
    }
}
