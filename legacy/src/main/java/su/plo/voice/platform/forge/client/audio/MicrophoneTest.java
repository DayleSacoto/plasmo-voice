package su.plo.voice.platform.forge.client.audio;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Upstream MicrophoneTestController: the level meter behind the activation threshold slider and the loopback test.
 * The capture thread reports every processed frame; the playback thread drains the loopback frames.
 */
public final class MicrophoneTest {
    /** 200 ms of 20 ms frames; the oldest frame is dropped when playback falls behind. */
    private static final int LOOPBACK_CAPACITY = 10;
    /** Upstream tick: the meter falls 0.04 per tick. */
    private static final double DECAY_PER_MS = 0.04D / 50D;

    private final ArrayBlockingQueue<short[]> loopback = new ArrayBlockingQueue<>(LOOPBACK_CAPACITY);
    private volatile boolean active;
    /** Upstream enables the test only while the input device is open. */
    private volatile boolean inputOpen;
    private volatile double peak;
    private volatile long peakTime;

    public void start() {
        loopback.clear();
        active = true;
    }

    public void stop() {
        active = false;
        loopback.clear();
    }

    /** While active, upstream flushes the capture activations: nothing is sent to the server. */
    public boolean isActive() {
        return active;
    }

    public boolean isInputOpen() {
        return inputOpen;
    }

    /** A reopened device continues the running test, like upstream's restart on a device change. */
    void setInputOpen(boolean inputOpen) {
        this.inputOpen = inputOpen;
    }

    /** Capture thread: the processed microphone frame. */
    void onCaptured(short[] samples, long now) {
        double level = CaptureActivation.highestAudioLevel(samples);
        // Upstream AudioUtil.audioLevelToDoubleRange.
        double value = 1D - Math.max(-60D, level) / -60D;
        if (level > -60D && value > value(now)) {
            peak = value;
            peakTime = now;
        }
        if (active) {
            while (!loopback.offer(samples)) loopback.poll();
        }
    }

    /** The meter value 0..1. */
    public double value(long now) {
        return Math.max(0D, peak - (now - peakTime) * DECAY_PER_MS);
    }

    /** Playback thread. */
    short[] poll() {
        return loopback.poll();
    }
}
