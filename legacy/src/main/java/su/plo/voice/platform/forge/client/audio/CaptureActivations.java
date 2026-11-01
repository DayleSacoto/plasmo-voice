package su.plo.voice.platform.forge.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

/**
 * The activation decisions of upstream VoiceAudioCapture.run and processActivation for one captured frame: the
 * parent (proximity) activation first, the others by weight, transitive blocking per class, one encode per frame,
 * a sequence number per activation. No OpenAL, Minecraft or network: audio and END go to a {@link Sink}.
 */
final class CaptureActivations {
    /** One activation: what the capture loop needs to know about it, its processing state and its sequence counter. */
    static final class Slot {
        final UUID id;
        final String name;
        final int weight;
        final boolean proximity;
        final boolean transitive;
        final CaptureActivation activation = new CaptureActivation();
        private long sequence;
        /** Upstream activationStreams: a voice packet went out and no END followed yet. */
        private boolean streaming;

        Slot(UUID id, String name, int weight, boolean proximity, boolean transitive) {
            this.id = id;
            this.name = name;
            this.weight = weight;
            this.proximity = proximity;
            this.transitive = transitive;
        }

        static Slot of(VoiceActivation activation) {
            return new Slot(activation.getId(), activation.getName(), activation.getWeight(), activation.isProximity(),
                    activation.isTransitive());
        }

        /** The activation of a config that lacks the proximity entry: still the parent, as before activations existed. */
        static Slot proximityFallback() {
            return new Slot(VoiceActivation.PROXIMITY_ID, VoiceActivation.PROXIMITY_NAME, 0, true, true);
        }

        boolean isActive() {
            return activation.isActive();
        }

        boolean isStreaming() {
            return streaming;
        }

        long sequence() {
            return sequence;
        }

        private long nextSequence() {
            return ++sequence;
        }
    }

    /** The client settings the decisions read, per activation. */
    interface Settings {
        CaptureActivation.Type type(Slot slot);

        /** Upstream configToggle. */
        boolean toggled(Slot slot);

        boolean pushToTalkPressed(Slot slot);

        double threshold();
    }

    /** Where the decisions go; one instance per frame, so the encoded frame is cached inside it. */
    interface Sink {
        /** Encodes (and encrypts) the processed mono frame on the first call of the frame; null when that failed. */
        byte[] encode();

        /** Upstream EncodedCapture.mono: null until {@link #encode()} ran for this frame. */
        byte[] encoded();

        void audio(Slot slot, long sequenceNumber, byte[] data);

        /**
         * {@code lastStream} is true when no other activation is sending any more: the shared encoder may be reset.
         * Upstream resets it on every END, which corrupts a stream that is still running on the same encoder.
         */
        void end(Slot slot, long sequenceNumber, boolean lastStream);
    }

    final Slot parent;
    /** Every other activation, heaviest first. */
    final List<Slot> others;

    CaptureActivations(Slot parent, List<Slot> others) {
        this.parent = parent;
        this.others = Collections.unmodifiableList(new ArrayList<>(others));
    }

    /** Builds the slots of a server config; {@code activations} is the packet's list. */
    static CaptureActivations create(Collection<VoiceActivation> activations) {
        VoiceActivation proximity = null;
        for (VoiceActivation activation : activations) {
            if (activation.getId().equals(VoiceActivation.PROXIMITY_ID)) proximity = activation;
        }
        List<Slot> slots = new ArrayList<>();
        for (VoiceActivation activation : ClientConfig.otherActivations(activations)) slots.add(Slot.of(activation));
        return new CaptureActivations(proximity == null ? Slot.proximityFallback() : Slot.of(proximity), slots);
    }

    /** Parent first, then the others. */
    List<Slot> all() {
        List<Slot> all = new ArrayList<>(others.size() + 1);
        all.add(parent);
        all.addAll(others);
        return all;
    }

    /** The ids of the activations that are active now. */
    Set<UUID> activeIds() {
        Set<UUID> active = null;
        for (Slot slot : all()) {
            if (!slot.isActive()) continue;
            if (active == null) active = new HashSet<>();
            active.add(slot.id);
        }
        return active == null ? Collections.<UUID>emptySet() : active;
    }

    boolean anyStreaming() {
        if (parent.streaming) return true;
        for (Slot slot : others) {
            if (slot.streaming) return true;
        }
        return false;
    }

    /** Returns the parent's result. */
    CaptureActivation.Result process(short[] samples, long now, Settings settings, Sink sink) {
        CaptureActivation.Result parentResult = parent.activation.process(samples, null, settings.type(parent),
                settings.toggled(parent), settings.pushToTalkPressed(parent), settings.threshold(), now);

        boolean proximityReached = false;
        boolean nonProximityReached = false;
        for (Slot slot : others) {
            CaptureActivation.Type type = settings.type(slot);
            boolean toggled = settings.toggled(slot);
            if (CaptureActivation.isDisabled(type, toggled) && !slot.isActive()) continue;

            if (slot.proximity ? proximityReached : nonProximityReached) {
                // Upstream only resets. A running stream also gets its END here, like the blocked parent, instead of
                // being left to time out on the server and the other clients.
                slot.activation.reset();
                if (slot.streaming) end(slot, sink);
                continue;
            }

            CaptureActivation.Result result = slot.activation.process(samples, parentResult, type, toggled,
                    settings.pushToTalkPressed(slot), settings.threshold(), now);
            send(slot, result, sink);
            if (result == CaptureActivation.Result.ACTIVATED && !slot.transitive) {
                if (slot.proximity) {
                    proximityReached = true;
                } else {
                    nonProximityReached = true;
                }
            }
        }

        // The parent is proximity: a non-transitive proximity activation that is sending replaces it.
        if (!proximityReached) {
            send(parent, parentResult, sink);
        } else if (parent.streaming) {
            end(parent, sink);
        }
        return parentResult;
    }

    /** Microphone test, muted microphone, voice disabled or server mute: every running activation ends, nothing is sent. */
    void flush(Sink sink) {
        for (Slot slot : all()) {
            if (!slot.isActive() && !slot.streaming) continue;
            slot.activation.reset();
            slot.streaming = false;
            sink.end(slot, slot.nextSequence(), !anyStreaming());
        }
    }

    /** Upstream processActivation. */
    private void send(Slot slot, CaptureActivation.Result result, Sink sink) {
        if (result == CaptureActivation.Result.ACTIVATED) {
            byte[] data = sink.encode();
            if (data != null) sink.audio(slot, slot.nextSequence(), data);
            slot.streaming = true;
        } else if (result == CaptureActivation.Result.END) {
            end(slot, sink);
        }
    }

    /** Upstream END: the frame some activation encoded in this very frame goes out once more, then the END packet. */
    private void end(Slot slot, Sink sink) {
        slot.streaming = false;
        byte[] data = sink.encoded();
        if (data != null) sink.audio(slot, slot.nextSequence(), data);
        sink.end(slot, slot.nextSequence(), !anyStreaming());
    }
}
