package su.plo.voice.platform.forge.client.audio;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Legacy extension, not in upstream: automatic voice leveling of one remote source, on its decoded PCM.
 * Feed-forward: the smoothed speech level of the incoming 20 ms frames (RMS in dBFS; frames under the gate are
 * pauses or noise and change nothing) sets a correction towards the target, capped and ramped across each frame.
 * All channels share one level and one gain, so the stereo image is kept.
 * Playback thread only; one instance per voice source, so every speaker has its own state.
 */
@SideOnly(Side.CLIENT)
public final class VoiceLeveler {
    public static final double DEFAULT_TARGET_DB = -18D;
    public static final double MIN_TARGET_DB = -30D;
    public static final double MAX_TARGET_DB = -10D;
    /** Frames quieter than this are pauses or background noise: the level estimate and the gain hold. */
    static final double GATE_DB = -50D;
    static final double MAX_BOOST_DB = 12D;
    static final double MAX_REDUCTION_DB = 18D;
    /** Level estimate time constants: a louder level is followed quickly (attack), a quieter one slowly (release). */
    static final double ATTACK_MS = 80D;
    static final double RELEASE_MS = 800D;
    private static final double FRAME_MS = 20D;
    private static final double ATTACK = 1D - Math.exp(-FRAME_MS / ATTACK_MS);
    private static final double RELEASE = 1D - Math.exp(-FRAME_MS / RELEASE_MS);
    /** A boost never takes a frame peak above this, about -0.5 dBFS; a cut never needs limiting. */
    static final int CEILING = 31_000;

    /** Smoothed speech level in dBFS; NaN until the first speech frame, which starts it at the target. */
    private double levelDb = Double.NaN;
    /** Linear gain reached at the end of the last frame. */
    private double gain = 1D;
    // Diagnostics since the last describe().
    private long frames;
    private long gatedFrames;
    private long limitedFrames;

    /**
     * Returns the leveled copy of an interleaved frame, or the frame itself when leveling is disabled, which also
     * forgets the state so that enabling it again starts clean.
     */
    short[] process(short[] samples, int channels, boolean enabled, double targetDb) {
        if (!enabled) {
            reset();
            return samples;
        }
        if (samples.length == 0) return samples;

        double frameDb = rmsDb(samples);
        frames++;
        double target = gain;
        if (frameDb < GATE_DB) {
            gatedFrames++;
        } else {
            if (Double.isNaN(levelDb)) levelDb = targetDb;
            levelDb += (frameDb - levelDb) * (frameDb > levelDb ? ATTACK : RELEASE);
            // Anti-windup: the estimate stays where the capped gain can still follow it.
            levelDb = Math.max(targetDb - MAX_BOOST_DB, Math.min(targetDb + MAX_REDUCTION_DB, levelDb));
            target = Math.pow(10D, (targetDb - levelDb) / 20D);
        }
        // Limiter: no boost takes this frame's peak above the ceiling, including the start of the ramp, so a loud
        // onset after a boosted quiet passage is not clipped. A cut (gain up to 1) is never limited.
        int peak = peak(samples);
        double limit = peak > 0 ? Math.max(1D, (double) CEILING / peak) : Double.MAX_VALUE;
        if (target > limit || gain > limit) limitedFrames++;
        target = Math.min(target, limit);

        short[] leveled = new short[samples.length];
        int length = samples.length / channels;
        double start = gain;
        for (int frame = 0; frame < length; frame++) {
            double current = Math.min(limit, start + (target - start) * (frame + 1) / length);
            for (int channel = 0; channel < channels; channel++) {
                int index = frame * channels + channel;
                leveled[index] = saturate(samples[index] * current);
            }
        }
        gain = target;
        return leveled;
    }

    void reset() {
        levelDb = Double.NaN;
        gain = 1D;
    }

    double gainDb() {
        return 20D * Math.log10(gain);
    }

    double levelDb() {
        return levelDb;
    }

    /** Debug summary; resets the frame counters. */
    String describe(boolean enabled, double targetDb) {
        if (!enabled) return "off";
        String text = String.format("level=%.1fdB, target=%.0fdB, gain=%+.1fdB, gatedFrames=%d/%d, limitedFrames=%d",
                levelDb, targetDb, gainDb(), gatedFrames, frames, limitedFrames);
        frames = 0;
        gatedFrames = 0;
        limitedFrames = 0;
        return text;
    }

    /** RMS of all channels together, in dBFS; -127 for digital silence. */
    static double rmsDb(short[] samples) {
        double sum = 0D;
        for (short sample : samples) sum += (double) sample * sample;
        double rms = Math.sqrt(sum / samples.length) / 32768D;
        return rms <= 0D ? -127D : 20D * Math.log10(rms);
    }

    private static int peak(short[] samples) {
        int peak = 0;
        for (short sample : samples) peak = Math.max(peak, Math.abs((int) sample));
        return peak;
    }

    private static short saturate(double value) {
        long rounded = Math.round(value);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
    }
}
