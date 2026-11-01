package su.plo.voice.platform.forge.client.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import su.plo.voice.platform.forge.client.audio.CaptureActivation.Result;
import su.plo.voice.platform.forge.client.audio.CaptureActivation.Type;
import su.plo.voice.platform.forge.client.audio.CaptureActivations.Slot;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

import static org.junit.Assert.*;

/** Upstream VoiceAudioCapture.run and processActivation: the per-frame decisions, without audio or network. */
public class CaptureActivationsTest {
    private static final short[] SILENCE = new short[960];
    private static final double THRESHOLD = -30D;
    private static final long T = 1_000_000L;

    private final Slot proximity = new Slot(VoiceActivation.PROXIMITY_ID, "proximity", 0, true, true);
    private final Settings settings = new Settings();
    private long now = T;

    /** What went out in one frame, in order: {@code audio:name:sequence}, {@code end:name:sequence:lastStream}. */
    private static final class RecordingSink implements CaptureActivations.Sink {
        final List<String> events = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        int encodes;
        private byte[] encoded;

        @Override
        public byte[] encode() {
            if (encoded == null) {
                encodes++;
                encoded = new byte[] {(byte) encodes};
            }
            return encoded;
        }

        @Override
        public byte[] encoded() {
            return encoded;
        }

        @Override
        public void audio(Slot slot, long sequenceNumber, byte[] data) {
            events.add("audio:" + slot.name + ":" + sequenceNumber);
            payloads.add(data);
        }

        @Override
        public void end(Slot slot, long sequenceNumber, boolean lastStream) {
            events.add("end:" + slot.name + ":" + sequenceNumber + ":" + lastStream);
        }
    }

    private static final class Settings implements CaptureActivations.Settings {
        final Map<String, Type> types = new HashMap<>();
        final Map<String, Boolean> toggled = new HashMap<>();
        final Set<String> pressed = new HashSet<>();

        @Override
        public Type type(Slot slot) {
            Type type = types.get(slot.name);
            return type == null ? Type.PUSH_TO_TALK : type;
        }

        @Override
        public boolean toggled(Slot slot) {
            return Boolean.TRUE.equals(toggled.get(slot.name));
        }

        @Override
        public boolean pushToTalkPressed(Slot slot) {
            return pressed.contains(slot.name);
        }

        @Override
        public double threshold() {
            return THRESHOLD;
        }
    }

    private CaptureActivations activations(Slot... others) {
        return new CaptureActivations(proximity, Arrays.asList(others));
    }

    private RecordingSink frame(CaptureActivations activations) {
        RecordingSink sink = new RecordingSink();
        activations.process(SILENCE, now, settings, sink);
        now += 20;
        return sink;
    }

    private static Slot slot(String name, int weight, boolean proximityClass, boolean transitive) {
        return new Slot(VoiceActivation.generateId(name), name, weight, proximityClass, transitive);
    }

    @Test
    public void proximityAloneSendsLikeBeforeThereWereOtherActivations() {
        CaptureActivations activations = activations();
        assertEquals(Collections.emptyList(), frame(activations).events);

        settings.pressed.add("proximity");
        assertEquals(Arrays.asList("audio:proximity:1"), frame(activations).events);
        assertEquals(Arrays.asList("audio:proximity:2"), frame(activations).events);

        // Released: push-to-talk still holds for 350 ms, then ENDs with the next sequence number, as the last stream.
        settings.pressed.clear();
        assertEquals(Arrays.asList("audio:proximity:3"), frame(activations).events);
        now += CaptureActivation.PUSH_TO_TALK_RELEASE_MS;
        assertEquals(Arrays.asList("end:proximity:4:true"), frame(activations).events);
        assertEquals(Collections.emptyList(), frame(activations).events);
    }

    @Test
    public void transitiveGroupsAndProximityBothSendFromOneEncode() {
        Slot groups = slot("groups", 10, false, true);
        CaptureActivations activations = activations(groups);
        settings.pressed.add("groups");
        settings.pressed.add("proximity");

        RecordingSink sink = frame(activations);
        // The heavier activation first, the parent last.
        assertEquals(Arrays.asList("audio:groups:1", "audio:proximity:1"), sink.events);
        assertEquals(1, sink.encodes);
        assertSame(sink.payloads.get(0), sink.payloads.get(1));

        sink = frame(activations);
        assertEquals(Arrays.asList("audio:groups:2", "audio:proximity:2"), sink.events);
        assertEquals(1, sink.encodes);
        assertEquals(new HashSet<>(Arrays.asList(groups.id, proximity.id)), activations.activeIds());
    }

