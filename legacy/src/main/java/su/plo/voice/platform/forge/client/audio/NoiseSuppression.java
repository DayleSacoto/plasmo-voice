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
 * Upstream NoiseSuppressionFilter: RNNoise on the processed mono microphone frame. Upstream also runs a limiter
 * first but discards its output, so only the denoiser changes the audio. Capture thread only.
 */
@SideOnly(Side.CLIENT)
final class NoiseSuppression implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");

    private final ClientState state;
    private Denoise denoise;

    NoiseSuppression(ClientState state) {
        this.state = state;
        // Upstream turns the entry off and disables it where the natives cannot run.
        if (!OpusCodec.isNativesSupported()) unavailable();
    }

    short[] process(short[] samples) {
        if (!state.isNoiseSuppression() || !state.isNoiseSuppressionAvailable()) {
            close();
            return samples;
        }
        if (denoise == null) {
            try {
                denoise = Denoise.create();
            } catch (Exception | LinkageError e) {
                LOGGER.error("RNNoise is not available on this platform", e);
                unavailable();
                return samples;
            }
        }
        try {
            return toShorts(denoise.process(toFloats(samples)));
        } catch (DenoiseException e) {
            throw new IllegalStateException("Failed to denoise audio samples", e);
        }
    }

    @Override
    public void close() {
        if (denoise != null) denoise.close();
        denoise = null;
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
