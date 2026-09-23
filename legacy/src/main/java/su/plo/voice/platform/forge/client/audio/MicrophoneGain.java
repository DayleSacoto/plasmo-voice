package su.plo.voice.platform.forge.client.audio;

import java.util.Arrays;

/**
 * Upstream GainFilter: applies the microphone volume but never more than the loudest sample allows,
 * using the smallest safe multiplier of the last 48 frames so the level does not pump.
 */
final class MicrophoneGain {
    private final float[] recent = new float[48];
    private int next;

    MicrophoneGain() {
        Arrays.fill(recent, -1F);
    }

    short[] process(short[] samples, float volume) {
        int highest = 0;
        for (short sample : samples) highest = Math.max(highest, Math.abs((int) sample));
        if (highest > 0) volume = Math.min(volume, (float) (Short.MAX_VALUE - 1) / (float) highest);

        recent[next] = volume;
        next = (next + 1) % recent.length;
        for (float value : recent) {
            if (value >= 0F) volume = Math.min(volume, value);
        }

        for (int i = 0; i < samples.length; i++) samples[i] = (short) (samples[i] * volume);
        return samples;
    }
}