    @Test
    public void groupsAloneDoesNotSendProximity() {
        CaptureActivations activations = activations(slot("groups", 10, false, true));
        settings.pressed.add("groups");
        assertEquals(Arrays.asList("audio:groups:1"), frame(activations).events);
        assertEquals(Collections.singleton(VoiceActivation.generateId("groups")), activations.activeIds());
    }

    @Test
    public void sequenceNumbersAreCountedPerActivation() {
        Slot groups = slot("groups", 10, false, true);
        CaptureActivations activations = activations(groups);
        settings.pressed.add("groups");
        frame(activations);
        frame(activations);
        frame(activations);
        settings.pressed.add("proximity");
        RecordingSink sink = frame(activations);
        assertEquals(Arrays.asList("audio:groups:4", "audio:proximity:1"), sink.events);
        assertEquals(4, groups.sequence());
        assertEquals(1, proximity.sequence());
    }

    @Test
    public void nonTransitiveActivationBlocksOnlyLowerOnesOfItsOwnClass() {
        Slot heavy = slot("heavy", 30, false, false);
        Slot light = slot("light", 20, false, true);
        Slot nearby = slot("nearby", 10, true, true);
        CaptureActivations activations = activations(heavy, light, nearby);
        settings.pressed.addAll(Arrays.asList("heavy", "light", "nearby", "proximity"));

        RecordingSink sink = frame(activations);
        // light is blocked by heavy (both non-proximity); the proximity class is untouched.
        assertEquals(Arrays.asList("audio:heavy:1", "audio:nearby:1", "audio:proximity:1"), sink.events);
        assertEquals(1, sink.encodes);
        assertTrue(heavy.isActive());
        assertFalse(light.isActive());
        assertFalse(light.isStreaming());
    }

    @Test
    public void nonTransitiveProximityActivationBlocksTheParent() {
        Slot nearby = slot("nearby", 10, true, false);
        CaptureActivations activations = activations(nearby);
        settings.pressed.add("proximity");
        assertEquals(Arrays.asList("audio:proximity:1"), frame(activations).events);

        // nearby starts mid-stream: the parent is cut off, getting the frame nearby encoded and then its END.
        settings.pressed.add("nearby");
        RecordingSink sink = frame(activations);
        assertEquals(Arrays.asList("audio:nearby:1", "audio:proximity:2", "end:proximity:3:false"), sink.events);
        assertFalse(proximity.isStreaming());
        assertTrue(nearby.isStreaming());
        // Still blocked, but already ended: no second END.
        assertEquals(Arrays.asList("audio:nearby:2"), frame(activations).events);
    }

    @Test
    public void blockedStreamingActivationGetsAnEnd() {
        Slot heavy = slot("heavy", 30, false, false);
        Slot light = slot("light", 20, false, true);
        CaptureActivations activations = activations(heavy, light);
        settings.pressed.add("light");
        assertEquals(Arrays.asList("audio:light:1"), frame(activations).events);

        settings.pressed.add("heavy");
        RecordingSink sink = frame(activations);
        assertEquals(Arrays.asList("audio:heavy:1", "audio:light:2", "end:light:3:false"), sink.events);
        assertFalse(light.isActive());
    }

    @Test
    public void flushEndsEveryRunningActivationOnlyTheLastOneIsTheLastStream() {
        Slot groups = slot("groups", 10, false, true);
        Slot idle = slot("idle", 5, false, true);
        CaptureActivations activations = activations(groups, idle);
        settings.pressed.add("groups");
        settings.pressed.add("proximity");
        frame(activations);

        RecordingSink sink = new RecordingSink();
        activations.flush(sink);
        assertEquals(Arrays.asList("end:proximity:2:false", "end:groups:2:true"), sink.events);
        assertEquals(0, sink.encodes);
        assertTrue(activations.activeIds().isEmpty());
        assertFalse(activations.anyStreaming());

        // Nothing left to end.
        sink = new RecordingSink();
        activations.flush(sink);
        assertTrue(sink.events.isEmpty());
    }

