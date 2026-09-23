package su.plo.voice.platform.forge.client.audio;

import java.util.Comparator;
import java.util.PriorityQueue;

import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Upstream StaticJitterBuffer: frames wait until a few are buffered so late ones can be reordered;
 * after SourceAudioEnd everything left is released. Offered from the UDP/client threads, polled by playback.
 */
final class JitterBuffer {
    /** Upstream advanced.jitter_packet_delay default. */
    static final int PACKET_DELAY = 3;
    static final long STALE_THRESHOLD_MS = 500L;
    /** One second of 20 ms frames; newer frames are dropped while playback is stalled. */
    static final int CAPACITY = 50;

    private final PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingLong(entry -> entry.sequenceNumber));
    private SourceAudioEndPacket endPacket;

    synchronized void offer(SourceAudioPacket packet, long now) {
        if (endPacket != null && packet.getSequenceNumber() > endPacket.getSequenceNumber()) endPacket = null;
        add(packet.getSequenceNumber(), packet, now);
    }

    synchronized void offer(SourceAudioEndPacket packet, long now) {
        endPacket = packet;
        add(packet.getSequenceNumber(), packet, now);
    }

    /** Returns the next {@link SourceAudioPacket} or {@link SourceAudioEndPacket}, or null while buffering. */
    synchronized Object poll(long now) {
        while (endPacket != null || queue.size() >= PACKET_DELAY) {
            Entry entry = queue.poll();
            if (entry == null) return null;
            if (now - entry.arrivalTime < STALE_THRESHOLD_MS) return entry.packet;
        }
        return null;
    }

    synchronized void clear() {
        queue.clear();
        endPacket = null;
    }

    private void add(long sequenceNumber, Object packet, long now) {
        if (queue.size() < CAPACITY) queue.add(new Entry(sequenceNumber, packet, now));
    }

    private static final class Entry {
        final long sequenceNumber;
        final Object packet;
        final long arrivalTime;

        Entry(long sequenceNumber, Object packet, long arrivalTime) {
            this.sequenceNumber = sequenceNumber;
            this.packet = packet;
            this.arrivalTime = arrivalTime;
        }
    }
}
