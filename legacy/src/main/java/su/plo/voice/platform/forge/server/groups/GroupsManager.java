package su.plo.voice.platform.forge.server.groups;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Upstream GroupsManager as a pure state machine. Every mutation happens on the server thread; the UDP worker only
 * calls {@link #route(UUID)} and {@link #routeBySource(UUID)}, which read immutable {@link GroupRoute} snapshots from
 * concurrent maps. Minecraft side effects go through the {@link GroupsListener}.
 *
 * <p>Differences from upstream: {@link #delete} no longer spams new_owner notices, {@link #ban} only removes the
 * target from this group, and persistent state is exposed as {@link #snapshot()}/{@link #restore}.
 */
public final class GroupsManager {
    private static final byte SOURCE_STATE = 1;

    private final GroupsListener listener;
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    private final Map<UUID, Group> groupByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sources = new ConcurrentHashMap<>();
    private final Map<UUID, GroupRoute> routes = new ConcurrentHashMap<>();
    private final Map<UUID, GroupRoute> routesBySource = new ConcurrentHashMap<>();
    /** Profiles of online players that joined or reconnected; used to name a new owner. */
    private final Map<UUID, GroupProfile> profiles = new ConcurrentHashMap<>();
    /**
     * Players online in Minecraft, with or without a Groups client. A non-persistent group lives while one of its
     * members is here, even when nobody is an active Groups participant (onlineSet), as before Groups was optional.
     */
    private final Map<UUID, GroupProfile> present = new ConcurrentHashMap<>();
    /** Last known name of every player seen in a group; survives going offline. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Bumped on every state change, so a cache of derived views (the Groups GUI) knows when to rebuild. */
    private final AtomicLong version = new AtomicLong();
    private volatile Function<UUID, String> nameFallback = id -> null;

    public GroupsManager(GroupsListener listener) {
        this.listener = listener;
    }

    // ---- reads ----

    /** Increases on every mutation (including connection events); cheap to poll. */
    public long version() {
        return version.get();
    }

    /** Where {@link #nameOf} looks for players the manager has not seen this run (for example the Forge username cache). */
    public void setNameFallback(Function<UUID, String> fallback) {
        this.nameFallback = fallback == null ? id -> null : fallback;
    }

    /** Last known name: remembered here, else the fallback, else the shortened UUID. Never null. */
    public String nameOf(UUID id) {
        String name = names.get(id);
        if (name == null || name.isEmpty()) {
            try {
                name = nameFallback.apply(id);
            } catch (RuntimeException e) {
                name = null;
            }
        }
        return name == null || name.isEmpty() ? id.toString().substring(0, 8) : name;
    }

    /** Names remembered for the given players (for persistence); players without one are left out. */
    Map<UUID, String> knownNames(Collection<UUID> ids) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (UUID id : ids) {
            String name = names.get(id);
            if (name != null && !name.isEmpty()) result.put(id, name);
        }
        return result;
    }

    public Group group(UUID id) {
        return groups.get(id);
    }

    public Group groupOf(UUID player) {
        return groupByPlayer.get(player);
    }

    public Collection<Group> groups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    /** UDP-thread safe. */
    public GroupRoute route(UUID speaker) {
        return routes.get(speaker);
    }

    /** UDP-thread safe. */
    public GroupRoute routeBySource(UUID sourceId) {
        return routesBySource.get(sourceId);
    }

    public List<GroupRoute> routes(Group group) {
        List<GroupRoute> result = new ArrayList<>();
        for (UUID member : group.onlineSet()) {
            GroupRoute route = routes.get(member);
            if (route != null) result.add(route);
        }
        return result;
    }

    /** Forgets everything without any callbacks (shutdown/reload). */
    public void clear() {
        touch();
        names.clear();
        groups.clear();
        groupByPlayer.clear();
        sources.clear();
        routes.clear();
        routesBySource.clear();
        profiles.clear();
        present.clear();
    }

    // ---- lifecycle ----

    /** @param owner null for a console-created group */
    public Group create(String name, String password, boolean persistent, Set<String> permissions, GroupProfile owner) {
        touch();
        Group group = new Group(UUID.randomUUID(), name);
        group.setPassword(password);
        group.setPersistent(persistent);
        group.setPermissions(permissions);
        groups.put(group.getId(), group);
        if (owner != null) {
            group.setOwner(owner);
            join(owner, group);
        }
        if (persistent) listener.changed();
        return group;
    }

    /** Upstream join: leaves the previous group first. Joining the group the player is already in only refreshes it. */
    public void join(GroupProfile player, Group group) {
        if (groups.get(group.getId()) != group) return;
        touch();
        remember(player);
        profiles.put(player.getId(), player);
        if (groupByPlayer.get(player.getId()) == group && group.memberSet().contains(player.getId())) {
            group.onlineSet().add(player.getId());
            if (!sources.containsKey(player.getId())) sources.put(player.getId(), UUID.randomUUID());
            republish(group);
            return;
        }
        leave(player.getId());
        groupByPlayer.put(player.getId(), group);
        group.memberSet().add(player.getId());
        group.onlineSet().add(player.getId());
        sources.put(player.getId(), UUID.randomUUID());
        republish(group);
        if (group.isPersistent()) listener.changed();
    }

    /** @return whether the player was a member of a group */
    public boolean leave(UUID player) {
        touch();
        discardSource(player);
        Group group = groupByPlayer.remove(player);
        if (group == null) return false;
        boolean removed = group.memberSet().remove(player);
        group.onlineSet().remove(player);
        if (!removed) {
            republish(group);
            return false;
        }
        if (!group.isPersistent()) {
            if (group.isOwner(player)) {
                GroupProfile next = pickOnlineMember(group);
                group.setOwner(next);
                if (next != null) listener.notifyGroup(group, "notifications.new_owner", next.getName());
            }
            if (!anyMemberPresent(group)) {
                delete(group);
                return true;
            }
        }
        republish(group);
        if (group.isPersistent()) listener.changed();
        return true;
    }

    /** Removes the group and all its memberships, without owner hand-over notices. */
    public void delete(Group group) {
        if (groups.remove(group.getId()) == null) return;
        touch();
        for (UUID member : new ArrayList<>(group.memberSet())) {
            groupByPlayer.remove(member, group);
            discardSource(member);
        }
        group.memberSet().clear();
        group.onlineSet().clear();
        listener.routesChanged(group);
        if (group.isPersistent()) listener.changed();
    }

    public void kick(Group group, GroupProfile target) {
        leave(target.getId());
        listener.notifyPlayer(target.getId(), "notifications.kicked");
        listener.notifyGroup(group, "notifications.player_kicked", target.getName());
    }

    public void ban(Group group, GroupProfile target) {
        touch();
        remember(target);
        boolean wasMember = group.memberSet().contains(target.getId());
        if (wasMember) leave(target.getId());
        group.ban(target);
        listener.notifyGroup(group, "notifications.player_banned", target.getName());
        if (wasMember) listener.notifyPlayer(target.getId(), "notifications.banned");
        if (group.isPersistent()) listener.changed();
    }

    public boolean unban(Group group, GroupProfile target) {
        touch();
        if (!group.unban(target.getId())) return false;
        listener.notifyGroup(group, "notifications.player_unbanned", target.getName());
        if (group.isPersistent()) listener.changed();
        return true;
    }

    public void transfer(Group group, GroupProfile newOwner) {
        touch();
        remember(newOwner);
        group.setOwner(newOwner);
        listener.notifyGroup(group, "notifications.new_owner", newOwner.getName());
        if (group.isPersistent()) listener.changed();
    }

    public void rename(Group group, String name) {
        touch();
        group.setName(name);
        if (group.isPersistent()) listener.changed();
    }

    public void setPassword(Group group, String password) {
        touch();
        group.setPassword(password);
        if (group.isPersistent()) listener.changed();
    }

    public void setPersistent(Group group, boolean persistent) {
        touch();
        boolean was = group.isPersistent();
        group.setPersistent(persistent);
        if (was || persistent) listener.changed();
    }

    public void setPermissions(Group group, Set<String> permissions) {
        touch();
        group.setPermissions(permissions);
        if (group.isPersistent()) listener.changed();
    }

    // ---- connection events ----

    /**
     * Minecraft login, before (or without) Groups support: the player counts for keeping a non-persistent group, but
     * is not a Groups participant until {@link #playerOnline}.
     */
    public void playerPresent(GroupProfile player) {
        remember(player);
        present.put(player.getId(), player);
    }

    /** Upstream onPlayerJoin (voice connection established): restores a remembered membership. */
    public void playerOnline(GroupProfile player) {
        touch();
        remember(player);
        present.put(player.getId(), player);
        Group group = groupByPlayer.get(player.getId());
        if (group == null) return;
        profiles.put(player.getId(), player);
        GroupProfile owner = group.getOwner();
        if (owner != null && owner.getId().equals(player.getId())) {
            group.setOwner(player);
            if (group.isPersistent() && !owner.getName().equals(player.getName())) listener.changed();
        }
        discardSource(player.getId());
        group.onlineSet().add(player.getId());
        sources.put(player.getId(), UUID.randomUUID());
        republish(group);
    }

    /** Upstream onPlayerLeave: the membership is kept, the source is dropped. */
    public void playerOffline(UUID player) {
        touch();
        profiles.remove(player);
        present.remove(player);
        discardSource(player);
        Group group = groupByPlayer.get(player);
        if (group == null) return;
        group.onlineSet().remove(player);
        if (!group.isPersistent() && !anyMemberPresent(group)) {
            delete(group);
        } else {
            republish(group);
        }
    }

    // ---- persistence ----

    /** All persistent groups with their members' group mapping. */
    public GroupsStore.Data snapshot() {
        List<GroupsStore.Entry> entries = new ArrayList<>();
        Map<UUID, UUID> byPlayer = new LinkedHashMap<>();
        for (Group group : groups.values()) {
            if (!group.isPersistent()) continue;
            entries.add(new GroupsStore.Entry(group.getId(), group.getName(), group.getPassword(), true,
                    group.memberSet(), group.bannedCopy(), group.getOwner(), group.getPermissions(),
                    knownNames(group.memberSet())));
            for (UUID member : group.memberSet()) {
                if (groupByPlayer.get(member) == group) byPlayer.put(member, group.getId());
            }
        }
        return new GroupsStore.Data(entries, byPlayer);
    }

    /**
     * Registers the persistent groups of a loaded file on an empty manager (nobody online, no sources). A member only
     * stays in the group the mapping assigns it to; members without any mapping go to the first group listing them.
     */
    public void restore(GroupsStore.Data data) {
        touch();
        for (GroupsStore.Entry entry : data.getGroups()) {
            for (Map.Entry<UUID, String> known : entry.getMemberNames().entrySet()) names.putIfAbsent(known.getKey(), known.getValue());
            remember(entry.getOwner());
            for (GroupProfile banned : entry.getBanned()) remember(banned);
        }
        Map<UUID, Group> restored = new LinkedHashMap<>();
        for (GroupsStore.Entry entry : data.getGroups()) {
            if (!entry.isPersistent() || restored.containsKey(entry.getId())) continue;
            Group group = new Group(entry.getId(), entry.getName());
            group.setPassword(entry.getPassword());
            group.setPersistent(true);
            group.setOwner(entry.getOwner());
            group.setPermissions(entry.getPermissions());
            for (GroupProfile banned : entry.getBanned()) group.ban(banned);
            restored.put(group.getId(), group);
        }
        Map<UUID, Group> assigned = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> mapping : data.getGroupByPlayer().entrySet()) {
            Group group = restored.get(mapping.getValue());
            if (group != null && memberIds(data, group.getId()).contains(mapping.getKey())) {
                assigned.put(mapping.getKey(), group);
            }
        }
        for (GroupsStore.Entry entry : data.getGroups()) {
            Group group = restored.get(entry.getId());
            if (group == null) continue;
            for (UUID member : entry.getMembers()) {
                assigned.putIfAbsent(member, group);
            }
        }
        for (Map.Entry<UUID, Group> mapping : assigned.entrySet()) {
            mapping.getValue().memberSet().add(mapping.getKey());
            groupByPlayer.put(mapping.getKey(), mapping.getValue());
        }
        groups.putAll(restored);
    }

    private static Set<UUID> memberIds(GroupsStore.Data data, UUID groupId) {
        for (GroupsStore.Entry entry : data.getGroups()) {
            if (entry.getId().equals(groupId)) return entry.getMembers();
        }
        return Collections.emptySet();
    }

    // ---- internals ----

    private void touch() {
        version.incrementAndGet();
    }

    private void remember(GroupProfile profile) {
        if (profile != null && profile.getName() != null && !profile.getName().isEmpty()) {
            names.put(profile.getId(), profile.getName());
        }
    }

    private GroupProfile pickOnlineMember(Group group) {
        GroupProfile best = null;
        for (UUID id : group.onlineSet()) {
            GroupProfile profile = profiles.get(id);
            if (profile != null && (best == null || profile.getName().compareToIgnoreCase(best.getName()) < 0)) {
                best = profile;
            }
        }
        if (best != null) return best;
        // nobody with a Groups client is online: a member online with an older client keeps the group owned
        for (UUID id : group.memberSet()) {
            GroupProfile profile = present.get(id);
            if (profile != null && (best == null || profile.getName().compareToIgnoreCase(best.getName()) < 0)) {
                best = profile;
            }
        }
        return best;
    }

    /** Whether a member is online in Minecraft, with or without a Groups client. */
    private boolean anyMemberPresent(Group group) {
        for (UUID id : group.memberSet()) {
            if (group.onlineSet().contains(id) || present.containsKey(id)) return true;
        }
        return false;
    }

    private void discardSource(UUID player) {
        UUID sourceId = sources.remove(player);
        GroupRoute route = routes.remove(player);
        if (route != null) routesBySource.remove(route.getSourceId());
        if (sourceId != null) routesBySource.remove(sourceId);
        if (route != null) {
            listener.sourceRemoved(route);
        } else if (sourceId != null) {
            listener.sourceRemoved(new GroupRoute(player, sourceId, SOURCE_STATE, Collections.<UUID>emptySet()));
        }
    }

    /** Rebuilds the route of every online member (new recipients) and tells the integration. */
    private void republish(Group group) {
        Set<UUID> online = new HashSet<>(group.onlineSet());
        for (UUID member : online) {
            UUID sourceId = sources.get(member);
            if (sourceId == null) continue;
            GroupRoute route = new GroupRoute(member, sourceId, SOURCE_STATE, online);
            GroupRoute old = routes.put(member, route);
            if (old != null && !old.getSourceId().equals(sourceId)) routesBySource.remove(old.getSourceId());
            routesBySource.put(sourceId, route);
        }
        listener.routesChanged(group);
    }
}
