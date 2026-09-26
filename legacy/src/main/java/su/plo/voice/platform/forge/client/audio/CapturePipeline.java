package su.plo.voice.platform.forge.client.audio;

import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.debug.VoiceDebug;

/**
 * Upstream VoiceAudioCapture.run for one frame read from the microphone: the activation sees the raw frame, the
 * input filters (StereoToMonoFilter, GainFilter, NoiseSuppressionFilter) run on copies, only for what is sent or
 * shown (EncodedCapture). Capture thread only.
 */
@SideOnly(Side.CLIENT)
final class CapturePipeline implements AutoCloseable {
    private final ClientState state;
    final CaptureActivation activation = new CaptureActivation();
    private final MicrophoneGain gain = new MicrophoneGain();
    private final NoiseSuppression noiseSuppression;
    /** Processed mono frames of the proximity activation, to be encoded and sent. */
    private final Consumer<short[]> frames;
    private final Runnable ends;
    /** Loudest processed frame level since the last reset; computed only while debug logging is enabled. */
    double processedLevel = -127D;

    CapturePipeline(ClientState state, NoiseSuppression noiseSuppression, Consumer<short[]> frames, Runnable ends) {
        this.state = state;
        this.noiseSuppression = noiseSuppression;
        this.frames = frames;
        this.ends = ends;
    }

    /**
     * {@code channels} is the channel count of {@code raw}. Returns the activation result, or null when the
     * activations were flushed: microphone test, muted or disabled microphone, voice disabled, server mute.
     */
    CaptureActivation.Result process(short[] raw, int channels, boolean serverMuted, long now) {
        Processed processed = new Processed(raw, channels);
        MicrophoneTest test = state.getMicrophoneTest();
        CaptureActivation.Result result = null;
        if (test.isActive() || state.isMicrophoneMuted() || state.isVoiceDisabled() || serverMuted) {
            if (activation.isActive()) {
                activation.reset();
                ends.run();
            }
            state.setActivationActive(false);
        } else {
            result = activation.process(raw, state.getActivationType(), state.isActivationToggled(),
                    state.isPushToTalkPressed(), state.getActivationThreshold(), now);
            state.setActivationActive(activation.isActive());
            if (result == CaptureActivation.Result.ACTIVATED) {
                frames.accept(processed.mono());
            } else if (result == CaptureActivation.Result.END) {
                ends.run();
            }
        }
        // Upstream MicrophoneTestController.onAudioCaptureProcessed, subscribed while the settings screen is open:
        // the processed frame, in stereo with stereo capture (isStereoSupported; stereo_sources_to_mono is off).
        if (test.isListening()) test.onCaptured(state.isStereoCapture() ? processed.stereo() : processed.mono(), now);
        return result;
    }

    @Override
    public void close() {
        noiseSuppression.close();
    }

    /** Upstream EncodedCapture: lazily filtered copies of the raw frame; mono and stereo share the filter state. */
    private final class Processed {
        private final short[] raw;
        private final int channels;
        private short[] mono;
        private short[] stereo;

        Processed(short[] raw, int channels) {
            this.raw = raw;
            this.channels = channels;
        }

        short[] mono() {
            // StereoToMonoFilter applies only to a two channel frame.
            if (mono == null) mono = filter(channels == 2 ? VoiceCapture.toMono(raw) : raw.clone(), 1);
            return mono;
        }

        /** Every filter but the downmix. */
        short[] stereo() {
            if (stereo == null) stereo = filter(raw.clone(), channels);
            return stereo;
        }

        private short[] filter(short[] samples, int channels) {
            gain.process(samples, (float) state.getMicrophoneVolume());
            short[] filtered = noiseSuppression.process(samples, channels);
            if (VoiceDebug.CLIENT.enabled()) {
                processedLevel = Math.max(processedLevel, CaptureActivation.highestAudioLevel(filtered));
            }
            return filtered;
        }
    }
}
