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
        JitterBuffer buffer = new JitterBuffer(false, JitterBuffer.PACKET_DELAY);
        buffer.offer(audio(2), T);
        buffer.offer(audio(1), T);
        assertNull(buffer.poll(T)); // still buffering
        buffer.offer(audio(3), T);
        assertEquals(1L, ((SourceAudioPacket) buffer.poll(T)).getSequenceNumber());
        assertNull(buffer.poll(T)); // keeps PACKET_DELAY - 1 frames back
    }

    @Test
    public void jitterBufferDrainsAfterEnd() {
        JitterBuffer buffer = new JitterBuffer(false, JitterBuffer.PACKET_DELAY);
        buffer.offer(audio(1), T);
        buffer.offer(new SourceAudioEndPacket(SOURCE, 2L), T);
        assertEquals(1L, ((SourceAudioPacket) buffer.poll(T)).getSequenceNumber());
        assertTrue(buffer.poll(T) instanceof SourceAudioEndPacket);
        assertNull(buffer.poll(T));

        // The next stream starts buffering again.
        buffer.offer(audio(3), T);
        assertNull(buffer.poll(T));
    }

    /** Upstream StaticJitterBuffer: one stale frame per poll is dropped and that poll returns nothing. */
    @Test
    public void jitterBufferDropsOneStaleFramePerPoll() {
        long later = T + JitterBuffer.STALE_THRESHOLD_MS;
        JitterBuffer buffer = new JitterBuffer(false, JitterBuffer.PACKET_DELAY);
        buffer.offer(audio(1), T);
        buffer.offer(audio(2), later);
        buffer.offer(audio(3), later);
        buffer.offer(audio(4), later);
        assertNull(buffer.poll(later));
        assertEquals(1L, buffer.dropped());
        assertEquals(2L, ((SourceAudioPacket) buffer.poll(later)).getSequenceNumber());
    }

    /** Upstream queues are unbounded; a stalled playback is limited only by the OpenAL stream's 100 frame queue. */
    @Test
    public void jitterBufferHasNoFrameCap() {
        JitterBuffer buffer = new JitterBuffer(false, JitterBuffer.PACKET_DELAY);
        for (int i = 0; i < 500; i++) buffer.offer(audio(i), T);
        buffer.offer(new SourceAudioEndPacket(SOURCE, 1_000L), T);
        int polled = 0;
        while (buffer.poll(T) != null) polled++;
        assertEquals(501, polled);
        assertEquals(0L, buffer.dropped());
    }

    /** Upstream: a packet delay of one frame or less uses a FIFO queue, so frames keep their arrival order. */
    @Test
    public void jitterBufferWithoutDelayKeepsArrivalOrder() {
        JitterBuffer buffer = new JitterBuffer(false, 1);
        buffer.offer(audio(2), T);
        buffer.offer(audio(1), T);
        assertEquals(2L, ((SourceAudioPacket) buffer.poll(T)).getSequenceNumber());
        assertEquals(1L, ((SourceAudioPacket) buffer.poll(T)).getSequenceNumber());

        JitterBuffer none = new JitterBuffer(false, 0);
        none.offer(audio(7), T);
        assertEquals(7L, ((SourceAudioPacket) none.poll(T)).getSequenceNumber());
    }

    @Test
    public void distanceGainIsCubicAndClamped() {
        assertEquals(1D, VoiceSource.distanceGain(0D, 16D, true), 1e-9);
        assertEquals(0.125D, VoiceSource.distanceGain(8D, 16D, true), 1e-9);
        assertEquals(0D, VoiceSource.distanceGain(40D, 16D, true), 1e-9);
        assertEquals(1D, VoiceSource.distanceGain(5D, 0D, true), 1e-9);
        // Upstream advanced.exponential_distance_gain off.
        assertEquals(0.5D, VoiceSource.distanceGain(8D, 16D, false), 1e-9);
    }

    @Test
    public void volumeSliderIsCubicBelowFullVolume() {
        assertEquals(0.125D, VoiceSource.sliderGain(0.5D, true), 1e-9);
        assertEquals(1D, VoiceSource.sliderGain(1D, true), 1e-9);
        assertEquals(1.5D, VoiceSource.sliderGain(1.5D, true), 1e-9);
        // Upstream advanced.exponential_volume_slider off.
        assertEquals(0.5D, VoiceSource.sliderGain(0.5D, false), 1e-9);
    }

    @Test
    public void microphoneTestMeterDecaysAndLoopbackIsBounded() {
        MicrophoneTest test = new MicrophoneTest();
        short[] loud = new short[960];
        java.util.Arrays.fill(loud, (short) 16000);
        test.onCaptured(loud, 1_000L);
        double value = test.value(1_000L);
        assertTrue(value > 0.5D);
        // Upstream: 0.04 per tick.
        assertEquals(value - 0.04D, test.value(1_050L), 1e-9);
        // Only a running test feeds the loopback, and it keeps the newest 200 ms.
        assertNull(test.poll());
        test.start();
        for (int i = 0; i < 20; i++) test.onCaptured(loud, 2_000L);
        int frames = 0;
        while (test.poll() != null) frames++;
        assertEquals(10, frames);
        test.stop();
        test.onCaptured(loud, 3_000L);
        assertNull(test.poll());
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
