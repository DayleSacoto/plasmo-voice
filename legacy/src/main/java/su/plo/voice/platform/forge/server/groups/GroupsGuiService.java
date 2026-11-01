package su.plo.voice.platform.forge.server.groups;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.Logger;
import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.platform.forge.network.groups.GroupsProtocol;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.network.groups.GroupsState;
import su.plo.voice.platform.forge.server.groups.command.GroupActions;
import su.plo.voice.platform.forge.server.groups.command.GroupsPlatform;

/**
 * Server side of the Groups GUI: turns untrusted {@link GroupsAction}s into {@link GroupActions} calls (the same
 * validation the /groups commands use), answers with a translated {@link GroupsResult}, and keeps every voice-connected
 * client's {@link GroupsState} current by pushing a snapshot whenever it would differ from the last one sent.
 * Server thread only. Passwords are never logged.
 */
public final class GroupsGuiService {
    private static final String PREFIX = "pv.addon.groups.";
    /** At most this many actions per second and player; the rest is dropped. */
    static final int MAX_ACTIONS_PER_SECOND = 8;
    /** Without any state change, snapshots are still rechecked this often (permissions change outside the manager). */
    static final int SAFETY_NET_TICKS = 100;

    /** The Minecraft side: who is connected, their permissions, their language and the wire. */
    public interface Players {
        /** Players whose voice connection is established and who can receive Groups packets. */
        Collection<UUID> connected();

        boolean isConnected(UUID player);

        /** @return the player as an actor, or null when gone */
        GroupActor actor(UUID player);

        /** @param key full key, e.g. "pv.addon.groups.error.unknown" */
        String translate(UUID player, String key, Object... args);

        void sendState(UUID player, byte[] encodedState);

        void sendResult(UUID player, GroupsResult result);
    }

    private static final class Window {
        long start;
        int count;
    }

    private final GroupsManager manager;
    private final GroupsPlatform platform;
    private final GroupActions actions;
    private final Players players;
    private final LongSupplier clock;
    private final Logger logger;
    private final Map<UUID, byte[]> lastSent = new HashMap<>();
    private final Map<UUID, Window> windows = new HashMap<>();
    private long lastVersion = -1;
    private int idleTicks;

    public GroupsGuiService(GroupsManager manager, GroupsPlatform platform, GroupsListener notifier, Players players,
                            LongSupplier clock, Logger logger) {
        this.manager = manager;
        this.platform = platform;
        this.actions = new GroupActions(manager, platform, notifier);
        this.players = players;
        this.clock = clock;
        this.logger = logger;
    }

    // ---- actions ----

    /** Handles one decoded action of a player; silently ignored unless the player is voice-connected. */
    public void handle(UUID player, GroupsAction action) {
        if (action == null || !players.isConnected(player)) return;
        if (rateLimited(player)) return;
        GroupActor actor = players.actor(player);
        if (actor == null) return;

        if (action.type == GroupsAction.Type.REFRESH) {
            push(player, true);
            return;
        }
        GroupActions.Outcome outcome;
        try {
            outcome = dispatch(actor, action);
        } catch (RuntimeException e) {
            if (logger != null) logger.warn("Groups action {} of {} failed", action, player, e);
            outcome = null;
        }

        GroupsResult result;
        if (outcome == null) {
            result = new GroupsResult(action.requestId, false, "error.unknown",
                    players.translate(player, PREFIX + "error.unknown"));
        } else {
            String key = outcome.key != null ? outcome.key : outcome.ok ? "gui.done" : "error.unknown";
            result = new GroupsResult(action.requestId, outcome.ok, key,
                    players.translate(player, PREFIX + key, outcome.args == null ? new Object[0] : outcome.args));
        }
        players.sendResult(player, result);
        // a refused action means the client's view was probably stale: always send the truth
        push(player, outcome == null || !outcome.ok);
    }

