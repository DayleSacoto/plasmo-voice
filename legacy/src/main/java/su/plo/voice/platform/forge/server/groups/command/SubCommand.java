package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupPermissions;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupRules;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

/** One /groups subcommand. Argument 0 is the subcommand name itself. */
abstract class SubCommand {
    final GroupsManager manager;
    final GroupsPlatform platform;
    final GroupsNotifier notifier;
    final GroupActions actions;

    SubCommand(GroupsManager manager, GroupsPlatform platform, GroupsNotifier notifier) {
        this.manager = manager;
        this.platform = platform;
        this.notifier = notifier;
        this.actions = new GroupActions(manager, platform, notifier);
    }

    abstract String name();

    abstract void execute(GroupsCommandSource source, String[] args);

    /** @return whether the source may run it now; only decides if tab completion offers it */
    abstract boolean canExecute(GroupsCommandSource source);

    List<String> suggest(GroupsCommandSource source, String[] args) {
        return Collections.emptyList();
    }

    // ---- permissions ----

    /** Upstream hasAddonPermission: the wildcard or the node. */
    static boolean has(GroupsCommandSource source, String sub) {
        return GroupRules.has(source, sub);
    }

    static boolean hasFlag(GroupsCommandSource source, String flag) {
        return GroupRules.hasFlag(source, flag);
    }

    /** set / unset / delete / transfer / kick / ban / unban: the owner node for the owner, otherwise .all or .* */
    static boolean mayManage(GroupsCommandSource source, String prefix, boolean owner) {
        return GroupRules.mayManage(source, prefix, owner);
    }

    /** invite / info: .* or .member for any member, .owner for the owner */
    static boolean maySee(GroupsCommandSource source, String prefix, boolean owner) {
        return GroupRules.maySee(source, prefix, owner);
    }

    // ---- messages ----

    /** @param key relative to "pv.addon.groups." */
    static void msg(GroupsCommandSource source, String key, Object... args) {
        source.send(new ChatComponentText(source.translate(GroupsNotifier.PREFIX + key, args)));
    }

    /** Prints what a shared action answered; a success without a message prints nothing. */
    static void print(GroupsCommandSource source, GroupActions.Outcome outcome) {
        if (outcome.playerOnly) {
            playerOnly(source);
        } else if (outcome.key != null) {
            msg(source, outcome.key, outcome.args);
        }
    }

    static void noPermission(GroupsCommandSource source, String sub) {
        msg(source, "error.no_permission", GroupPermissions.addon(sub));
    }

    static void playerOnly(GroupsCommandSource source) {
        source.send(new ChatComponentText(source.translate("pv.error.player_only_command")));
    }

    static void divider(GroupsCommandSource source) {
        source.send(GroupComponents.divider(source));
    }

    static void send(GroupsCommandSource source, List<IChatComponent> lines) {
        for (IChatComponent line : lines) source.send(line);
    }

    // ---- shared steps ----

    /** The player running a group command and the group they are in. */
    static final class Member {
        final GroupProfile profile;
        final Group group;
        final boolean owner;

        Member(GroupProfile profile, Group group) {
            this.profile = profile;
            this.group = group;
            this.owner = group.isOwner(profile.getId());
        }
    }

    /** @return the caller and their group, or null after telling them why not (console / not in a group) */
    Member requireMember(GroupsCommandSource source) {
        GroupProfile profile = source.profile();
        if (profile == null) {
            playerOnly(source);
            return null;
        }
        Group group = manager.groupOf(profile.getId());
        if (group == null) {
            msg(source, "error.not_in_group");
            return null;
        }
        return new Member(profile, group);
    }

    /** Silent twin of {@link #requireMember} for tab completion. */
    Member memberOf(GroupsCommandSource source) {
        GroupProfile profile = source.profile();
        if (profile == null) return null;
        Group group = manager.groupOf(profile.getId());
        return group == null ? null : new Member(profile, group);
    }

    /** Names of online players matching the typed prefix (case-insensitive). */
    List<String> playerNames(String prefix, Predicate<GroupProfile> filter) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (GroupProfile profile : platform.onlinePlayers()) {
            if (filter.test(profile) && profile.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(profile.getName());
            }
        }
        return names;
    }
}
