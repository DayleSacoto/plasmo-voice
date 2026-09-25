package su.plo.voice.platform.forge.debug;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic UDP counters and timestamps of one endpoint (the client) or one session (the server). Updated only while
 * debug logging is enabled, from the UDP worker and senders; read for snapshots. Timestamps are
 * System.currentTimeMillis(), like the keep-alive code they describe.
 */
public final class UdpStats {
    public enum Kind { PING, AUDIO, OTHER }

    public final AtomicLong rx = new AtomicLong();
    public final AtomicLong tx = new AtomicLong();
    public final AtomicLong pingRx = new AtomicLong();
    public final AtomicLong pingTx = new AtomicLong();
    public final AtomicLong audioRx = new AtomicLong();
    public final AtomicLong audioTx = new AtomicLong();
    public final AtomicLong failedTx = new AtomicLong();
    public final AtomicLong malformed = new AtomicLong();
    public final AtomicLong ignored = new AtomicLong();
    public volatile long lastRx;
    public volatile long lastTx;
    public volatile long lastPingRx;
    public volatile long lastPingTx;
    public volatile long lastAudioRx;
    public volatile long lastAudioTx;

    public void received(Kind kind, long now) {
        rx.incrementAndGet();
        lastRx = now;
        if (kind == Kind.PING) {
            pingRx.incrementAndGet();
            lastPingRx = now;
        } else if (kind == Kind.AUDIO) {
            audioRx.incrementAndGet();
            lastAudioRx = now;
        }
    }

    public void sent(Kind kind, long now) {
        tx.incrementAndGet();
        lastTx = now;
        if (kind == Kind.PING) {
            pingTx.incrementAndGet();
            lastPingTx = now;
        } else if (kind == Kind.AUDIO) {
            audioTx.incrementAndGet();
            lastAudioTx = now;
        }
    }

    /** Ages in ms (-1 = never) and totals, for one snapshot line. */
    public String snapshot(long now) {
        return "rxAge=" + VoiceDebug.age(now, lastRx) + "ms txAge=" + VoiceDebug.age(now, lastTx)
                + "ms pingRxAge=" + VoiceDebug.age(now, lastPingRx) + "ms pingTxAge=" + VoiceDebug.age(now, lastPingTx)
                + "ms audioRxAge=" + VoiceDebug.age(now, lastAudioRx) + "ms audioTxAge=" + VoiceDebug.age(now, lastAudioTx)
                + "ms rx=" + rx.get() + " tx=" + tx.get() + " pingRx=" + pingRx.get() + " pingTx=" + pingTx.get()
                + " audioRx=" + audioRx.get() + " audioTx=" + audioTx.get() + " failedTx=" + failedTx.get()
                + " malformed=" + malformed.get() + " ignored=" + ignored.get();
    }
}
