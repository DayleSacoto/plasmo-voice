package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

final class CreateCommand extends SubCommand {
    CreateCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "create";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        return has(source, "create");
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        boolean insideFlag = false;
        if (args.length >= 2) {
            String before = args[args.length - 2];
            insideFlag = before.indexOf(':') >= 0 || before.endsWith(",");
        }
        String last = args.length == 0 ? "" : args[args.length - 1];

        if (!insideFlag) {
            List<String> used = CreateFlags.parse(args).usedFlags();
            List<String> result = new ArrayList<>();
            for (String flag : CreateFlags.KNOWN) {
                if (!used.contains(flag) && hasFlag(source, flag) && flag.startsWith(last)) result.add(flag + ":");
            }
            return result;
        }

        String flag = null;
        for (int i = args.length - 1; i >= 0; i--) {
            int colon = args[i].indexOf(':');
            if (colon >= 0) {
                flag = args[i].substring(0, colon);
                break;
            }
        }
        if (flag == null || !hasFlag(source, flag)) return Collections.emptyList();
        switch (flag) {
            case "name":
                return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.name"));
            case "password":
                return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.password"));
            case "permissions":
                return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.permissions"));
            case "persistent":
                return Arrays.asList("true", "false");
            default:
                return Collections.emptyList();
        }
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        CreateFlags flags = CreateFlags.parse(args);
        GroupActions.Outcome outcome = actions.create(source, flags.name(), flags.passwordRaw(), flags.permissions(),
                flags.persistentRaw());
        print(source, outcome);
        if (!outcome.ok) return;
        divider(source);
        send(source, GroupComponents.describe(outcome.group, source.profile(), manager, platform, source));
        divider(source);
    }
}
