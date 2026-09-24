package su.plo.voice.platform.forge.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.AudioFormat;

import org.junit.Test;

import static org.junit.Assert.*;

/** Upstream 2.1.17 JavaxInputDevice fallback: backend order, PCM layout and the stereo downmix. */
public class JavaxInputTest {
    @Test
    public void openAlStaysFirstAndJavaSoundIsTheFallback() {
        List<String> tried = new ArrayList<>();
        assertEquals(VoiceCapture.Backend.OPENAL, VoiceCapture.openBackend(false, opener(tried, "al", true), opener(tried, "javax", true)));
        assertEquals("[al]", tried.toString());

        tried.clear();
        assertEquals(VoiceCapture.Backend.JAVAX, VoiceCapture.openBackend(false, opener(tried, "al", false), opener(tried, "javax", true)));
        assertEquals("[al, javax]", tried.toString());

        tried.clear();
        assertNull(VoiceCapture.openBackend(false, opener(tried, "al", false), opener(tried, "javax", false)));
        assertEquals("[al, javax]", tried.toString());
    }

    @Test
    public void useJavaxInputSkipsOpenAl() {
        List<String> tried = new ArrayList<>();
        assertEquals(VoiceCapture.Backend.JAVAX, VoiceCapture.openBackend(true, opener(tried, "al", true), opener(tried, "javax", true)));
        assertEquals("[javax]", tried.toString());

        tried.clear();
        assertNull(VoiceCapture.openBackend(true, opener(tried, "al", true), opener(tried, "javax", false)));
        assertEquals("[javax]", tried.toString());
    }

    @Test
    public void capturesSignedLittleEndianSixteenBitPcm() {
        AudioFormat format = JavaxInput.format(48_000, 2);
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
        assertEquals(48_000F, format.getSampleRate(), 0F);
        assertEquals(16, format.getSampleSizeInBits());
        assertEquals(2, format.getChannels());
        assertEquals(4, format.getFrameSize());
        assertFalse(format.isBigEndian());
        assertEquals(1, JavaxInput.format(48_000, 1).getChannels());

        byte[] bytes = {0x34, 0x12, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F};
        assertArrayEquals(new short[] {0x1234, -1, Short.MIN_VALUE, Short.MAX_VALUE}, JavaxInput.toShorts(bytes));
    }

    @Test
    public void stereoFramesAreAveragedPerSamplePair() {
        short[] stereo = {100, 300, -100, -300, Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE};
        assertArrayEquals(new short[] {200, -200, Short.MAX_VALUE, 0}, VoiceCapture.toMono(stereo));
        // One 20 ms frame at 48 kHz: 1920 interleaved samples become 960.
        assertEquals(960, VoiceCapture.toMono(new short[1920]).length);
    }

    private static BooleanSupplier opener(List<String> tried, String name, boolean opens) {
        return () -> {
            tried.add(name);
            return opens;
        };
    }
}
