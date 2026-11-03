package su.plo.voice.platform.forge.network.groups;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players announced Groups support on their current Minecraft connection, and the Groups protocol version
 * agreed with each. Session-only: nothing here is persisted, an entry belongs to one connection and is dropped when
 * that connection logs out. A player without an entry is not Groups-capable (an older client, or a client that has
 * not announced yet). Thread-safe: announcements arrive on netty threads, everything else reads on the server thread.
 *
 * @param <C> the connection an announcement came from, so a stale logout never clears a newer connection's entry
 */
public final class GroupsCapabilities<C> {
    /** Oldest and newest Groups protocol this build can speak. */
    public static final int MIN_PROTOCOL = 1;
    public static final int MAX_PROTOCOL = 1;

    private static final class Entry<C> {
        final C connection;
        final int protocol;

        Entry(C connection, int protocol) {
            this.connection = connection;
            this.protocol = protocol;
        }
    }

    private final Map<UUID, Entry<C>> entries = new ConcurrentHashMap<>();

    /**
     * The highest protocol both sides speak, or 0 when the ranges do not overlap. Future versions are added by
     * raising {@link #MAX_PROTOCOL} and encoding per session; an older peer keeps getting the version it announced.
     */
    public static int negotiate(int clientMin, int clientMax) {
        if (clientMin < 1 || clientMax < clientMin) return 0;
        int protocol = Math.min(clientMax, MAX_PROTOCOL);
        return protocol >= Math.max(clientMin, MIN_PROTOCOL) ? protocol : 0;
    }

    /**
     * Records an announcement. A client whose range does not overlap ours is recorded as unsupported (no entry).
     *
     * @return the agreed protocol when this connection became Groups-capable just now, else 0 (already known,
     *         or no common protocol)
     */
    public int announce(UUID player, C connection, int clientMin, int clientMax) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(connection);
        int protocol = negotiate(clientMin, clientMax);
        if (protocol == 0) {
            remove(player, connection);
            return 0;
        }
        Entry<C> previous = entries.put(player, new Entry<>(connection, protocol));
        return previous != null && previous.connection == connection && previous.protocol == protocol ? 0 : protocol;
    }

    /** The agreed Groups protocol of the player's current connection; 0 when not Groups-capable. */
    public int protocol(UUID player) {
        Entry<C> entry = player == null ? null : entries.get(player);
        return entry == null ? 0 : entry.protocol;
    }

    public boolean supports(UUID player) {
        return protocol(player) > 0;
    }

    /** Logout: forgets the entry of that connection only (a newer connection of the same player keeps its own). */
    public void remove(UUID player, C connection) {
        Entry<C> entry = entries.get(player);
        if (entry != null && entry.connection == connection) entries.remove(player, entry);
    }

    public void clear() {
        entries.clear();
    }
}
