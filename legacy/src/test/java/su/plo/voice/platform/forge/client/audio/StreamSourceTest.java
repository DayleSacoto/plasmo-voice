package su.plo.voice.platform.forge.client.audio;

import org.junit.Test;
import org.lwjgl.openal.AL10;
import su.plo.voice.platform.forge.client.audio.StreamSource.Step;

import static org.junit.Assert.assertEquals;

/**
 * Upstream StreamAlSource decisions after the processed buffers were refilled. The queue size is the one taken
 * before the refill; the old legacy loop looked at the queue after it, so fresh frames just moved into OpenAL
 * made a recoverable underrun look like the end of the stream.
 */
public class StreamSourceTest {
    @Test
    public void stoppedSourceWithFreshFramesRestartsInsteadOfResetting() {
        // Two frames were waiting before the refill moved them into OpenAL; the Java queue is now empty.
        assertEquals(Step.PLAY, StreamSource.step(AL10.AL_STOPPED, 2, false));
        assertEquals(Step.PLAY, StreamSource.step(AL10.AL_STOPPED, 1, true));
    }

    @Test
    public void stoppedSourceWithNothingQueuedIsARealUnderrun() {
        assertEquals(Step.REPRIME, StreamSource.step(AL10.AL_STOPPED, 0, false));
        // Already re-primed with silence: wait for audio without resetting again.
        assertEquals(Step.NONE, StreamSource.step(AL10.AL_STOPPED, 0, true));
    }

    @Test
    public void initialSourceStarts() {
        assertEquals(Step.PLAY, StreamSource.step(AL10.AL_INITIAL, 3, false));
        // The silence primed at creation starts playing too.
        assertEquals(Step.PLAY, StreamSource.step(AL10.AL_INITIAL, 0, true));
    }

    @Test
    public void playingOrPausedSourcesAreLeftAlone() {
        for (int state : new int[] {AL10.AL_PLAYING, AL10.AL_PAUSED}) {
            assertEquals(Step.NONE, StreamSource.step(state, 0, false));
            assertEquals(Step.NONE, StreamSource.step(state, 5, false));
            assertEquals(Step.NONE, StreamSource.step(state, 0, true));
        }
    }

    @Test
    public void stateNamesForDiagnostics() {
        assertEquals("STOPPED", StreamSource.stateName(AL10.AL_STOPPED));
        assertEquals("0x1", StreamSource.stateName(1));
    }
}
