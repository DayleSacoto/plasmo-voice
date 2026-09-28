package su.plo.voice.platform.forge.client.audio;

import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.StaticSourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.slib.api.position.Pos3d;

import static org.junit.Assert.*;

public class VoiceLevelerTest {
    private static final int FRAME = 960;
    private static final double TARGET = VoiceLeveler.DEFAULT_TARGET_DB;
    /** One second of 20 ms frames. */
    private static final int SECOND = 50;

    private final VoiceLeveler leveler = new VoiceLeveler();

    @Test
    public void disabledLeavesTheFrameUntouched() {
        short[] frame = sine(-35D, 1);
        short[] copy = frame.clone();
        assertSame(frame, leveler.process(frame, 1, false, TARGET));
        assertArrayEquals(copy, frame);
    }

    @Test
    public void speechNearTheTargetStaysNearUnity() {
        run(sine(-18.5D, 1), 5 * SECOND);
        assertEquals(0D, leveler.gainDb(), 1D);
    }

    @Test
    public void loudSpeechIsReducedGraduallyAndQuickly() {
        short[] loud = sine(-6D, 1);
        leveler.process(loud, 1, true, TARGET);
        double first = leveler.gainDb();
        assertTrue("no jump on the first frame: " + first, first > -4D && first < 0D);
        run(loud, 10); // 200 ms
        assertTrue("attack within 200 ms: " + leveler.gainDb(), leveler.gainDb() < -10D);
        run(loud, 2 * SECOND);
        assertEquals(-12D, leveler.gainDb(), 0.5D);
    }

    @Test
    public void quietSpeechIsBoostedGradually() {
        short[] quiet = sine(-26D, 1);
        leveler.process(quiet, 1, true, TARGET);
        assertTrue(leveler.gainDb() < 1D);
        run(quiet, SECOND / 2);
        double halfSecond = leveler.gainDb();
        assertTrue("release is slow: " + halfSecond, halfSecond > 1D && halfSecond < 6D);
        run(quiet, 5 * SECOND);
        assertEquals(8D, leveler.gainDb(), 0.5D);
    }

    @Test
    public void boostNeverExceedsTheCap() {
        short[] veryQuiet = sine(-45D, 1);
        for (int i = 0; i < 20 * SECOND; i++) {
            leveler.process(veryQuiet, 1, true, TARGET);
            assertTrue(leveler.gainDb() <= VoiceLeveler.MAX_BOOST_DB + 1e-9);
        }
        assertEquals(VoiceLeveler.MAX_BOOST_DB, leveler.gainDb(), 0.1D);
    }

    @Test
    public void reductionNeverExceedsTheCap() {
        short[] square = new short[FRAME];
        for (int i = 0; i < FRAME; i++) square[i] = (short) (i % 2 == 0 ? 30_000 : -30_000); // about -0.8 dBFS RMS
        for (int i = 0; i < 5 * SECOND; i++) {
            leveler.process(square, 1, true, VoiceLeveler.MIN_TARGET_DB);
            assertTrue(leveler.gainDb() >= -VoiceLeveler.MAX_REDUCTION_DB - 1e-9);
        }
        assertEquals(-VoiceLeveler.MAX_REDUCTION_DB, leveler.gainDb(), 0.1D);
    }

    @Test
    public void silenceAndBackgroundNoiseDoNotRaiseTheGain() {
        // From a fresh state: only background noise, the gain never leaves unity.
        run(sine(-58D, 1), 30 * SECOND);
        assertEquals(0D, leveler.gainDb(), 1e-9);
        run(new short[FRAME], SECOND);
        assertEquals(0D, leveler.gainDb(), 1e-9);

        // After quiet speech: pauses hold the gain instead of chasing the target.
        run(sine(-26D, 1), 5 * SECOND);
        double speaking = leveler.gainDb();
        run(sine(-58D, 1), 30 * SECOND);
        assertEquals(speaking, leveler.gainDb(), 1e-9);
    }

