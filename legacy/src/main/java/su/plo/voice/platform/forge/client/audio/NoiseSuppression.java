package su.plo.voice.platform.forge.client.audio;

import com.plasmoverse.rnnoise.Denoise;
import com.plasmoverse.rnnoise.DenoiseException;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import su.plo.voice.platform.forge.audio.codec.OpusCodec;
import su.plo.voice.platform.forge.client.ClientState;

/**
 * Upstream NoiseSuppressionFilter: RNNoise on the processed microphone frame, one denoiser per channel of a stereo
 * frame (the left one also serves mono). Upstream also runs a limiter first but discards its output, so only the
 * denoiser changes the audio. Capture thread only.
 */
@SideOnly(Side.CLIENT)
final class NoiseSuppression implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");

    private final ClientState state;
    private Denoise denoise;
    private Denoise rightDenoise;

    NoiseSuppression(ClientState state) {
        this.state = state;
        // Upstream turns the entry off and disables it where the natives cannot run.
        if (!OpusCodec.isNativesSupported()) unavailable();
    }

    short[] process(short[] samples) {
        return process(samples, 1);
    }

    short[] process(short[] samples, int channels) {
        if (!state.isNoiseSuppression() || !state.isNoiseSuppressionAvailable()) {
            close();
            return samples;
        }
        if (denoise == null) {
            try {
                denoise = Denoise.create();
                rightDenoise = Denoise.create();
            } catch (Exception | LinkageError e) {
                LOGGER.error("RNNoise is not available on this platform", e);
                unavailable();
                return samples;
            }
        }
        try {
            if (channels == 1) return toShorts(denoise.process(toFloats(samples)));
            return interleave(toShorts(denoise.process(toFloats(channel(samples, 0)))),
                    toShorts(rightDenoise.process(toFloats(channel(samples, 1)))));
        } catch (DenoiseException e) {
            throw new IllegalStateException("Failed to denoise audio samples", e);
        }
    }

    @Override
    public void close() {
        if (denoise != null) denoise.close();
        if (rightDenoise != null) rightDenoise.close();
        denoise = null;
        rightDenoise = null;
    }

    /** One channel of an interleaved stereo frame. */
    static short[] channel(short[] samples, int offset) {
        short[] channel = new short[samples.length / 2];
        for (int i = 0; i < channel.length; i++) channel[i] = samples[i * 2 + offset];
        return channel;
    }

    static short[] interleave(short[] left, short[] right) {
        short[] samples = new short[left.length + right.length];
        for (int i = 0; i < left.length && i < right.length; i++) {
            samples[i * 2] = left[i];
            samples[i * 2 + 1] = right[i];
        }
        return samples;
    }

    private void unavailable() {
        close();
        state.setNoiseSuppression(false);
        state.setNoiseSuppressionAvailable(false);
    }

    /** Upstream AudioUtil.shortsToFloats: RNNoise takes the 16-bit sample values as floats. */
    static float[] toFloats(short[] samples) {
        float[] floats = new float[samples.length];
        for (int i = 0; i < samples.length; i++) floats[i] = samples[i];
        return floats;
    }

    /** Upstream AudioUtil.floatsToShorts: clamped to the 16-bit range. */
    static short[] toShorts(float[] floats) {
        short[] samples = new short[floats.length];
        for (int i = 0; i < floats.length; i++) {
            samples[i] = (short) Math.min(Short.MAX_VALUE, Math.max(Short.MIN_VALUE, floats[i]));
        }
        return samples;
    }
}
