package su.plo.voice.platform.forge.debug;

/** Rate limit for periodic diagnostic lines; single-threaded owner. */
public final class DebugInterval {
    private final long intervalMs;
    private long next;

    public DebugInterval(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** True at most once per interval, the first time right away. */
    public boolean due(long now) {
        if (now < next) return false;
        next = now + intervalMs;
        return true;
    }
}
