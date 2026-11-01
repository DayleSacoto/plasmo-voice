package su.plo.voice.platform.forge.network.groups;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many decoded Groups actions one connection may have waiting for the server thread. Netty threads acquire, the
 * server thread releases once the action ran (or was dropped), so one flooding client cannot fill the shared queue and
 * push out the actions of everybody else. The per-player rate limit of the GUI service still applies afterwards.
 *
 * @param <K> the connection key (the NetworkManager in game)
 */
final class ConnectionQuota<K> {
    private final int limit;
    private final ConcurrentHashMap<K, AtomicInteger> queued = new ConcurrentHashMap<>();

    ConnectionQuota(int limit) {
        this.limit = limit;
    }

    /** @return true when the connection may queue one more action; it must then be {@link #release}d exactly once */
    boolean tryAcquire(K connection) {
        AtomicInteger count = queued.computeIfAbsent(connection, key -> new AtomicInteger());
        if (count.incrementAndGet() > limit) {
            count.decrementAndGet();
            return false;
        }
        return true;
    }

    void release(K connection) {
        AtomicInteger count = queued.get(connection);
        if (count == null) return;
        if (count.decrementAndGet() <= 0) queued.remove(connection, count);
    }

    int queued(K connection) {
        AtomicInteger count = queued.get(connection);
        return count == null ? 0 : Math.max(0, count.get());
    }

    void clear() {
        queued.clear();
    }
}
