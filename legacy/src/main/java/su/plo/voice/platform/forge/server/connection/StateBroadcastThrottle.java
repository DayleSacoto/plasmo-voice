package su.plo.voice.platform.forge.server.connection;

/** Upstream PlayerChannelHandler throttle: one PlayerInfoUpdate per 250 ms, the trailing change is kept. */
final class StateBroadcastThrottle {
    static final long INTERVAL_MS = 250L;

    private long lastBroadcast = -INTERVAL_MS;
    private boolean pending;

    /** Returns true when the change should be broadcast now; otherwise it is flushed later by {@link #due}. */
    boolean changed(long now) {
        if (now - lastBroadcast >= INTERVAL_MS) {
            lastBroadcast = now;
            pending = false;
            return true;
        }
        pending = true;
        return false;
    }

    boolean due(long now) {
        if (!pending || now - lastBroadcast < INTERVAL_MS) return false;
        pending = false;
        lastBroadcast = now;
        return true;
    }
}