    @Test
    public void sharedEncoderIsOnlyResetByTheLastEnd() {
        Slot groups = slot("groups", 10, false, true);
        CaptureActivations activations = activations(groups);
        settings.pressed.add("groups");
        settings.pressed.add("proximity");
        frame(activations);

        // groups releases first: proximity still runs on the same encoder.
        settings.pressed.remove("groups");
        RecordingSink sink = frame(activations);
        assertEquals(Arrays.asList("audio:groups:2", "audio:proximity:2"), sink.events);
        now += CaptureActivation.PUSH_TO_TALK_RELEASE_MS;
        sink = frame(activations);
        assertEquals(Arrays.asList("end:groups:3:false", "audio:proximity:3"), sink.events);

        settings.pressed.remove("proximity");
        assertEquals(Arrays.asList("audio:proximity:4"), frame(activations).events);
        now += CaptureActivation.PUSH_TO_TALK_RELEASE_MS;
        assertEquals(Arrays.asList("end:proximity:5:true"), frame(activations).events);
    }

    @Test
    public void toggledVoiceActivationIsSkippedUnlessItStillRuns() {
        Slot groups = slot("groups", 10, false, true);
        CaptureActivations activations = activations(groups);
        settings.types.put("groups", Type.INHERIT);
        settings.pressed.add("proximity");
        assertEquals(Arrays.asList("audio:groups:1", "audio:proximity:1"), frame(activations).events);

        // Toggled off while running: one END, then it stays out of the frame.
        settings.toggled.put("groups", true);
        assertEquals(Arrays.asList("end:groups:2:false", "audio:proximity:2"), frame(activations).events);
        assertEquals(Arrays.asList("audio:proximity:3"), frame(activations).events);
        assertFalse(groups.isActive());
    }

    @Test
    public void inheritFollowsProximityStartAndEnd() {
        Slot groups = slot("groups", 10, false, true);
        CaptureActivations activations = activations(groups);
        settings.types.put("groups", Type.INHERIT);
        assertTrue(frame(activations).events.isEmpty());

        settings.pressed.add("proximity");
        assertEquals(Arrays.asList("audio:groups:1", "audio:proximity:1"), frame(activations).events);

        settings.pressed.clear();
        assertEquals(Arrays.asList("audio:groups:2", "audio:proximity:2"), frame(activations).events);
        now += CaptureActivation.PUSH_TO_TALK_RELEASE_MS;
        // The parent's END: inherit ends in the same frame, the shared encoder is reset after the last one.
        assertEquals(Arrays.asList("end:groups:3:false", "end:proximity:3:true"), frame(activations).events);
    }

    @Test
    public void groupsVoiceActivationDetectsItsOwnVoice() {
        Slot groups = slot("groups", 10, false, true);
        CaptureActivations activations = activations(groups);
        settings.types.put("groups", Type.VOICE);
        short[] loud = new short[960];
        Arrays.fill(loud, (short) 8000);

        RecordingSink sink = new RecordingSink();
        activations.process(loud, now, settings, sink);
        assertEquals(Arrays.asList("audio:groups:1"), sink.events);
        assertFalse(proximity.isActive());
    }

    @Test
    public void createOrdersByWeightThenNameIgnoringCaseAndKeepsProximityAsParent() {
        VoiceActivation near = activation("proximity", 0, true);
        VoiceActivation groups = activation("groups", 10, false);
        VoiceActivation beta = activation("beta", 10, false);
        VoiceActivation alpha = activation("Alpha", 10, false);
        VoiceActivation top = activation("zzz", 50, false);

        CaptureActivations activations = CaptureActivations.create(Arrays.asList(groups, near, beta, top, alpha));
        assertEquals(VoiceActivation.PROXIMITY_ID, activations.parent.id);
        List<String> names = new ArrayList<>();
        for (Slot slot : activations.others) names.add(slot.name);
        assertEquals(Arrays.asList("zzz", "Alpha", "beta", "groups"), names);
        assertTrue(activations.parent.proximity);
    }

    @Test
    public void createWithoutProximityStillHasTheParent() {
        CaptureActivations activations = CaptureActivations.create(Collections.singletonList(activation("groups", 10, false)));
        assertEquals(VoiceActivation.PROXIMITY_ID, activations.parent.id);
        assertEquals(1, activations.others.size());
    }

    private static VoiceActivation activation(String name, int weight, boolean proximity) {
        return new VoiceActivation(name, "pv.activation." + name, "plasmovoice:textures/icons/" + name + ".png",
                Collections.<Integer>emptyList(), 0, proximity, false, true, null, weight);
    }
}
