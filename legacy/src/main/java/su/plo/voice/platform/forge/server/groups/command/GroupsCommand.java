package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import su.plo.voice.platform.forge.server.groups.GroupProfile;

/** The /groups command: dispatches to the subcommands in upstream registration order. */
public final class GroupsCommand extends CommandBase {
    private final GroupsPlatform platform;
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    public GroupsCommand(su.plo.voice.platform.forge.server.groups.GroupsManager manager, GroupsPlatform platform) {
        this.platform = platform;
        GroupsNotifier notifier = new GroupsNotifier(platform);
        register(new BrowseCommand(manager, platform, notifier));
        register(new CreateCommand(manager, platform, notifier));
        register(new JoinCommand(manager, platform, notifier));
        register(new LeaveCommand(manager, platform, notifier));
        register(new InviteCommand(manager, platform, notifier));
        register(new InfoCommand(manager, platform, notifier));
        register(new SetCommand(manager, platform, notifier));
        register(new UnsetCommand(manager, platform, notifier));
        register(new DeleteCommand(manager, platform, notifier));
        register(new TransferCommand(manager, platform, notifier));
        register(new KickCommand(manager, platform, notifier));
        register(new BanCommand(manager, platform, notifier));
        register(new UnbanCommand(manager, platform, notifier));
    }

    private void register(SubCommand subCommand) {
        subCommands.put(subCommand.name(), subCommand);
    }

    @Override
    public String getCommandName() {
        return "groups";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/groups";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    /** Everyone may run it; the subcommands check their own nodes. */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        execute(platform.source(sender), args);
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return suggest(platform.source(sender), args);
    }

    /** Upstream CommandHandler.execute. */
    public void execute(GroupsCommandSource source, String[] args) {
        GroupActions.Outcome unsupported = clientError(source);
        if (unsupported != null) {
            SubCommand.print(source, unsupported);
            return;
        }
        if (args.length == 0) {
            subCommands.get("browse").execute(source, args);
            return;
        }
        SubCommand subCommand = subCommands.get(args[0]);
        if (subCommand != null) {
            subCommand.execute(source, args);
            return;
        }
        SubCommand.msg(source, "error.unknown_subcommand", String.join(", ", subCommands.keySet()));
    }

    /**
     * A player whose current client cannot use Groups gets no Groups at all: joining would make them a member that
     * never hears the group. Their stored membership stays as it is. The console is always allowed.
     */
    private GroupActions.Outcome clientError(GroupsCommandSource source) {
        GroupProfile player = source.profile();
        return player == null ? null : GroupActions.clientError(platform.clientSupport(player.getId()), "error.");
    }

    /** Upstream CommandHandler.suggest. */
    public List<String> suggest(GroupsCommandSource source, String[] args) {
        if (clientError(source) != null) return Collections.emptyList();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            List<String> names = new ArrayList<>();
            for (SubCommand subCommand : subCommands.values()) {
                if (subCommand.name().startsWith(prefix) && subCommand.canExecute(source)) names.add(subCommand.name());
            }
            return names;
        }
        SubCommand subCommand = subCommands.get(args[0]);
        return subCommand == null ? Collections.<String>emptyList() : subCommand.suggest(source, args);
    }
}
