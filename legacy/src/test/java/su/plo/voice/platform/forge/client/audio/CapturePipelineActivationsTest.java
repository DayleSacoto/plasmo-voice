package su.plo.voice.platform.forge.client.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

import static org.junit.Assert.*;

/** The pipeline with the proximity activation and a groups one: client settings in, encoded frames and HUD state out. */
public class CapturePipelineActivationsTest {
    private static final int FRAME = 960;
    private static final long NOW = 1_000_000L;

    private final ClientState state = new ClientState();
    private final CaptureActivations.Slot groups =
            new CaptureActivations.Slot(VoiceActivation.generateId("groups"), "groups", 10, false, true);
    private final CaptureActivations activations = new CaptureActivations(
            CaptureActivations.Slot.proximityFallback(), Collections.singletonList(groups));
    private final List<String> events = new ArrayList<>();
    private int encodes;
    private final CapturePipeline pipeline = new CapturePipeline(state, new NoiseSuppression(state), activations,
            new CapturePipeline.Output() {
                @Override
                public byte[] encode(short[] mono) {
                    encodes++;
                    return new byte[] {1};
                }

                @Override
                public void audio(CaptureActivations.Slot slot, long sequenceNumber, byte[] data) {
                    events.add("audio:" + slot.name + ":" + sequenceNumber);
                }

                @Override
                public void end(CaptureActivations.Slot slot, long sequenceNumber, boolean lastStream) {
                    events.add("end:" + slot.name + ":" + sequenceNumber + ":" + lastStream);
                }
            });

    {
        state.setNoiseSuppression(false);
    }

    private static short[] constant(int value) {
        short[] samples = new short[FRAME];
        Arrays.fill(samples, (short) value);
        return samples;
    }

    @Test
    public void groupsPushToTalkSendsOnlyTheGroupsActivationAndShowsItInTheHud() {
        state.setPushToTalkPressed("groups", true);
        assertEquals(CaptureActivation.Result.NOT_ACTIVATED, pipeline.process(constant(0), 1, false, NOW));
        assertEquals(Arrays.asList("audio:groups:1"), events);
        assertEquals(Collections.singleton(groups.id), state.getActiveActivations());
        assertTrue(state.isActivationActive());
        assertEquals(1, encodes);
    }

    @Test
    public void bothPushToTalkKeysSendFromOneEncodeAndGroupsInheritFollowsProximity() {
        state.setPushToTalkPressed(true);
        state.setPushToTalkPressed("groups", true);
        assertEquals(CaptureActivation.Result.ACTIVATED, pipeline.process(constant(0), 1, false, NOW));
        assertEquals(Arrays.asList("audio:groups:1", "audio:proximity:1"), events);
        assertEquals(1, encodes);
        assertEquals(new HashSet<>(Arrays.asList(groups.id, VoiceActivation.PROXIMITY_ID)), state.getActiveActivations());

        // Groups inherits: it follows proximity from now on, whatever its own key does.
        events.clear();
        state.setPushToTalkPressed("groups", false);
        state.setActivationType("groups", CaptureActivation.Type.INHERIT);
        pipeline.process(constant(0), 1, false, NOW + 20);
        assertEquals(Arrays.asList("audio:groups:2", "audio:proximity:2"), events);
    }

    @Test
    public void toggledGroupsInheritIsSwitchedOffByTheClientSetting() {
        state.setActivationType("groups", CaptureActivation.Type.INHERIT);
        state.setActivationToggled("groups", true);
        state.setPushToTalkPressed(true);
        pipeline.process(constant(0), 1, false, NOW);
        assertEquals(Arrays.asList("audio:proximity:1"), events);
        assertEquals(Collections.singleton(VoiceActivation.PROXIMITY_ID), state.getActiveActivations());
    }

    @Test
    public void flushEndsBothAndClearsTheHudState() {
        state.setPushToTalkPressed(true);
        state.setPushToTalkPressed("groups", true);
        pipeline.process(constant(0), 1, false, NOW);
        events.clear();

        assertNull(pipeline.process(constant(0), 1, true, NOW + 20));
        assertEquals(Arrays.asList("end:proximity:2:false", "end:groups:2:true"), events);
        assertTrue(state.getActiveActivations().isEmpty());
        assertFalse(state.isActivationActive());
    }
}
