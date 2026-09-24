package su.plo.voice.platform.forge.client.audio;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.plasmoverse.rnnoise.Denoise;
import org.junit.Test;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

import static org.junit.Assert.*;

/** Upstream 2.1.17 parity: occlusion, directional sources, adaptive jitter buffer and RNNoise. */
public class ParityAudioTest {
    private static final UUID SOURCE = UUID.randomUUID();

    /** Blocks by x along a straight line from x=0.5 to x=10.5 at y=z=0.5; true = solid, false = not solid. */
    private static SoundOcclusion.BlockProbe wall(Map<Integer, Boolean> blocks) {
        return (x, y, z, from, to) -> y == 0 && z == 0 ? blocks.get(x) : null;
    }

    @Test
    public void occlusionAddsHalfForASolidBlockAndAQuarterForTheNextOnes() {
        double[] sound = {0.5D, 0.5D, 0.5D};
        double[] listener = {10.5D, 0.5D, 0.5D};
        Map<Integer, Boolean> blocks = new HashMap<>();
        assertEquals(0D, SoundOcclusion.occludedPercent(wall(blocks), sound, listener), 1e-9);

        blocks.put(5, true);
        assertEquals(0.5D, SoundOcclusion.occludedPercent(wall(blocks), sound, listener), 1e-9);
        blocks.put(6, true);
        assertEquals(0.625D, SoundOcclusion.occludedPercent(wall(blocks), sound, listener), 1e-9);

        Map<Integer, Boolean> glass = new HashMap<>();
        glass.put(5, false);
        assertEquals(0.25D, SoundOcclusion.occludedPercent(wall(glass), sound, listener), 1e-9);

        // The source's own block is skipped, and the total stops at 0.98.
        Map<Integer, Boolean> solid = new HashMap<>();
        for (int x = 0; x <= 10; x++) solid.put(x, true);
        assertEquals(0.98D, SoundOcclusion.occludedPercent(wall(solid), sound, listener), 1e-9);
    }

    @Test
    public void occlusionMovesInUpstreamSteps() {
        assertEquals(0.5D, VoiceSource.smoothOcclusion(-1D, 0.5D), 1e-9);
        // Up by 0.05 even past the target, down by 0.05 but not below it.
        assertEquals(0.35D, VoiceSource.smoothOcclusion(0.3D, 0.32D), 1e-9);
        assertEquals(0.25D, VoiceSource.smoothOcclusion(0.3D, 0D), 1e-9);
        assertEquals(0.28D, VoiceSource.smoothOcclusion(0.3D, 0.28D), 1e-9);
    }

    @Test
    public void directionalGainFallsOffOutsideTheInnerAngle() {
        double[] look = {0D, 0D, 1D};
        // The speaker looks at the listener: full volume.
        assertEquals(1D, VoiceSource.angleGain(0D, 0D, -1D, new double[] {0D, 0D, -1D}, 72.5D, true), 1e-9);
        // Listener straight behind (180 degrees): silent.
        assertEquals(0D, VoiceSource.angleGain(0D, 0D, -1D, look, 72.5D, false), 1e-9);
        // 90 degrees off with the default 145 degree cone: (1 - 17.5 / 107.5), cubed with exponential gain.
        double linear = 1D - (90D - 72.5D) / (180D - 72.5D);
        assertEquals(linear, VoiceSource.angleGain(1D, 0D, 0D, look, 72.5D, false), 1e-9);
        assertEquals(linear * linear * linear, VoiceSource.angleGain(1D, 0D, 0D, look, 72.5D, true), 1e-9);
    }

    @Test
    public void adaptiveJitterBufferPlaysFramesOnTheirSchedule() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.configure(true, 3);
        long t = 10_000L;
        buffer.offer(audio(10), t);
        // Upstream: until a second arrival measures the jitter, the extra delay is the full packet delay.
        assertNull(buffer.poll(t + 119));
        // A steady 20 ms arrival drops the extra delay: frames play at first arrival + 3 frames + 20 ms each.
        buffer.offer(audio(11), t + 20);
        assertNull(buffer.poll(t + 59));
        assertNotNull(buffer.poll(t + 60));
        assertNull(buffer.poll(t + 79));
        assertNotNull(buffer.poll(t + 80));

        // Switching the mode starts over with an empty buffer.
        buffer.offer(audio(12), t + 40);
        buffer.configure(false, 3);
        assertTrue(buffer.isEmpty());
    }

    @Test
    public void rnnoiseLoadsFromTheShadedNativesAndKeepsTheFrameLength() throws Exception {
        Denoise denoise = Denoise.create();
        try {
            short[] frame = new short[960];
            for (int i = 0; i < frame.length; i++) frame[i] = (short) ((i * 7919) % 2000 - 1000);
            short[] out = NoiseSuppression.toShorts(denoise.process(NoiseSuppression.toFloats(frame)));
            assertEquals(frame.length, out.length);
        } finally {
            denoise.close();
        }
        assertEquals(Short.MAX_VALUE, NoiseSuppression.toShorts(new float[] {1e9F})[0]);
        assertEquals(Short.MIN_VALUE, NoiseSuppression.toShorts(new float[] {-1e9F})[0]);
    }

    private static SourceAudioPacket audio(long sequenceNumber) {
        return new SourceAudioPacket(sequenceNumber, (byte) 1, new byte[] {1}, SOURCE, (short) 16);
    }
}
