package su.plo.voice.platform.forge.client.hud;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Session cache of resolved player skins by nickname, without any Minecraft type so it can be tested; {@code T} is the
 * skin texture location. Client thread only.
 *
 * <p>Per nickname: the last live skin seen on the loaded player entity wins and is kept after the player unloads,
 * goes out of range or goes offline. A player never seen with a skin is looked up by nickname once through
 * {@link Lookup}; while the lookup runs or after it failed the caller's fallback is used. A failed lookup is retried
 * only after {@link #RETRY_COOLDOWN_MS} and at most {@link #MAX_LOOKUPS} times per session: an account without a custom
 * skin never gets one by name, so it keeps the default head instead of being looked up forever. Entries are dropped
 * only by {@link #clear()} (disconnect, session reset), never because a player is no longer visible.
 */
public final class SkinCache<T> {
    public static final long RETRY_COOLDOWN_MS = 30_000L;
    /** Lookups per nickname and session; after that many failures the default head is final. */
    public static final int MAX_LOOKUPS = 3;

    /** The asynchronous nickname lookup, the vanilla skin download for the game. */
    public interface Lookup<T> {
        /** Starts (or joins an existing) lookup for the nickname and returns the skin location it fills. */
        T start(String nickname);

        Status status(T skin);

        /** Forgets a failed lookup so {@link #start} downloads again. */
        void discard(T skin);
    }

    public enum Status {
        LOADING,
        LOADED,
        FAILED,
        /** The texture object is not the vanilla downloader (another mod replaced it): used as is, never retried. */
        UNKNOWN
    }

    enum State {
        PENDING,
        RESOLVED,
        FAILED
    }

    static final class Entry<T> {
        /** Last real skin of the loaded player entity. */
        T live;
        /** Skin location of the nickname lookup, null until one was started. */
        T named;
        State state;
        long failedAt;
        int lookups;
    }

    private final Lookup<T> lookup;
    private final LongSupplier clock;
    private final Map<String, Entry<T>> entries = new HashMap<>();

    public SkinCache(Lookup<T> lookup, LongSupplier clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    /**
     * @param nickname the real player name used for skin lookup; null or empty skips the cache
     * @param live     the skin of the loaded player entity, null when the entity is not loaded or has no skin yet
     * @param fallback the default head while nothing is resolved
     */
    public T resolve(String nickname, T live, T fallback) {
        String key = key(nickname);
        if (key == null) return live != null ? live : fallback;
        Entry<T> entry = entries.get(key);
        if (entry == null) {
            entry = new Entry<>();
            entries.put(key, entry);
        }
        if (live != null) {
            entry.live = live;
            return live;
        }
        if (entry.live != null) return entry.live;
        return named(nickname, entry, fallback);
    }

    private T named(String nickname, Entry<T> entry, T fallback) {
        long now = clock.getAsLong();
        if (entry.named == null || entry.state == State.FAILED && entry.lookups < MAX_LOOKUPS
                && now - entry.failedAt >= RETRY_COOLDOWN_MS) {
            if (entry.named != null) lookup.discard(entry.named);
            entry.named = lookup.start(nickname);
            entry.state = State.PENDING;
            entry.lookups++;
        }
        if (entry.state == State.PENDING) {
            Status status = lookup.status(entry.named);
            if (status == Status.LOADED || status == Status.UNKNOWN) {
                entry.state = State.RESOLVED;
            } else if (status == Status.FAILED) {
                entry.state = State.FAILED;
                entry.failedAt = now;
            }
        }
        return entry.state == State.RESOLVED ? entry.named : fallback;
    }

    /** Disconnect or session reset: every nickname is resolved again in the next session. */
    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    Entry<T> entry(String nickname) {
        return entries.get(key(nickname));
    }

    /**
     * The server sends the first 8 UUID characters when it does not know a member's name; that is not an account
     * name, so it must never be looked up (it could fetch an unrelated player's skin).
     */
    public static boolean isPlaceholder(UUID playerId, String nickname) {
        return playerId != null && nickname != null
                && nickname.trim().equalsIgnoreCase(playerId.toString().substring(0, 8));
    }

    /** Minecraft nicknames are case-insensitive; formatting codes are not part of the name. */
    static String key(String nickname) {
        if (nickname == null) return null;
        String name = nickname.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
        return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
    }
}
