package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupAccess;
import su.plo.voice.platform.forge.server.groups.GroupActor;
import su.plo.voice.platform.forge.server.groups.GroupPermissions;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupRules;
import su.plo.voice.platform.forge.server.groups.GroupsListener;
import su.plo.voice.platform.forge.server.groups.GroupsManager;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

/**
 * The one place that validates and performs a group action. The /groups commands and the Groups GUI both call it, so
 * a client cannot do anything through a packet that the commands would refuse. Each method checks everything (the
 * actor's nodes, membership, targets, values), mutates through the {@link GroupsManager}, sends the group
 * notifications and returns an {@link Outcome}; printing it is the caller's business.
 */
public final class GroupActions {
    static final List<String> SET_FLAGS = Arrays.asList("name", "password", "permissions", "persistent");
    static final List<String> UNSET_FLAGS = Arrays.asList("password", "permissions");

    private final GroupsManager manager;
    private final GroupsPlatform platform;
    private final GroupsListener notifier;

    public GroupActions(GroupsManager manager, GroupsPlatform platform, GroupsListener notifier) {
        this.manager = manager;
        this.platform = platform;
        this.notifier = notifier;
    }

    // ---- results ----

    /** What an action did. {@code key} is relative to "pv.addon.groups."; a successful action may have none. */
    public static final class Outcome {
        public final boolean ok;
        /** Null for a success that has no message of its own (the group notifications say it). */
        public final String key;
        public final Object[] args;
        /** The console tried a player-only action. */
        public final boolean playerOnly;
        /** The change was refused only because nothing would change. */
        public final boolean identical;
        /** The group a create produced. */
        public final Group group;

        private Outcome(boolean ok, String key, Object[] args, boolean playerOnly, boolean identical, Group group) {
            this.ok = ok;
            this.key = key;
            this.args = args;
            this.playerOnly = playerOnly;
            this.identical = identical;
            this.group = group;
        }

        static Outcome ok(String key, Object... args) {
            return new Outcome(true, key, args, false, false, null);
        }

        static Outcome error(String key, Object... args) {
            return new Outcome(false, key, args, false, false, null);
        }

        static Outcome identicalValue(String key) {
            return new Outcome(false, key, new Object[0], false, true, null);
        }

        static Outcome playerOnlyError() {
            return new Outcome(false, null, new Object[0], true, false, null);
        }

        Outcome with(Group group) {
            return new Outcome(ok, key, args, playerOnly, identical, group);
        }

        @Override
        public String toString() {
            return "Outcome{" + (ok ? "ok " : "error ") + key + "}";
        }
    }

    /** Who a kick / ban / invite / transfer / unban is aimed at: a typed name (command) or an id (GUI). */
    public static final class Target {
        private final String name;
        private final UUID id;

        private Target(String name, UUID id) {
            this.name = name;
            this.id = id;
        }

        /** @return null for a missing name, which the action reports as a usage error */
        public static Target byName(String name) {
            return name == null ? null : new Target(name, null);
        }

        /** @return null for a missing id, which the action reports as a usage error */
        public static Target byId(UUID id) {
            return id == null ? null : new Target(null, id);
        }

        boolean isSelf(GroupProfile self) {
            return id != null ? id.equals(self.getId()) : name.equalsIgnoreCase(self.getName());
        }

        GroupProfile online(GroupsPlatform platform) {
            return id != null ? platform.onlinePlayer(id) : platform.onlinePlayer(name);
        }

        GroupProfile banned(Group group) {
            for (GroupProfile banned : group.getBanned()) {
                if (id != null ? banned.getId().equals(id) : banned.getName().equalsIgnoreCase(name)) return banned;
            }
            return null;
        }
    }

    // ---- shared steps ----

    private static Outcome noPermission(String sub) {
        return Outcome.error("error.no_permission", GroupPermissions.addon(sub));
    }

    private static final class Member {
        final GroupProfile profile;
        final Group group;
        final boolean owner;
        final Outcome error;

        Member(GroupProfile profile, Group group, Outcome error) {
            this.profile = profile;
            this.group = group;
            this.owner = group != null && profile != null && group.isOwner(profile.getId());
            this.error = error;
        }
    }

    private Member requireMember(GroupActor actor) {
        GroupProfile profile = actor.profile();
        if (profile == null) return new Member(null, null, Outcome.playerOnlyError());
        Group group = manager.groupOf(profile.getId());
        if (group == null) return new Member(profile, null, Outcome.error("error.not_in_group"));
        return new Member(profile, group, null);
    }

