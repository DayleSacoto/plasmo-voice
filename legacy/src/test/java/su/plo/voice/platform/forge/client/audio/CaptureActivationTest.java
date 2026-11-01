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
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, false, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, true, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, false, THRESHOLD, T + 350));
        assertEquals(Result.END, activation.process(SILENCE, Type.PUSH_TO_TALK, false, false, THRESHOLD, T + 351));
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, false, false, THRESHOLD, T + 400));
    }

    @Test
    public void voiceActivationUsesThresholdAndHold() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.NOT_ACTIVATED, activation.process(QUIET, Type.VOICE, false, false, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(LOUD, Type.VOICE, false, false, THRESHOLD, T + 20));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.VOICE, false, false, THRESHOLD, T + 520));
        assertEquals(Result.END, activation.process(SILENCE, Type.VOICE, false, false, THRESHOLD, T + 521));
        assertFalse(activation.isActive());
    }

    @Test
    public void resetStopsTheStream() {
        CaptureActivation activation = new CaptureActivation();
        activation.process(LOUD, Type.VOICE, false, false, THRESHOLD, T);
        assertTrue(activation.isActive());
        activation.reset();
        assertFalse(activation.isActive());
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Type.VOICE, false, false, THRESHOLD, T + 10));
    }

    @Test
    public void toggleSwitchesOnlyVoiceActivationOff() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.ACTIVATED, activation.process(LOUD, Type.VOICE, false, false, THRESHOLD, T));
        assertEquals(Result.END, activation.process(LOUD, Type.VOICE, true, false, THRESHOLD, T + 20));
        assertEquals(Result.NOT_ACTIVATED, activation.process(LOUD, Type.VOICE, true, false, THRESHOLD, T + 40));
        // Upstream ignores the toggle for push-to-talk.
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Type.PUSH_TO_TALK, true, true, THRESHOLD, T + 60));
    }

    @Test
    public void toggledInheritAndVoiceAreDisabledButPushToTalkIsNot() {
        assertTrue(CaptureActivation.isDisabled(Type.VOICE, true));
        assertTrue(CaptureActivation.isDisabled(Type.INHERIT, true));
        assertFalse(CaptureActivation.isDisabled(Type.PUSH_TO_TALK, true));
        assertFalse(CaptureActivation.isDisabled(Type.VOICE, false));
        assertFalse(CaptureActivation.isDisabled(Type.INHERIT, false));
    }

    @Test
    public void voiceFollowsTheParentResultBeforeItsOwnDetection() {
        CaptureActivation activation = new CaptureActivation();
        // Silence, but the parent started: this one starts too and holds for 500 ms after the parent's last frame.
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.ACTIVATED, Type.VOICE, false, false, THRESHOLD, T));
        assertTrue(activation.isActive());
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.NOT_ACTIVATED, Type.VOICE, false, false, THRESHOLD, T + 500));
        assertEquals(Result.END, activation.process(SILENCE, Result.NOT_ACTIVATED, Type.VOICE, false, false, THRESHOLD, T + 501));
        // The parent's END ends it at once, loud or not.
        activation.process(SILENCE, Result.ACTIVATED, Type.VOICE, false, false, THRESHOLD, T + 600);
        assertEquals(Result.END, activation.process(LOUD, Result.END, Type.VOICE, false, false, THRESHOLD, T + 620));
        assertFalse(activation.isActive());
    }

    @Test
    public void voiceDetectsItsOwnVoiceWhenTheParentIsQuiet() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.NOT_ACTIVATED, activation.process(QUIET, Result.NOT_ACTIVATED, Type.VOICE, false, false, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(LOUD, Result.NOT_ACTIVATED, Type.VOICE, false, false, THRESHOLD, T + 20));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, null, Type.VOICE, false, false, THRESHOLD, T + 520));
        assertEquals(Result.END, activation.process(SILENCE, null, Type.VOICE, false, false, THRESHOLD, T + 521));
    }

    @Test
    public void inheritMirrorsTheParentResult() {
        CaptureActivation activation = new CaptureActivation();
        // Whatever the samples are: no parent result means nothing to inherit.
        assertEquals(Result.NOT_ACTIVATED, activation.process(LOUD, null, Type.INHERIT, false, false, THRESHOLD, T));
        assertFalse(activation.isActive());
        assertEquals(Result.NOT_ACTIVATED, activation.process(LOUD, Result.NOT_ACTIVATED, Type.INHERIT, false, false, THRESHOLD, T + 20));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.ACTIVATED, Type.INHERIT, false, false, THRESHOLD, T + 40));
        assertTrue(activation.isActive());
        assertEquals(Result.END, activation.process(SILENCE, Result.END, Type.INHERIT, false, false, THRESHOLD, T + 60));
        assertFalse(activation.isActive());
    }

    @Test
    public void toggledInheritEndsOnceAndStaysOff() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.ACTIVATED, Type.INHERIT, false, false, THRESHOLD, T));
        assertEquals(Result.END, activation.process(SILENCE, Result.ACTIVATED, Type.INHERIT, true, false, THRESHOLD, T + 20));
        assertEquals(Result.NOT_ACTIVATED, activation.process(SILENCE, Result.ACTIVATED, Type.INHERIT, true, false, THRESHOLD, T + 40));
        assertFalse(activation.isActive());
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.ACTIVATED, Type.INHERIT, false, false, THRESHOLD, T + 60));
    }

    @Test
    public void toggledVoiceIgnoresTheParent() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.NOT_ACTIVATED, activation.process(LOUD, Result.ACTIVATED, Type.VOICE, true, false, THRESHOLD, T));
        assertFalse(activation.isActive());
    }

    @Test
    public void pushToTalkIgnoresTheParentResult() {
        CaptureActivation activation = new CaptureActivation();
        assertEquals(Result.NOT_ACTIVATED, activation.process(LOUD, Result.ACTIVATED, Type.PUSH_TO_TALK, false, false, THRESHOLD, T));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.NOT_ACTIVATED, Type.PUSH_TO_TALK, false, true, THRESHOLD, T + 20));
        assertEquals(Result.ACTIVATED, activation.process(SILENCE, Result.END, Type.PUSH_TO_TALK, false, false, THRESHOLD, T + 370));
        assertEquals(Result.END, activation.process(SILENCE, null, Type.PUSH_TO_TALK, false, false, THRESHOLD, T + 371));
    }

    @Test
    public void microphoneGainNeverClips() {
        short[] quiet = tone(1000);
        short[] boosted = new MicrophoneGain().process(quiet.clone(), 2F);
        assertEquals(2 * max(quiet), max(boosted), 2);

        short[] loud = tone(30000);
        MicrophoneGain gain = new MicrophoneGain();
        assertTrue(max(gain.process(loud.clone(), 2F)) < Short.MAX_VALUE);
        // The safe multiplier of a loud frame is remembered for the next frames.
        assertTrue(max(gain.process(quiet.clone(), 2F)) < 2 * max(quiet) - 100);
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

    private static int max(short[] pcm) {
        int max = 0;
        for (short sample : pcm) max = Math.max(max, Math.abs((int) sample));
        return max;
    }

    private static short[] tone(int amplitude) {
        short[] pcm = new short[960];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 48_000.0) * amplitude);
        return pcm;
    }
}
