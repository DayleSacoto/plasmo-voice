package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

final class SetCommand extends SubCommand {
    SetCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "set";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        Member member = memberOf(source);
        return member != null && mayManage(source, "set", member.owner);
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (args.length < 2) return Collections.emptyList();
        String flag = args[1];
        if (args.length == 2) {
            List<String> result = new ArrayList<>();
            for (String known : CreateFlags.KNOWN) {
                if (known.startsWith(flag) && hasFlag(source, known)) result.add(known);
            }
            return result;
        }
        if (!hasFlag(source, flag)) return Collections.emptyList();
        switch (flag) {
            case "name":
                return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.name"));
            case "password":
                return args.length == 3
                        ? Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.password"))
                        : Collections.<String>emptyList();
            case "permissions":
                return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.permissions"));
            case "persistent":
                return args.length == 3 ? java.util.Arrays.asList("true", "false") : Collections.<String>emptyList();
            default:
                return Collections.emptyList();
        }
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        print(source, actions.set(source, args.length > 1 ? args[1] : null,
                java.util.Arrays.asList(args).subList(Math.min(2, args.length), args.length)));
    }
}
