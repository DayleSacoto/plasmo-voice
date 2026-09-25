package su.plo.voice.platform.forge.debug;

/**
 * Diagnostic-only detection of inbound traffic silence: it only decides what to log and never touches the
 * connection. Traffic is recorded from any thread; {@link #check} runs on one owner thread.
 */
public final class SilenceMonitor {
    public enum Event { NONE, SILENT, STILL_SILENT, RECOVERED }

    private final long thresholdMs;
    private final long repeatMs;
    private volatile long lastTraffic;
    private long silentSince = -1L;
    private long lastWarning;
    private long recoveredAfterMs;

    public SilenceMonitor(long thresholdMs, long repeatMs) {
        this.thresholdMs = thresholdMs;
        this.repeatMs = repeatMs;
    }

    /** Starts the silence clock, e.g. when the connection became usable. */
    public void reset(long now) {
        lastTraffic = now;
        silentSince = -1L;
    }

    public void traffic(long now) {
        lastTraffic = now;
    }

    /** Silence age for the last SILENT/STILL_SILENT event, or the outage length for RECOVERED. */
    public long age(long now) {
        return silentSince >= 0 ? now - lastTraffic : recoveredAfterMs;
    }

    public Event check(long now) {
        long last = lastTraffic;
        if (last <= 0L) return Event.NONE;
        if (silentSince >= 0) {
            if (last > silentSince) {
                recoveredAfterMs = last - silentSince;
                silentSince = -1L;
                return Event.RECOVERED;
            }
            if (now - lastWarning < repeatMs) return Event.NONE;
            lastWarning = now;
            return Event.STILL_SILENT;
        }
        if (now - last < thresholdMs) return Event.NONE;
        silentSince = last;
        lastWarning = now;
        return Event.SILENT;
    }
}
