package su.plo.voice.platform.forge.client.audio;

import java.util.Collections;
import java.util.UUID;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.debug.VoiceDebug;

/**
 * Upstream VoiceAudioCapture.run for one frame read from the microphone: the activations see the raw frame, the
 * input filters (StereoToMonoFilter, GainFilter, NoiseSuppressionFilter) run on copies, only for what is sent or
 * shown (EncodedCapture). Capture thread only.
 */
@SideOnly(Side.CLIENT)
final class CapturePipeline implements AutoCloseable {
    /** Encoding and delivery of what the activations decide; {@link CaptureActivations.Sink} minus the frame cache. */
    interface Output {
        /** Encodes and encrypts a processed mono frame; null when that failed. */
        byte[] encode(short[] mono);

        void audio(CaptureActivations.Slot slot, long sequenceNumber, byte[] data);

        void end(CaptureActivations.Slot slot, long sequenceNumber, boolean lastStream);
    }

    private final ClientState state;
    final CaptureActivations activations;
    private final CaptureActivations.Settings settings;
    private final MicrophoneGain gain = new MicrophoneGain();
    private final NoiseSuppression noiseSuppression;
    private final Output output;
    /** Loudest processed frame level since the last reset; computed only while debug logging is enabled. */
    double processedLevel = -127D;

    CapturePipeline(ClientState state, NoiseSuppression noiseSuppression, CaptureActivations activations, Output output) {
        this.state = state;
        this.noiseSuppression = noiseSuppression;
        this.activations = activations;
        this.output = output;
        this.settings = new StateSettings();
    }

    /**
     * {@code channels} is the channel count of {@code raw}. Returns the result of the proximity (parent) activation,
     * or null when the activations were flushed: microphone test, muted or disabled microphone, voice disabled,
     * server mute.
     */
    CaptureActivation.Result process(short[] raw, int channels, boolean serverMuted, long now) {
        Processed processed = new Processed(raw, channels);
        MicrophoneTest test = state.getMicrophoneTest();
        FrameSink sink = new FrameSink(processed);
        CaptureActivation.Result result = null;
        if (test.isActive() || state.isMicrophoneMuted() || state.isVoiceDisabled() || serverMuted) {
            activations.flush(sink);
            state.setActiveActivations(Collections.<UUID>emptySet());
        } else {
            result = activations.process(raw, now, settings, sink);
            state.setActiveActivations(activations.activeIds());
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

    /** The per-activation client settings, read by name like the settings file stores them. */
    private final class StateSettings implements CaptureActivations.Settings {
        @Override
        public CaptureActivation.Type type(CaptureActivations.Slot slot) {
            return state.getActivationType(slot.name);
        }

        @Override
        public boolean toggled(CaptureActivations.Slot slot) {
            return state.isActivationToggled(slot.name);
        }

        @Override
        public boolean pushToTalkPressed(CaptureActivations.Slot slot) {
            return state.isPushToTalkPressed(slot.name);
        }

        @Override
        public double threshold() {
            return state.getActivationThreshold();
        }
    }

    /** One frame of upstream EncodedCapture.mono: encoded on first use, then shared by every activation sending. */
    private final class FrameSink implements CaptureActivations.Sink {
        private final Processed processed;
        private boolean encodeTried;
        private byte[] encoded;

        FrameSink(Processed processed) {
            this.processed = processed;
        }

        @Override
        public byte[] encode() {
            if (!encodeTried) {
                encodeTried = true;
                encoded = output.encode(processed.mono());
            }
            return encoded;
        }

        @Override
        public byte[] encoded() {
            return encoded;
        }

        @Override
        public void audio(CaptureActivations.Slot slot, long sequenceNumber, byte[] data) {
            output.audio(slot, sequenceNumber, data);
        }

        @Override
        public void end(CaptureActivations.Slot slot, long sequenceNumber, boolean lastStream) {
            output.end(slot, sequenceNumber, lastStream);
        }
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
