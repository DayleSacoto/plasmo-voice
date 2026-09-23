package su.plo.voice.platform.forge.audio.codec;

import java.util.Collections;

import org.junit.Test;
import su.plo.voice.proto.data.audio.codec.CodecInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusMode;

import static org.junit.Assert.*;

public class OpusCodecTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SIZE = SAMPLE_RATE / 1000 * 20; // upstream 20 ms frames
    private static final int MTU = 1024;
    private static final int FRAMES = 50;

    @Test
    public void nativeCodecEncodesAndDecodesTone() throws Exception {
        assertTone(new OpusCodec.NativeOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU),
                new OpusCodec.NativeOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE));
    }

    @Test
    public void javaCodecEncodesAndDecodesTone() throws Exception {
        assertTone(new OpusCodec.JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU),
                new OpusCodec.JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE));
    }

    /** Clients may pick different backends, so both must speak the same Opus bitstream. */
    @Test
    public void nativeAndJavaBackendsInteroperate() throws Exception {
        assertTone(new OpusCodec.NativeOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU),
                new OpusCodec.JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE));
        assertTone(new OpusCodec.JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU),
                new OpusCodec.NativeOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE));
    }

    @Test
    public void packetLossConcealmentProducesAFrame() throws Exception {
        for (AudioDecoder decoder : new AudioDecoder[] {
                new OpusCodec.NativeOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE),
                new OpusCodec.JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE)}) {
            try (AudioDecoder plc = decoder) {
                assertEquals(FRAME_SIZE, plc.decode(null).length);
            }
        }
    }

    @Test
    public void factoryPrefersNativesAndHonoursOptOut() {
        CodecInfo info = new CodecInfo("opus", new java.util.HashMap<>(java.util.Map.of("mode", "VOIP", "bitrate", "-1000")));
        try (AudioEncoder encoder = OpusCodec.createEncoder(info, SAMPLE_RATE, false, MTU);
             AudioDecoder decoder = OpusCodec.createDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            assertTrue(encoder instanceof OpusCodec.NativeOpusEncoder);
            assertTrue(decoder instanceof OpusCodec.NativeOpusDecoder);
        }
        System.setProperty("plasmovoice.disable_natives", "true");
        try (AudioEncoder encoder = OpusCodec.createEncoder(info, SAMPLE_RATE, false, MTU);
             AudioDecoder decoder = OpusCodec.createDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            assertTrue(encoder instanceof OpusCodec.JavaOpusEncoder);
            assertTrue(decoder instanceof OpusCodec.JavaOpusDecoder);
        } finally {
            System.clearProperty("plasmovoice.disable_natives");
        }
        assertThrows(IllegalStateException.class, () -> OpusCodec.createEncoder(
                new CodecInfo("opus", Collections.emptyMap()), SAMPLE_RATE, false, MTU));
    }

    @Test
    public void closeIsIdempotent() throws Exception {
        AudioEncoder encoder = new OpusCodec.NativeOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU);
        AudioDecoder decoder = new OpusCodec.NativeOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE);
        encoder.close();
        encoder.close();
        decoder.close();
        decoder.close();
    }

    private static void assertTone(AudioEncoder encoder, AudioDecoder decoder) throws Exception {
        try (AudioEncoder e = encoder; AudioDecoder d = decoder) {
            double inputEnergy = 0;
            double outputEnergy = 0;
            int crossings = 0;
            for (int frame = 0; frame < FRAMES; frame++) {
                short[] pcm = tone(frame);
                byte[] encoded = e.encode(pcm);
                assertTrue(encoded.length > 0 && encoded.length <= MTU);
                short[] decoded = d.decode(encoded);
                assertEquals(FRAME_SIZE, decoded.length);
                if (frame < 10) continue; // let the codec settle
                for (int i = 0; i < FRAME_SIZE; i++) {
                    inputEnergy += (double) pcm[i] * pcm[i];
                    outputEnergy += (double) decoded[i] * decoded[i];
                    if (i > 0 && (decoded[i - 1] < 0) != (decoded[i] < 0)) crossings++;
                }
            }
            double energyRatio = outputEnergy / inputEnergy;
            assertTrue("energy ratio " + energyRatio, energyRatio > 0.5 && energyRatio < 2.0);
            double crossingsPerSecond = crossings / ((FRAMES - 10) * 0.02);
            assertEquals("zero crossings/s for 440 Hz", 880.0, crossingsPerSecond, 60.0);
        }
    }

    private static short[] tone(int frame) {
        short[] pcm = new short[FRAME_SIZE];
        for (int i = 0; i < FRAME_SIZE; i++) {
            double t = (double) (frame * FRAME_SIZE + i) / SAMPLE_RATE;
            pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * t) * 8000);
        }
        return pcm;
    }
}
