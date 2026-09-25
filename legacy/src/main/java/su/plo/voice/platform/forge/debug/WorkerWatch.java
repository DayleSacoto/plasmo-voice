package su.plo.voice.platform.forge.debug;

import su.plo.voice.platform.forge.debug.VoiceDebug.Category;

/**
 * Diagnostic-only stall detection for a worker loop: the worker records a heartbeat each iteration and another
 * thread checks it, so a blocked or dead worker is reported even though it cannot log itself. Never touches the
 * worker. {@link #beat} runs on the worker, {@link #check} on one watching thread.
 */
public final class WorkerWatch {
    private static final int STACK_FRAMES = 8;

    private final String name;
    private final long thresholdMs;
    private final DebugInterval checks = new DebugInterval(1_000L);
    private volatile long lastBeat;
    private boolean stalled;
    private boolean deadReported;

    public WorkerWatch(String name, long thresholdMs) {
        this.name = name;
        this.thresholdMs = thresholdMs;
    }

    public void beat(long now) {
        lastBeat = now;
    }

    public void check(VoiceDebug debug, Thread worker, boolean closed, long now) {
        if (closed || worker == null || !checks.due(now)) return;
        long last = lastBeat;
        if (last <= 0L) return;
        long age = now - last;
        if (!worker.isAlive()) {
            if (!deadReported) {
                deadReported = true;
                debug.warn(Category.THREAD, "{} worker is not running although its endpoint is open: lastLoopAge={}ms",
                        name, age);
            }
            return;
        }
        if (age > thresholdMs && !stalled) {
            stalled = true;
            debug.warn(Category.THREAD, "{} worker stalled: lastLoopAge={}ms, state={}, at {}", name, age, worker.getState(),
                    stack(worker));
        } else if (age <= thresholdMs && stalled) {
            stalled = false;
            debug.log(Category.THREAD, "{} worker resumed", name);
        }
    }

    private static String stack(Thread worker) {
        StackTraceElement[] frames = worker.getStackTrace();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(STACK_FRAMES, frames.length); i++) {
            if (i > 0) builder.append(" <- ");
            builder.append(frames[i]);
        }
        return builder.length() == 0 ? "(no frames)" : builder.toString();
    }
}
