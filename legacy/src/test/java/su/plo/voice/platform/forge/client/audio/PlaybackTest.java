package su.plo.voice.platform.forge.client.audio;

import java.util.UUID;

import org.junit.Test;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

import static org.junit.Assert.*;

public class PlaybackTest {
    private static final UUID SOURCE = UUID.randomUUID();
    private static final long T = 1_000_000L;

    @Test
    public void jitterBufferReordersAfterDelay() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.offer(audio(2), T);
        buffer.offer(audio(1), T);
        assertNull(buffer.poll(T)); // still buffering
        buffer.offer(audio(3), T);
        assertEquals(1L, ((SourceAudioPacket) buffer.poll(T)).getSequenceNumber());
        assertNull(buffer.poll(T)); // keeps PACKET_DELAY - 1 frames back
    }

    @Test
    public void jitterBufferDrainsAfterEnd() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.offer(audio(1), T);
        buffer.offer(new SourceAudioEndPacket(SOURCE, 2L), T);
        assertEquals(1L, ((SourceAudioPacket) buffer.poll(T)).getSequenceNumber());
        assertTrue(buffer.poll(T) instanceof SourceAudioEndPacket);
        assertNull(buffer.poll(T));

        // The next stream starts buffering again.
        buffer.offer(audio(3), T);
        assertNull(buffer.poll(T));
    }

    @Test
    public void jitterBufferSkipsStaleFramesAndIsBounded() {
        long later = T + JitterBuffer.STALE_THRESHOLD_MS;
        JitterBuffer buffer = new JitterBuffer();
        buffer.offer(audio(1), T);
        buffer.offer(audio(2), later);
        buffer.offer(audio(3), later);
        buffer.offer(audio(4), later);
        assertEquals(2L, ((SourceAudioPacket) buffer.poll(later)).getSequenceNumber());

        buffer.clear();
        for (int i = 0; i < JitterBuffer.CAPACITY + 10; i++) buffer.offer(audio(i), T);
        buffer.offer(new SourceAudioEndPacket(SOURCE, 1_000L), T);
        int polled = 0;
        while (buffer.poll(T) != null) polled++;
        assertEquals(JitterBuffer.CAPACITY, polled);
    }

    @Test
    public void distanceGainIsCubicAndClamped() {
        assertEquals(1D, VoiceSource.distanceGain(0D, 16D), 1e-9);
        assertEquals(0.125D, VoiceSource.distanceGain(8D, 16D), 1e-9);
        assertEquals(0D, VoiceSource.distanceGain(40D, 16D), 1e-9);
        assertEquals(1D, VoiceSource.distanceGain(5D, 0D), 1e-9);
    }

    @Test
    public void stateDiffMatchesUpstreamByteDiff() {
        assertEquals(0, VoiceSource.stateDiff((byte) 5, (byte) 5));
        assertEquals(3, VoiceSource.stateDiff((byte) 5, (byte) 8));
        assertEquals(10, VoiceSource.stateDiff((byte) 1, (byte) 11));
        assertEquals(2, VoiceSource.stateDiff((byte) 127, (byte) -127));
        assertTrue(VoiceSource.stateDiff((byte) 5, (byte) 3) < 10); // an older state is not rejected
    }

    @Test
    public void fadesRampTheFrame() {
        short[] frame = new short[] {1000, 1000, 1000, 1000};
        assertArrayEquals(new short[] {0, 250, 500, 750}, VoiceSource.fadeIn(frame, 1));
        assertArrayEquals(new short[] {1000, 750, 500, 250}, VoiceSource.fadeOut(frame, 1));
        assertArrayEquals(new short[] {0, 0, 500, 500}, VoiceSource.fadeIn(frame, 2));
    }

    private static SourceAudioPacket audio(long sequenceNumber) {
        return new SourceAudioPacket(sequenceNumber, (byte) 1, new byte[] {1}, SOURCE, (short) 16);
    }
}
