package su.plo.voice.platform.forge.client.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import su.plo.voice.platform.forge.client.ClientState;

import static org.junit.Assert.*;

/**
 * Upstream VoiceAudioCapture ordering: the activation decides on the raw frame, the encoder and the microphone test
 * get the filtered copy. Raw and processed levels differ on purpose, so either reordering fails a test.
 */
public class CapturePipelineTest {
    private static final int FRAME = 960;
    private static final long NOW = 1_000_000L;

    private final ClientState state = new ClientState();
    private final List<short[]> sent = new ArrayList<>();
    private int ends;
    private final CapturePipeline pipeline = new CapturePipeline(state, new NoiseSuppression(state), sent::add, () -> ends++);

    {
        state.setNoiseSuppression(false);
        state.setActivationType(CaptureActivation.Type.VOICE);
        state.setActivationThreshold(-30D);
    }

    @Test
    public void loudRawFrameActivatesAndTheQuietProcessedFrameIsSent() {
        // Raw about -19 dB; the microphone volume takes the processed frame down to about -45 dB.
        state.setMicrophoneVolume(0.05D);
        short[] raw = constant(FRAME, 16_000);
        assertTrue(CaptureActivation.highestAudioLevel(raw) > -30D);

        assertEquals(CaptureActivation.Result.ACTIVATED, pipeline.process(raw, 1, false, NOW));
        assertEquals(1, sent.size());
        short[] frame = sent.get(0);
        assertEquals(FRAME, frame.length);
        assertEquals(800, frame[0]);
        assertTrue(CaptureActivation.highestAudioLevel(frame) < -30D);
        // The raw frame itself is never filtered in place.
        assertEquals(16_000, raw[0]);
    }

    @Test
    public void quietRawFrameDoesNotActivateEvenWhenGainWouldLiftIt() {
        // Raw about -33 dB; doubled it would pass the -30 dB threshold.
        state.setMicrophoneVolume(2D);
        short[] raw = constant(FRAME, 3_200);
        assertTrue(CaptureActivation.highestAudioLevel(raw) < -30D);
        short[] doubled = constant(FRAME, 6_400);
        assertTrue(CaptureActivation.highestAudioLevel(doubled) > -30D);

        assertEquals(CaptureActivation.Result.NOT_ACTIVATED, pipeline.process(raw, 1, false, NOW));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void stereoCaptureActivatesOnTheRawStereoFrameAndSendsTheDownmix() {
        state.setStereoCapture(true);
        state.setMicrophoneVolume(0.05D);
        // Opposite channels: loud in stereo, silent once StereoToMonoFilter averages them.
        short[] raw = new short[FRAME * 2];
        for (int i = 0; i < raw.length; i += 2) {
            raw[i] = 16_000;
            raw[i + 1] = -16_000;
        }
        assertTrue(CaptureActivation.highestAudioLevel(raw) > -30D);

        assertEquals(CaptureActivation.Result.ACTIVATED, pipeline.process(raw, 2, false, NOW));
        short[] frame = sent.get(0);
        assertEquals(FRAME, frame.length);
        assertTrue(Arrays.equals(new short[FRAME], frame));
    }

    @Test
    public void microphoneTestSeesTheProcessedFrameOnlyWhileTheSettingsAreOpen() {
        state.setMicrophoneVolume(0.05D);
        MicrophoneTest test = state.getMicrophoneTest();
        test.start();
        pipeline.process(constant(FRAME, 16_000), 1, false, NOW);
        assertNull("settings screen closed: nothing is processed for the test", test.poll());

        test.setListening(true);
        assertNull(pipeline.process(constant(FRAME, 16_000), 1, false, NOW)); // the test flushes the activation
        short[] looped = test.poll();
        assertNotNull(looped);
        assertEquals(800, looped[0]);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void stereoMicrophoneTestKeepsBothChannels() {
        state.setStereoCapture(true);
        state.setMicrophoneVolume(0.05D);
        MicrophoneTest test = state.getMicrophoneTest();
        test.setListening(true);
        test.start();
        short[] raw = new short[FRAME * 2];
        for (int i = 0; i < raw.length; i += 2) {
            raw[i] = 16_000;
            raw[i + 1] = -16_000;
        }
        pipeline.process(raw, 2, false, NOW);
        short[] looped = test.poll();
        assertEquals(FRAME * 2, looped.length);
        assertEquals(800, looped[0]);
        assertEquals(-800, looped[1]);
    }

    @Test
    public void serverMuteEndsARunningActivation() {
        state.setMicrophoneVolume(1D);
        assertEquals(CaptureActivation.Result.ACTIVATED, pipeline.process(constant(FRAME, 16_000), 1, false, NOW));
        assertNull(pipeline.process(constant(FRAME, 16_000), 1, true, NOW + 20));
        assertEquals(1, ends);
        assertFalse(pipeline.activation.isActive());
        assertFalse(state.isActivationActive());
        assertEquals(1, sent.size());
    }

    private static short[] constant(int length, int value) {
        short[] samples = new short[length];
        Arrays.fill(samples, (short) value);
        return samples;
    }
}
