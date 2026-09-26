package su.plo.voice.platform.forge.client.audio;

import java.util.Comparator;
import java.util.PriorityQueue;

import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Upstream StaticJitterBuffer and AdaptiveJitterBuffer (advanced.adaptive_jitter_buffer), with
 * advanced.jitter_packet_delay. Offered from the UDP/client threads, polled by playback.
 * <p>
 * Static: frames wait until a few are buffered so late ones can be reordered; after SourceAudioEnd everything left
 * is released. Adaptive: each frame is scheduled from the first frame's arrival plus 20 ms per sequence number,
 * delayed further by the measured arrival jitter.
 */
final class JitterBuffer {
    /** Upstream advanced.jitter_packet_delay default. */
    static final int PACKET_DELAY = 3;
    static final long STALE_THRESHOLD_MS = 500L;
    /** One second of 20 ms frames; newer frames are dropped while playback is stalled. */
    static final int CAPACITY = 50;
    private static final long FRAME_MS = 20L;

    private final PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingLong(entry -> entry.sequenceNumber));
    private SourceAudioEndPacket endPacket;
    private boolean adaptive;
    private int packetDelay = PACKET_DELAY;
    // Adaptive scheduling.
    private long firstArrival = -1L;
    private long firstSequenceNumber = -1L;
    private long lastArrival = -1L;
    private double jitterEstimate;
    private long adaptiveDelay = PACKET_DELAY * FRAME_MS;
    /** Frames dropped because the buffer was full or the frame was stale; read by the playback diagnostics. */
    private long dropped;

    /** Upstream picks the buffer when a source is created; a changed setting starts over with an empty buffer. */
    synchronized void configure(boolean adaptive, int packetDelay) {
        if (this.adaptive == adaptive && this.packetDelay == packetDelay) return;
        this.adaptive = adaptive;
        this.packetDelay = packetDelay;
        clear();
    }

    synchronized boolean isAdaptive() {
        return adaptive;
    }

    synchronized void offer(SourceAudioPacket packet, long now) {
        if (endPacket != null && packet.getSequenceNumber() > endPacket.getSequenceNumber()) {
            endPacket = null;
            firstArrival = -1L;
            firstSequenceNumber = -1L;
            lastArrival = -1L;
        }
        add(packet.getSequenceNumber(), packet, now);
    }

    synchronized void offer(SourceAudioEndPacket packet, long now) {
        endPacket = packet;
        add(packet.getSequenceNumber(), packet, now);
    }

    /** Returns the next {@link SourceAudioPacket} or {@link SourceAudioEndPacket}, or null while buffering. */
    synchronized Object poll(long now) {
        if (adaptive) {
            Entry next = queue.peek();
            if (next == null || now < next.scheduledTime + adaptiveDelay) return null;
            return queue.poll().packet;
        }
        while (endPacket != null || queue.size() >= packetDelay) {
            Entry entry = queue.poll();
            if (entry == null) return null;
            if (now - entry.arrivalTime < STALE_THRESHOLD_MS) return entry.packet;
            dropped++;
        }
        return null;
    }

    synchronized long dropped() {
        return dropped;
    }

    synchronized int size() {
        return queue.size();
    }

    synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    synchronized void clear() {
        queue.clear();
        endPacket = null;
        firstArrival = -1L;
        firstSequenceNumber = -1L;
        lastArrival = -1L;
        jitterEstimate = 0D;
        adaptiveDelay = packetDelay * FRAME_MS;
    }

    private void add(long sequenceNumber, Object packet, long now) {
        long scheduledTime = adaptive ? schedule(sequenceNumber, now) : now;
        if (queue.size() < CAPACITY) queue.add(new Entry(sequenceNumber, packet, now, scheduledTime));
        else dropped++;
    }

    /** Upstream scheduledPlaybackTime: assumes the sender keeps a steady 20 ms frame rate. */
    private long schedule(long sequenceNumber, long arrival) {
        if (lastArrival >= 0) {
            double delta = Math.abs((arrival - lastArrival) - FRAME_MS);
            jitterEstimate += (delta - jitterEstimate) / 16D;
            adaptiveDelay = Math.round(jitterEstimate / FRAME_MS) * FRAME_MS;
        }
        lastArrival = arrival;
        if (firstSequenceNumber < 0) {
            firstArrival = arrival;
            firstSequenceNumber = sequenceNumber;
        }
        return firstArrival + packetDelay * FRAME_MS + (sequenceNumber - firstSequenceNumber) * FRAME_MS;
    }

    private static final class Entry {
        final long sequenceNumber;
        final Object packet;
        final long arrivalTime;
        final long scheduledTime;

        Entry(long sequenceNumber, Object packet, long arrivalTime, long scheduledTime) {
            this.sequenceNumber = sequenceNumber;
            this.packet = packet;
            this.arrivalTime = arrivalTime;
            this.scheduledTime = scheduledTime;
        }
    }
}