    /** Group names must be printable: no control characters and no colour-code markers. */
    private static boolean printable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '§') return false;
        }
        return true;
    }

    /** A password is its first word; an empty one means no password. */
    static String normalizePassword(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        int space = trimmed.indexOf(' ');
        String word = space < 0 ? trimmed : trimmed.substring(0, space);
        return word.isEmpty() ? null : word;
    }

    private Outcome checkName(String name) {
        GroupsSettings settings = platform.settings();
        int min = settings.getMinimumNameLength();
        int max = settings.getMaximumNameLength();
        if (name.length() < min || name.length() > max) return Outcome.error("error.name_length", min, max);
        if (!printable(name)) return Outcome.error("command.set.error.invalid_value", name);
        return null;
    }

    // ---- create / join / leave ----

    /**
     * Upstream /groups create. Every parameter is null when not given.
     *
     * @param password      raw value; its first word becomes the password
     * @param persistent    "true" or "false"; anything else is an invalid value
     */
    public Outcome create(GroupActor actor, String name, String password, Set<String> permissions, String persistent) {
        if (!GroupRules.has(actor, "create")) return noPermission("create");
        GroupProfile creator = actor.profile();
        GroupsSettings settings = platform.settings();

        if (name != null && !GroupRules.hasFlag(actor, "name")) return noPermission("flag.name");
        String groupName = name != null
                ? name
                : settings.getDefaultGroupNameFormat().replace("%player%", creator == null ? "Server" : creator.getName());
        Outcome nameError = checkName(groupName);
        if (nameError != null) return nameError;
        if (password != null && !GroupRules.hasFlag(actor, "password")) return noPermission("flag.password");
        if (permissions != null && !GroupRules.hasFlag(actor, "permissions")) return noPermission("flag.permissions");
        Boolean persistentValue = null;
        if (persistent != null) {
            if (!GroupRules.hasFlag(actor, "persistent")) return noPermission("flag.persistent");
            persistentValue = CreateFlags.parseBoolean(persistent);
            if (persistentValue == null) return Outcome.error("command.set.error.invalid_value", persistent);
        }
        boolean isPersistent = persistentValue != null && persistentValue;
        Set<String> nodes = permissions == null ? new LinkedHashSet<String>() : permissions;

        if (creator != null) {
            Group old = manager.groupOf(creator.getId());
            if (old != null) {
                manager.leave(creator.getId());
                notifier.notifyGroup(old, "notifications.player_left", creator.getName());
            }
        }
        Group group = manager.create(groupName, normalizePassword(password), isPersistent, nodes, creator);
        return Outcome.ok("command.create.success", group.getName()).with(group);
    }

    /** Upstream /groups join with the group id as typed. */
    public Outcome join(GroupActor actor, String groupId, String password) {
        if (!GroupRules.has(actor, "join")) return noPermission("join");
        if (actor.profile() == null) return Outcome.playerOnlyError();
        if (groupId == null) return Outcome.error("command.join.error.usage");
        UUID id;
        try {
            id = UUID.fromString(groupId);
        } catch (IllegalArgumentException e) {
            return Outcome.error("error.uuid_parse", groupId);
        }
        return join(actor, id, password);
    }

    public Outcome join(GroupActor actor, UUID groupId, String password) {
        if (!GroupRules.has(actor, "join")) return noPermission("join");
        GroupProfile player = actor.profile();
        if (player == null) return Outcome.playerOnlyError();
        if (groupId == null) return Outcome.error("command.join.error.usage");
        Group group = manager.group(groupId);
        if (group == null) return Outcome.error("error.group_not_found");
        Group old = manager.groupOf(player.getId());
        if (old == group) return Outcome.error("command.join.error.already_joined");

        // every check runs before the old group is left, so a refused join keeps the player where they were
        switch (GroupAccess.checkJoin(group, player.getId(), password, actor::hasPermission)) {
            case BANNED:
                return Outcome.error("command.join.error.banned");
            case NO_PERMISSION:
                return Outcome.error("command.join.error.no_permission");
            case PASSWORD_MISSING:
                return Outcome.error("command.join.error.password_arg_missing");
            case WRONG_PASSWORD:
                return Outcome.error("command.join.error.wrong_password");
            default:
                break;
        }

        if (old != null) {
            manager.leave(player.getId());
            notifier.notifyGroup(old, "notifications.player_left", player.getName());
        }
        notifier.notifyGroup(group, "notifications.player_join", player.getName());
        manager.join(player, group);
        return Outcome.ok("command.join.success", group.getName());
    }

    public Outcome leave(GroupActor actor) {
        if (!GroupRules.has(actor, "leave")) return noPermission("leave");
        Member member = requireMember(actor);
        if (member.error != null) return member.error;

        manager.leave(member.profile.getId());
        notifier.notifyGroup(member.group, "notifications.player_left", member.profile.getName());
        return Outcome.ok("command.leave.success", member.group.getName());
    }

    // ---- invite / kick / ban / unban / transfer / delete ----

    public Outcome invite(final GroupActor actor, Target target) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.maySee(actor, "invite", member.owner)) {
            return noPermission(member.owner ? "invite.owner" : "invite.member");
        }
        if (target == null) return Outcome.error("command.invite.error.usage");
        if (target.isSelf(member.profile)) return Outcome.error("command.invite.error.invite_self");
        GroupProfile invited = target.online(platform);
        if (invited == null) return Outcome.error("error.player_not_found");
        Group group = member.group;
        if (manager.groupOf(invited.getId()) == group) return Outcome.error("command.invite.error.already_joined");
        if (group.isBanned(invited.getId())) return Outcome.error("command.invite.error.banned");

        // the join button is built now, so it carries the password the group has at this moment
        final UUID receiver = invited.getId();
        Translator tr = new Translator() {
            @Override
            public String translate(String key, Object... a) {
                return platform.translate(receiver, key, a);
            }
        };
        platform.send(receiver, GroupComponents.divider(tr));
        platform.send(receiver, GroupComponents.invitation(member.profile.getName(), group, tr));
        platform.send(receiver, GroupComponents.joinButtonWithPassword(group, tr));
        platform.send(receiver, GroupComponents.divider(tr));
        return Outcome.ok("command.invite.success", invited.getName());
    }

    public Outcome kick(GroupActor actor, Target target) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "kick", member.owner)) {
            return noPermission(member.owner ? "kick.owner" : "kick.all");
        }
        if (target == null) return Outcome.error("command.kick.error.usage");
        GroupProfile victim = target.online(platform);
        if (victim == null) return Outcome.error("error.player_not_found");
        if (!member.group.getMembers().contains(victim.getId())) return Outcome.error("command.kick.error.not_in_group");
        // the manager sends the kicked / player_kicked notices
        manager.kick(member.group, victim);
        return Outcome.ok(null);
    }

    public Outcome ban(GroupActor actor, Target target) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "ban", member.owner)) {
            return noPermission(member.owner ? "ban.owner" : "ban.all");
        }
        if (target == null) return Outcome.error("command.ban.error.usage");
        GroupProfile victim = target.online(platform);
        if (victim == null) return Outcome.error("error.player_not_found");
        if (member.group.isBanned(victim.getId())) return Outcome.error("command.ban.error.already_banned");
        // the manager sends the banned / player_banned notices
        manager.ban(member.group, victim);
        return Outcome.ok(null);
    }

    /** Shares the ban.* permissions, like upstream; the target comes from the banned list, online or not. */
    public Outcome unban(GroupActor actor, Target target) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "ban", member.owner)) {
            return noPermission(member.owner ? "ban.owner" : "ban.all");
        }
        if (target == null) return Outcome.error("command.unban.error.usage");
        GroupProfile banned = target.banned(member.group);
        if (banned == null) return Outcome.error("command.unban.error.not_banned");
        manager.unban(member.group, banned);
        return Outcome.ok(null);
    }

    public Outcome transfer(GroupActor actor, Target target) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "transfer", member.owner)) {
            return noPermission(member.owner ? "transfer.owner" : "transfer.all");
        }
        if (target == null) return Outcome.error("command.transfer.error.usage");
        GroupProfile newOwner = target.online(platform);
        if (newOwner == null) return Outcome.error("error.player_not_found");
        if (newOwner.equals(member.profile)) return Outcome.error("command.transfer.error.already_owner");
        if (!member.group.getMembers().contains(newOwner.getId())) return Outcome.error("command.transfer.error.not_in_group");
        // the manager announces the new owner itself
        manager.transfer(member.group, newOwner);
        return Outcome.ok(null);
    }

    public Outcome delete(GroupActor actor) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "delete", member.owner)) {
            return noPermission(member.owner ? "delete.owner" : "delete.all");
        }
        notifier.notifyGroup(member.group, "notifications.group_deleted");
        manager.delete(member.group);
        return Outcome.ok(null);
    }

    // ---- set / unset / update ----

    /** One validated change, applied later. */
    private static final class Change {
        final Outcome error;
        final Runnable apply;

        Change(Outcome error, Runnable apply) {
            this.error = error;
            this.apply = apply;
        }

        static Change fail(Outcome error) {
            return new Change(error, null);
        }
    }

    /** Upstream /groups set {@code flag value...}; {@code value} holds the words after the flag. */
    public Outcome set(GroupActor actor, String flag, List<String> value) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "set", member.owner)) {
            return noPermission(member.owner ? "set.owner" : "set.all");
        }
        if (flag == null) return Outcome.error("command.set.error.usage");
        if (!SET_FLAGS.contains(flag)) {
            List<String> available = new ArrayList<>();
            for (String known : SET_FLAGS) {
                if (GroupRules.hasFlag(actor, known)) available.add(known);
            }
            return Outcome.error("command.set.error.unknown_flag", flag, String.join(", ", available));
        }
        if (!GroupRules.hasFlag(actor, flag)) return noPermission("flag." + flag);
        if (value.isEmpty()) return Outcome.error("command.set.error.usage");

        Change change = prepareSet(member.group, flag, String.join(" ", value), value.get(0));
        if (change.error != null) return change.error;
        change.apply.run();
        return Outcome.ok(null);
    }

    /** Upstream /groups unset {@code flag}. */
    public Outcome unset(GroupActor actor, String flag) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        if (!GroupRules.mayManage(actor, "unset", member.owner)) {
            return noPermission(member.owner ? "unset.owner" : "unset.all");
        }
        if (flag == null) return Outcome.error("command.unset.error.usage");
        if (!UNSET_FLAGS.contains(flag)) {
            List<String> available = new ArrayList<>();
            for (String known : UNSET_FLAGS) {
                if (GroupRules.hasFlag(actor, known)) available.add(known);
            }
            return Outcome.error("command.unset.error.unknown_flag", flag, String.join(", ", available));
        }
        if (!GroupRules.hasFlag(actor, flag)) return noPermission("flag." + flag);

        Change change = prepareUnset(member.group, flag);
        if (change.error != null) return change.error;
        change.apply.run();
        return Outcome.ok(null);
    }

    /**
     * Groups GUI edit: any combination of /groups set|unset in one request. Every provided field is validated with the
     * rules of its command before anything is applied; a field equal to the current value is skipped unless all
     * provided fields are, which is an identical_value error.
     *
     * @param name          new name, or null
     * @param password      new password (first word), or null/empty to leave it
     * @param clearPassword remove the password (like unset password)
     * @param persistent    new value, or null
     * @param permissions   the new filter; empty clears it (like unset permissions); null leaves it
     */
    public Outcome update(GroupActor actor, String name, String password, boolean clearPassword, Boolean persistent,
                          List<String> permissions) {
        Member member = requireMember(actor);
        if (member.error != null) return member.error;
        Group group = member.group;

        String newPassword = normalizePassword(password);
        if (newPassword != null && clearPassword) return Outcome.error("command.set.error.invalid_value", "password");
        Set<String> nodes = null;
        if (permissions != null) {
            nodes = new LinkedHashSet<>();
            for (String node : permissions) {
                if (node != null && !node.trim().isEmpty()) nodes.add(node.trim());
            }
            if (nodes.isEmpty() && !permissions.isEmpty()) return Outcome.error("command.set.error.invalid_value", "permissions");
        }
        if (name == null && newPassword == null && !clearPassword && persistent == null && nodes == null) {
            return Outcome.error("command.set.error.usage");
        }

        List<Change> changes = new ArrayList<>();
        Outcome identical = null;
        // name, password, persistent, permissions: the order the changes are applied in too
        Object[][] fields = {
                {"name", name != null},
                {"password", newPassword != null},
                {"password-clear", clearPassword},
                {"persistent", persistent != null},
                {"permissions", nodes != null && !nodes.isEmpty()},
                {"permissions-clear", nodes != null && nodes.isEmpty()},
        };
        for (Object[] field : fields) {
            if (!(Boolean) field[1]) continue;
            String id = (String) field[0];
            boolean clear = id.endsWith("-clear");
            String flag = clear ? id.substring(0, id.indexOf('-')) : id;
            String prefix = clear ? "unset" : "set";
            if (!GroupRules.mayManage(actor, prefix, member.owner)) {
                return noPermission(member.owner ? prefix + ".owner" : prefix + ".all");
            }
            if (!GroupRules.hasFlag(actor, flag)) return noPermission("flag." + flag);

            Change change;
            switch (id) {
                case "name":
                    change = prepareSet(group, "name", name, name);
                    break;
                case "password":
                    change = prepareSet(group, "password", newPassword, newPassword);
                    break;
                case "persistent":
                    change = prepareSet(group, "persistent", String.valueOf(persistent), String.valueOf(persistent));
                    break;
                case "permissions":
                    change = prepareSet(group, "permissions", String.join(",", nodes), String.join(",", nodes));
                    break;
                default:
                    change = prepareUnset(group, flag);
                    break;
            }
            if (change.error == null) {
                changes.add(change);
            } else if (change.error.identical) {
                if (identical == null) identical = change.error;
            } else {
                return change.error;
            }
        }
        if (changes.isEmpty()) {
            return identical != null ? identical : Outcome.error("command.set.error.usage");
        }
        for (Change change : changes) change.apply.run();
        return Outcome.ok("gui.updated");
    }

    private Change prepareSet(final Group group, String flag, String value, String firstWord) {
        switch (flag) {
            case "name": {
                final String name = value;
                if (group.getName().equals(name)) return Change.fail(Outcome.identicalValue("command.set.error.identical_value"));
                Outcome nameError = checkName(name);
                if (nameError != null) return Change.fail(nameError);
                return new Change(null, new Runnable() {
                    @Override
                    public void run() {
                        String old = group.getName();
                        manager.rename(group, name);
                        notifier.notifyGroup(group, "notifications.name_change", old, name);
                    }
                });
            }
            case "password": {
                final String password = firstWord;
                if (password.isEmpty()) return Change.fail(Outcome.error("command.set.error.invalid_value", password));
                if (password.equals(group.getPassword())) {
                    return Change.fail(Outcome.identicalValue("command.set.error.identical_value"));
                }
                return new Change(null, new Runnable() {
                    @Override
                    public void run() {
                        manager.setPassword(group, password);
                        notifier.notifyGroup(group, "notifications.password_changed");
                    }
                });
            }
            case "permissions": {
                final Set<String> permissions = CreateFlags.splitNodes(value);
                if (permissions.isEmpty()) return Change.fail(Outcome.error("command.set.error.invalid_value", value));
                if (permissions.equals(group.getPermissions())) {
                    return Change.fail(Outcome.identicalValue("command.set.error.identical_value"));
                }
                return new Change(null, new Runnable() {
                    @Override
                    public void run() {
                        manager.setPermissions(group, permissions);
                        notifier.notifyGroup(group, "notifications.permissions_changed");
                        kickWhoNoLongerPasses(group);
                    }
                });
            }
            default: {
                final Boolean persistent = CreateFlags.parseBoolean(value);
                if (persistent == null) return Change.fail(Outcome.error("command.set.error.invalid_value", value));
                if (group.isPersistent() == persistent) {
                    return Change.fail(Outcome.identicalValue("command.set.error.identical_value"));
                }
                return new Change(null, new Runnable() {
                    @Override
                    public void run() {
                        manager.setPersistent(group, persistent);
                        notifier.notifyGroup(group, persistent ? "notifications.persistent_true" : "notifications.persistent_false");
                    }
                });
            }
        }
    }

    private Change prepareUnset(final Group group, String flag) {
        if (flag.equals("password")) {
            if (group.getPassword() == null) return Change.fail(Outcome.identicalValue("command.unset.error.password_not_set"));
            return new Change(null, new Runnable() {
                @Override
                public void run() {
                    manager.setPassword(group, null);
                    notifier.notifyGroup(group, "notifications.password_unset");
                }
            });
        }
        if (group.getPermissions().isEmpty()) {
            return Change.fail(Outcome.identicalValue("command.unset.error.permissions_not_set"));
        }
        return new Change(null, new Runnable() {
            @Override
            public void run() {
                manager.setPermissions(group, new HashSet<String>());
                notifier.notifyGroup(group, "notifications.permissions_unset");
            }
        });
    }

    /** Members who no longer pass the permission filter are kicked (offline members cannot be checked). */
    private void kickWhoNoLongerPasses(Group group) {
        for (UUID id : group.onlineMembers()) {
            final UUID member = id;
            if (group.hasPermission(node -> platform.hasPermission(member, node))) continue;
            GroupProfile profile = platform.onlinePlayer(id);
            if (profile != null) manager.kick(group, profile);
        }
    }

    // ---- browse ----

    /** Whether the actor may browse; the visible groups come from {@link GroupRules#browseVisible}. */
    public static boolean mayBrowse(GroupActor actor) {
        return GroupRules.has(actor, "browse");
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
