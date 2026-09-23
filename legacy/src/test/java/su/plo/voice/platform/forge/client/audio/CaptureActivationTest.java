package su.plo.voice.platform.forge.client.audio;

import org.junit.Test;
import su.plo.voice.platform.forge.client.audio.CaptureActivation.Result;
import su.plo.voice.platform.forge.client.audio.CaptureActivation.Type;

import static org.junit.Assert.*;

public class CaptureActivationTest {
    private static final short[] SILENCE = new short[960];
    private static final short[] LOUD = tone(8000);   // about -12 dB
    private static final short[] QUIET = tone(100);   // about -50 dB
    private static final double THRESHOLD = -30D;
    private static final long T = 1_000_000L;

    @Test
    public void pushToTalkHoldsBriefly() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, true, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, THRESHOLD, T + 350));
        assertEquals(Result.END, activation.process(SILENCE, Type.PUSH_TO_TALK, false, THRESHOLD, T + 351));
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, THRESHOLD, T + 400));
    }

    @Test
    public void voiceActivationUsesThresholdAndHold() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.NOT_ACTIVATED, activation.process(QUIET, Type.VOICE, false, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(LOUD, Type.VOICE, false, THRESHOLD, T + 20));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.VOICE, false, THRESHOLD, T + 520));
        assertEquals(Result.END, activation.process(SILENCE, Type.VOICE, false, THRESHOLD, T + 521));
        assertFalse(activation.isActive());
    }

    @Test
    public void resetStopsTheStream() {
        CaptureActivation activation = new CaptureActivation();
        activation.process(LOUD, Type.VOICE, false, THRESHOLD, T);
        assertTrue(activation.isActive());
        activation.reset();
        assertFalse(activation.isActive());
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Type.VOICE, false, THRESHOLD, T + 10));
    }

    @Test
    public void audioLevelMatchesUpstreamScale() {
        assertEquals(-127D, CaptureActivation.calculateAudioLevel(SILENCE, 0, 50), 0D);
        assertTrue(CaptureActivation.containsMinAudioLevel(LOUD, THRESHOLD));
        assertFalse(CaptureActivation.containsMinAudioLevel(QUIET, THRESHOLD));
    }

    @Test
    public void onlyOpenAlSoft125BeforePatch2NeedsStereoCapture() {
        assertTrue(VoiceCapture.isBrokenAlSoftVersion("1.1 ALSOFT 1.25.0"));
        assertTrue(VoiceCapture.isBrokenAlSoftVersion("1.1 ALSOFT 1.25.1"));
        assertFalse(VoiceCapture.isBrokenAlSoftVersion("1.1 ALSOFT 1.25.2"));
        assertFalse(VoiceCapture.isBrokenAlSoftVersion("1.1 ALSOFT 1.24.3"));
        assertFalse(VoiceCapture.isBrokenAlSoftVersion("1.1"));
        assertFalse(VoiceCapture.isBrokenAlSoftVersion(null));
    }

    private static short[] tone(int amplitude) {
        short[] pcm = new short[960];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 48_000.0) * amplitude);
        return pcm;
    }
}