    @Test
    public void gainMovesSmoothlyBetweenAndWithinFrames() {
        double last = 0D;
        // Below the limiter: a 18 dB jump moves the gain by at most 18 dB * ATTACK (about 4 dB) per frame.
        short[][] steps = {sine(-30D, 1), sine(-12D, 1), sine(-30D, 1)};
        for (short[] step : steps) {
            for (int i = 0; i < 3 * SECOND; i++) {
                leveler.process(step, 1, true, TARGET);
                assertTrue("gain step per frame", Math.abs(leveler.gainDb() - last) < 4.5D);
                last = leveler.gainDb();
            }
        }

        // Inside a frame the gain is ramped: a constant input comes out as a monotonic ramp, not a step.
        VoiceLeveler ramp = new VoiceLeveler();
        short[] constant = new short[FRAME];
        Arrays.fill(constant, (short) 16_000); // about -6 dBFS
        short[] out = ramp.process(constant, 1, true, TARGET);
        assertEquals(16_000, out[0], 5);
        assertEquals(16_000 * Math.pow(10D, ramp.gainDb() / 20D), out[FRAME - 1], 2D);
        for (int i = 1; i < FRAME; i++) {
            assertTrue(out[i] <= out[i - 1]);
            assertTrue(out[i - 1] - out[i] <= 5);
        }
    }

    @Test
    public void attackIsFasterThanRelease() {
        run(sine(-18D, 1), 2 * SECOND);
        int attack = framesUntil(sine(-6D, 1), -11D);
        VoiceLeveler quiet = new VoiceLeveler();
        int release = 0;
        while (quiet.gainDb() < 11D) {
            quiet.process(sine(-30D, 1), 1, true, TARGET);
            release++;
        }
        assertTrue("attack " + attack + " frames, release " + release + " frames", attack * 5 < release);
    }

    @Test
    public void boostedPeaksAreLimitedWithoutWrapping() {
        run(sine(-45D, 1), 20 * SECOND); // full +12 dB boost
        short[] peaky = sine(-45D, 1);
        peaky[FRAME / 2] = 20_000;
        peaky[FRAME / 2 + 1] = -20_000;
        // The onset frame itself, which starts from the full boost, is limited too.
        short[] first = leveler.process(peaky, 1, true, TARGET);
        assertTrue("sign kept, no wrap-around", first[FRAME / 2] > 0 && first[FRAME / 2 + 1] < 0);
        for (short sample : first) assertTrue(Math.abs(sample) <= VoiceLeveler.CEILING);
        assertEquals(VoiceLeveler.CEILING, first[FRAME / 2], 1D);
        short[] second = leveler.process(peaky, 1, true, TARGET);
        for (short sample : second) assertTrue(Math.abs(sample) <= VoiceLeveler.CEILING);

        // Full-scale input under a cut is left to the gain: nothing to limit.
        VoiceLeveler cut = new VoiceLeveler();
        short[] square = new short[FRAME];
        for (int i = 0; i < FRAME; i++) square[i] = (short) (i % 2 == 0 ? 32_767 : -32_768);
        short[] out = null;
        for (int i = 0; i < SECOND; i++) out = cut.process(square, 1, true, TARGET);
        assertEquals(32_767 * Math.pow(10D, cut.gainDb() / 20D), out[FRAME - 2], 2D);
    }

    @Test
    public void stereoChannelsShareOneGain() {
        short[] stereo = new short[FRAME * 2];
        short[] left = sine(-24D, 1);
        for (int i = 0; i < FRAME; i++) {
            stereo[i * 2] = left[i];
            stereo[i * 2 + 1] = (short) (left[i] / 4);
        }
        short[] out = stereo;
        for (int i = 0; i < 3 * SECOND; i++) out = leveler.process(stereo, 2, true, TARGET);
        assertTrue(leveler.gainDb() > 3D);
        for (int i = 0; i < FRAME; i++) {
            if (Math.abs(stereo[i * 2]) < 2_000) continue;
            assertEquals((double) stereo[i * 2] / stereo[i * 2 + 1], (double) out[i * 2] / out[i * 2 + 1], 0.01D);
        }
    }

    @Test
    public void everySpeakerHasItsOwnState() {
        VoiceLeveler loud = new VoiceLeveler();
        VoiceLeveler quiet = new VoiceLeveler();
        for (int i = 0; i < 5 * SECOND; i++) {
            loud.process(sine(-8D, 1), 1, true, TARGET);
            quiet.process(sine(-28D, 1), 1, true, TARGET);
        }
        assertEquals(-10D, loud.gainDb(), 0.5D);
        assertEquals(10D, quiet.gainDb(), 0.5D);
    }

    @Test
    public void aNewOrReenabledLevelerStartsClean() {
        run(sine(-6D, 1), 2 * SECOND);
        assertTrue(leveler.gainDb() < -10D);
        assertEquals(0D, new VoiceLeveler().gainDb(), 0D);

        leveler.process(sine(-6D, 1), 1, false, TARGET); // disabled: state forgotten
        assertEquals(0D, leveler.gainDb(), 0D);
        assertTrue(Double.isNaN(leveler.levelDb()));
    }

