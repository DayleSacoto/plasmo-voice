package su.plo.voice.platform.forge.client.audio;

/**
 * Upstream VoiceClientActivation.process: push-to-talk, voice detection or, for non-proximity activations, inheriting
 * the result of the parent (proximity) activation.
 */
public final class CaptureActivation {
    static final long PUSH_TO_TALK_RELEASE_MS = 350L;
    static final long VOICE_HOLD_MS = 500L;

    /** Upstream ClientActivation.Type; the proximity activation can never be {@link #INHERIT}. */
    public enum Type {
        PUSH_TO_TALK,
        VOICE,
        INHERIT
    }

    public enum Result {
        NOT_ACTIVATED,
        ACTIVATED,
        END
    }

    private boolean active;
    private long lastActivation;

    /** The proximity activation, which has no parent result. {@code toggled} is upstream configToggle. */
    public Result process(short[] samples, Type type, boolean toggled, boolean pushToTalkPressed, double thresholdDb, long now) {
        return process(samples, null, type, toggled, pushToTalkPressed, thresholdDb, now);
    }

    /**
     * Upstream VoiceClientActivation.process(samples, parentResult). {@code toggled} switches a voice or inherit
     * activation off (upstream isDisabled); push-to-talk ignores it.
     */
    public Result process(short[] samples, Result parentResult, Type type, boolean toggled, boolean pushToTalkPressed,
                          double thresholdDb, long now) {
        if (isDisabled(type, toggled)) {
            if (!active) return Result.NOT_ACTIVATED;
            active = false;
            return Result.END;
        }
        if (type == Type.PUSH_TO_TALK) {
            if (pushToTalkPressed) {
                active = true;
                lastActivation = now;
            } else if (active && now - lastActivation > PUSH_TO_TALK_RELEASE_MS) {
                active = false;
                return Result.END;
            }
            return active ? Result.ACTIVATED : Result.NOT_ACTIVATED;
        }
        if (type == Type.INHERIT) {
            if (parentResult == null) return Result.NOT_ACTIVATED;
            active = parentResult == Result.ACTIVATED;
            if (active) lastActivation = now;
            return parentResult;
        }

        // Voice: the parent's start or end decides first, then the activation's own detection.
        if (parentResult == Result.ACTIVATED) {
            active = true;
            lastActivation = now;
            return parentResult;
        } else if (parentResult == Result.END) {
            active = false;
            return parentResult;
        }
        boolean lastActivated = now - lastActivation <= VOICE_HOLD_MS;
        boolean voiceDetected = containsMinAudioLevel(samples, thresholdDb);
        if (lastActivated || voiceDetected) {
            if (voiceDetected) lastActivation = now;
            active = true;
            return Result.ACTIVATED;
        }
        if (active) {
            active = false;
            return Result.END;
        }
        return Result.NOT_ACTIVATED;
    }

    /** Upstream VoiceClientActivation.isDisabled: the toggle switches voice and inherit activations off. */
    public static boolean isDisabled(Type type, boolean toggled) {
        return toggled && type != Type.PUSH_TO_TALK;
    }

    public boolean isActive() {
        return active;
    }

    public void reset() {
        active = false;
        lastActivation = 0L;
    }

    /** Upstream AudioUtil.containsMinAudioLevel: 50-sample windows checked against the threshold. */
    static boolean containsMinAudioLevel(short[] samples, double minAudioLevel) {
        for (int i = 0; i < samples.length; i += 50) {
            if (calculateAudioLevel(samples, i, Math.min(i + 50, samples.length)) >= minAudioLevel) return true;
        }
        return false;
    }

    /** Upstream AudioUtil.calculateHighestAudioLevel over the same 50-sample windows. */
    static double highestAudioLevel(short[] samples) {
        double highest = -127D;
        for (int i = 0; i < samples.length; i += 50) {
            highest = Math.max(highest, calculateAudioLevel(samples, i, Math.min(i + 50, samples.length)));
        }
        return highest;
    }

    /** Upstream quirk kept: the window's squared sum is averaged over the whole frame length. */
    static double calculateAudioLevel(short[] samples, int offset, int end) {
        double rms = 0D;
        for (int i = offset; i < end; i++) {
            double sample = (double) samples[i] / Short.MAX_VALUE;
            rms += sample * sample;
        }
        rms = samples.length == 0 ? 0 : Math.sqrt(rms / samples.length);
        return rms > 0D ? Math.min(Math.max(20D * Math.log10(rms), -127D), 0D) : -127D;
    }
}