    private GroupActions.Outcome dispatch(GroupActor actor, GroupsAction action) {
        switch (action.type) {
            case CREATE:
                return actions.create(actor, action.name, emptyToNull(action.password), nodes(action.permissions),
                        action.persistent == null ? null : String.valueOf(action.persistent));
            case JOIN:
                return actions.join(actor, action.groupId, emptyToNull(action.password));
            case LEAVE:
                return actions.leave(actor);
            case INVITE:
                return actions.invite(actor, GroupActions.Target.byId(action.playerId));
            case KICK:
                return actions.kick(actor, GroupActions.Target.byId(action.playerId));
            case BAN:
                return actions.ban(actor, GroupActions.Target.byId(action.playerId));
            case UNBAN:
                return actions.unban(actor, GroupActions.Target.byId(action.playerId));
            case TRANSFER:
                return actions.transfer(actor, GroupActions.Target.byId(action.playerId));
            case DELETE:
                return actions.delete(actor);
            case UPDATE:
                return actions.update(actor, action.name == null ? null : action.name.trim(),
                        emptyToNull(action.password), action.clearPassword, action.persistent, action.permissions);
            default:
                return null;
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /** A create filter: blank entries dropped; none left means no filter given. */
    private static Set<String> nodes(List<String> permissions) {
        if (permissions == null) return null;
        Set<String> nodes = new LinkedHashSet<>();
        for (String node : permissions) {
            if (node != null && !node.trim().isEmpty()) nodes.add(node.trim());
        }
        return nodes.isEmpty() ? null : nodes;
    }

    private boolean rateLimited(UUID player) {
        long now = clock.getAsLong();
        Window window = windows.get(player);
        if (window == null) {
            window = new Window();
            windows.put(player, window);
        }
        if (now - window.start >= 1000 || now < window.start) {
            window.start = now;
            window.count = 0;
        }
        return ++window.count > MAX_ACTIONS_PER_SECOND;
    }

    // ---- snapshots ----

    /** A player's voice connection was just established: send the state now, whatever was sent before. */
    public void connected(UUID player) {
        lastSent.remove(player);
        push(player, true);
    }

    /** Logout or voice disconnect: forget what was sent. */
    public void disconnected(UUID player) {
        lastSent.remove(player);
        windows.remove(player);
    }

    public void clear() {
        lastSent.clear();
        windows.clear();
    }

    /** Once per server tick: pushes changed snapshots when the groups changed (and now and then regardless). */
    public void tick() {
        long version = manager.version();
        boolean dirty = version != lastVersion;
        if (!dirty && ++idleTicks < SAFETY_NET_TICKS) return;
        idleTicks = 0;
        lastVersion = version;

        Set<UUID> connected = new HashSet<>(players.connected());
        for (UUID player : connected) push(player, false);
        for (Iterator<UUID> it = lastSent.keySet().iterator(); it.hasNext(); ) {
            if (!connected.contains(it.next())) it.remove();
        }
        for (Iterator<UUID> it = windows.keySet().iterator(); it.hasNext(); ) {
            if (!connected.contains(it.next())) it.remove();
        }
    }

    /** @param force send even when the bytes equal the last ones sent */
    private void push(UUID player, boolean force) {
        GroupActor actor = players.actor(player);
        if (actor == null || !players.isConnected(player)) {
            lastSent.remove(player);
            return;
        }
        GroupsState state = GroupsSnapshots.build(manager, platform.settings(), actor,
                id -> platform.onlinePlayer(id) != null);
        byte[] bytes = GroupsProtocol.encode(state);
        byte[] previous = lastSent.get(player);
        if (!force && previous != null && Arrays.equals(previous, bytes)) return;
        lastSent.put(player, bytes);
        players.sendState(player, bytes);
    }

    // exposed for tests
    List<UUID> trackedPlayers() {
        return new ArrayList<>(lastSent.keySet());
    }
}