    @Test
    public void targetShiftsTheCorrectionByTheSameAmount() {
        VoiceLeveler low = new VoiceLeveler();
        VoiceLeveler high = new VoiceLeveler();
        for (int i = 0; i < 10 * SECOND; i++) {
            low.process(sine(-24D, 1), 1, true, -21D);
            high.process(sine(-24D, 1), 1, true, -15D);
        }
        assertEquals(3D, low.gainDb(), 0.5D);
        assertEquals(9D, high.gainDb(), 0.5D);
    }

    /**
     * Placement: leveling runs on the decoded frame before OpenAL. Distance, occlusion and direction only change
     * the OpenAL source gain, so the leveled PCM of a near and a far speaker is identical and the far one stays
     * quieter by exactly its distance gain.
     */
    @Test
    public void levelingRunsBeforeAndIndependentlyOfSpatialGain() {
        ClientState state = new ClientState();
        state.setVoiceLeveling(true);
        state.setSoundOcclusion(true);
        state.setDirectionalSources(true);
        VoiceSource near = player();
        VoiceSource far = player();
        near.position = new double[] {1D, 0D, 0D};
        far.position = new double[] {20D, 0D, 0D};
        far.occlusion = 0.9D;

        short[] quiet = sine(-28D, 1);
        short[] nearPcm = null;
        short[] farPcm = null;
        for (int i = 0; i < 3 * SECOND; i++) {
            nearPcm = near.prepare(quiet, 1, i, state);
            farPcm = far.prepare(quiet, 1, i, state);
        }
        assertArrayEquals(nearPcm, farPcm);
        assertTrue("quiet speaker boosted", VoiceLeveler.rmsDb(nearPcm) > VoiceLeveler.rmsDb(VoiceSource.fadeIn(quiet, 1)) + 5D);
        assertTrue(VoiceSource.distanceGain(20D, 24D, true) < VoiceSource.distanceGain(1D, 24D, true));

        state.setVoiceLeveling(false);
        VoiceSource off = player();
        assertArrayEquals(VoiceSource.fadeIn(quiet, 1), off.prepare(quiet, 1, 0, state));
    }

    @Test
    public void addonSourcesAreNotLeveled() {
        ClientState state = new ClientState();
        state.setVoiceLeveling(true);
        VoiceSource source = new VoiceSource(new StaticSourceInfo("addon", UUID.randomUUID(), UUID.randomUUID(), null,
                (byte) 0, new OpusDecoderInfo(), false, true, 0, new Pos3d(0D, 0D, 0D), new Pos3d(0D, 0D, 0D)), false, 1, 0L);
        short[] quiet = sine(-28D, 1);
        short[] out = null;
        for (int i = 0; i < 3 * SECOND; i++) out = source.prepare(quiet, 1, i, state);
        assertArrayEquals(VoiceSource.fadeIn(quiet, 1), out);
    }

    private static VoiceSource player() {
        return new VoiceSource(new PlayerSourceInfo("plasmovoice", UUID.randomUUID(), UUID.randomUUID(), null, (byte) 0,
                new OpusDecoderInfo(), false, true, 0, new VoicePlayerInfo(UUID.randomUUID(), "speaker", false, false, false)),
                false, 1, 0L);
    }

    private int framesUntil(short[] frame, double gainDb) {
        int frames = 0;
        while (leveler.gainDb() > gainDb) {
            leveler.process(frame, 1, true, TARGET);
            frames++;
        }
        return frames;
    }

    private void run(short[] frame, int frames) {
        for (int i = 0; i < frames; i++) leveler.process(frame, 1, true, TARGET);
    }

    /** A 440 Hz tone with the given RMS level in dBFS, 20 ms at 48 kHz. */
    static short[] sine(double rmsDb, int channels) {
        double amplitude = 32768D * Math.pow(10D, rmsDb / 20D) * Math.sqrt(2D);
        short[] samples = new short[FRAME * channels];
        for (int i = 0; i < FRAME; i++) {
            short value = (short) Math.round(amplitude * Math.sin(2D * Math.PI * 440D * i / 48_000D));
            for (int channel = 0; channel < channels; channel++) samples[i * channels + channel] = value;
        }
        return samples;
    }
}
