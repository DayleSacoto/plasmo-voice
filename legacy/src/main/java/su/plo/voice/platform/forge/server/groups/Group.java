package su.plo.voice.platform.forge.server.groups;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Upstream group.Group/GroupData without the Minecraft-side player set: the state of one voice group. Mutated only
 * by {@link GroupsManager} on the server thread; the UDP thread never reads a Group directly (it uses
 * {@link GroupRoute} snapshots), but every field is still safe to read concurrently.
 */
public final class Group {
    private final UUID id;
    private volatile String name;
    private volatile String password;
    private volatile boolean persistent;
    private volatile GroupProfile owner;
    private volatile Set<String> permissions = Collections.emptySet();
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final List<GroupProfile> banned = new CopyOnWriteArrayList<>();

    Group(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** @return the password, or null when the group is open. */
    public String getPassword() {
        return password;
    }

    public boolean isPersistent() {
        return persistent;
    }

    /** @return the owner, or null (console-created group, or no online member took over). */
    public GroupProfile getOwner() {
        return owner;
    }

    /** Required permission nodes (any one is enough); empty means a public group. */
    public Set<String> getPermissions() {
        return permissions;
    }

    /** All members, online or not (read-only view). */
    public Set<UUID> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    public List<GroupProfile> getBanned() {
        return Collections.unmodifiableList(banned);
    }

    public boolean isOwner(UUID player) {
        GroupProfile o = owner;
        return o != null && o.getId().equals(player);
    }

    public boolean isBanned(UUID player) {
        for (GroupProfile profile : banned) {
            if (profile.getId().equals(player)) return true;
        }
        return false;
    }

    /** Upstream Group.hasPermission: public, or any required node granted. */
    public boolean hasPermission(Predicate<String> has) {
        Set<String> required = permissions;
        if (required.isEmpty()) return true;
        for (String node : required) {
            if (has.test(node)) return true;
        }
        return false;
    }

    public int onlineCount() {
        return online.size();
    }

    public int memberCount() {
        return members.size();
    }

    /** Copy of the online members' ids. */
    public Set<UUID> onlineMembers() {
        return Collections.unmodifiableSet(new java.util.HashSet<>(online));
    }

    void setName(String name) {
        this.name = name;
    }

    void setPassword(String password) {
        this.password = password;
    }

    void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    void setOwner(GroupProfile owner) {
        this.owner = owner;
    }

    void setPermissions(Set<String> permissions) {
        this.permissions = Collections.unmodifiableSet(new java.util.HashSet<>(permissions));
    }

    Set<UUID> memberSet() {
        return members;
    }

    Set<UUID> onlineSet() {
        return online;
    }

    void ban(GroupProfile profile) {
        if (!isBanned(profile.getId())) banned.add(profile);
    }

    boolean unban(UUID player) {
        return banned.removeIf(profile -> profile.getId().equals(player));
    }

    List<GroupProfile> bannedCopy() {
        return new ArrayList<>(banned);
    }
}
