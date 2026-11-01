package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupRules;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

final class BrowseCommand extends SubCommand {
    BrowseCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        super(manager, platform, notifier);
    }

    @Override
    String name() {
        return "browse";
    }

    @Override
    boolean canExecute(GroupsCommandSource source) {
        return has(source, "browse");
    }

    @Override
    List<String> suggest(GroupsCommandSource source, String[] args) {
        if (args.length == 2) return Collections.singletonList(source.translate(GroupsNotifier.PREFIX + "arg.page"));
        return Collections.emptyList();
    }

    @Override
    void execute(GroupsCommandSource source, String[] args) {
        if (!has(source, "browse")) {
            noPermission(source, "browse");
            return;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                // upstream toIntOrNull ?: 1
            }
        }

        List<Group> visible = GroupRules.browseVisible(manager.groups(), source);

        if (visible.isEmpty()) {
            divider(source);
            msg(source, "command.browse.error.no_groups");
            divider(source);
            return;
        }
        int pages = (visible.size() + GroupComponents.PER_PAGE - 1) / GroupComponents.PER_PAGE;
        if (page < 1 || page > pages) {
            msg(source, "command.browse.error.page_not_found");
            return;
        }

        divider(source);
        int from = (page - 1) * GroupComponents.PER_PAGE;
        int to = Math.min(visible.size(), from + GroupComponents.PER_PAGE);
        for (Group group : visible.subList(from, to)) {
            send(source, GroupComponents.describe(group, source.profile(), manager, platform, source));
            divider(source);
        }
        source.send(GroupComponents.pageInfo(page, pages, source));
    }
}
