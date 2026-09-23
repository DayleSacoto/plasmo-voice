package su.plo.voice.platform.forge.client.audio;

/** Upstream VoiceClientActivation for the proximity activation: push-to-talk or voice detection. */
public final class CaptureActivation {
    static final long PUSH_TO_TALK_RELEASE_MS = 350L;
    static final long VOICE_HOLD_MS = 500L;

    public enum Type {
        PUSH_TO_TALK,
        VOICE
    }

    public enum Result {
        NOT_ACTIVATED,
        ACTIVATED,
        END
    }

    private boolean active;
    private long lastActivation;

    /** {@code toggled} is upstream ConfigClientActivation.configToggle: it switches voice activation off. */
    public Result process(short[] samples, Type type, boolean toggled, boolean pushToTalkPressed, double thresholdDb, long now) {
        if (type == Type.VOICE && toggled) {
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
