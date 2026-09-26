package su.plo.voice.platform.forge.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import su.plo.voice.platform.forge.debug.VoiceDebug;

/**
 * Upstream StreamAlSource: one OpenAL source fed through a ring of queued buffers.
 * Lives in the voice output context; every call happens on the playback thread.
 */
@SideOnly(Side.CLIENT)
final class StreamSource {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    private static final VoiceDebug DEBUG = VoiceDebug.CLIENT;
    private static final int QUEUE_LIMIT = 100;
    /**
     * Upstream loops again right after a restart or re-prime ({@code continue}); the pass after those settles.
     * ponytail: bounded so a broken driver cannot spin the playback thread; upstream has no bound.
     */
    private static final int MAX_PASSES = 4;

    /** What the upstream stream loop does after refilling the processed buffers. */
    enum Step {
        NONE,
        /** AlStreamSourceStoppedEvent: a real underrun; the buffers are re-primed with silence and the source resets. */
        REPRIME,
        PLAY
    }

    final boolean stereo;
    /** Upstream advanced.al_playback_buffers. */
    private final int numBuffers;
    private final int pointer;
    private final IntBuffer buffers;
    private final int format;
    private final int sampleRate;
    private final short[] emptyBuffer;
    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private ShortBuffer upload;
    private boolean emptyFilled;
    private int availableBuffer = -1;
    private long lastBufferTime;
    private float gain;
    // Diagnostics, counted only while debug logging is enabled; never the audio itself.
    private long submitted;
    private long consumed;
    private long dropped;
    private long restarts;
    private long reprimes;
    private long lastProgress;
    private int lastError;

    StreamSource(boolean stereo, int sampleRate, int frameSize, int numBuffers, long now) {
        this.stereo = stereo;
        this.numBuffers = numBuffers;
        this.format = stereo ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        this.sampleRate = sampleRate;
        this.emptyBuffer = new short[frameSize * (stereo ? 2 : 1)];
        this.upload = BufferUtils.createShortBuffer(emptyBuffer.length);

        AL10.alGetError();
        pointer = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new IllegalStateException("Failed to allocate an OpenAL source");
        buffers = BufferUtils.createIntBuffer(numBuffers);
        AL10.alGenBuffers(buffers);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteSources(pointer);
            throw new IllegalStateException("Failed to allocate OpenAL buffers");
        }
        // Upstream: volume sliders go above 1; the context has no distance model, gain is computed per frame.
        AL10.alSourcef(pointer, AL10.AL_MAX_GAIN, 4F);
        queueWithEmptyBuffers();
        fillQueue();
        lastBufferTime = now;
        lastProgress = now;
    }

    void write(short[] samples, long now) {
        short[] frame = samples.length == 0 ? emptyBuffer : samples;
        if (queue.size() > QUEUE_LIMIT) {
            if (DEBUG.enabled()) dropped++;
            return;
        }
        queue.add(frame);
        if (frame != emptyBuffer) {
            emptyFilled = false;
            lastBufferTime = now;
        }
        if (DEBUG.enabled()) submitted++;
    }

    /**
     * Upstream stream loop body: refills the processed buffers, then restarts or re-primes the source from the
     * Java queue size taken <em>before</em> the refill. Returns true when the stream really ran dry and was
     * re-primed with silence (upstream AlStreamSourceStoppedEvent).
     */
    boolean update(long now) {
        boolean stopped = false;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int queueSize = queue.size();
            int processed = AL10.alGetSourcei(pointer, AL10.AL_BUFFERS_PROCESSED);
            checkErrors("Get processed buffers");
            while (processed > 0 || availableBuffer != -1) {
                if (availableBuffer == -1) {
                    int buffer = AL10.alSourceUnqueueBuffers(pointer);
                    if (checkErrors("Unqueue buffer")) break;
                    availableBuffer = buffer;
                    if (DEBUG.enabled()) {
                        consumed++;
                        lastProgress = now;
                    }
                }
                if (fillAndPushBuffer(availableBuffer)) {
                    availableBuffer = -1;
                    processed--;
                } else {
                    break;
                }
            }

            Step step = step(AL10.alGetSourcei(pointer, AL10.AL_SOURCE_STATE), queueSize, emptyFilled);
            if (step == Step.REPRIME) {
                removeProcessedBuffers();
                availableBuffer = -1;
                queueWithEmptyBuffers();
                fillQueue();
                stopped = true;
                if (DEBUG.enabled()) reprimes++;
            } else if (step == Step.PLAY) {
                AL10.alSourcePlay(pointer);
                checkErrors("Source play");
                if (DEBUG.enabled()) restarts++;
            } else {
                break;
            }
        }
        return stopped;
    }

    /**
     * Upstream StreamAlSource: a stopped source with nothing new queued is a real underrun; any queued audio, or a
     * source that never played, starts it again. Decided from the queue size before the refill: frames that were
     * just moved into OpenAL buffers must not be taken for an empty stream.
     */
    static Step step(int state, int queueSizeBeforeRefill, boolean emptyFilled) {
        if (state == AL10.AL_STOPPED && queueSizeBeforeRefill == 0 && !emptyFilled) return Step.REPRIME;
        if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED && queueSizeBeforeRefill > 0) return Step.PLAY;
        if (state == AL10.AL_INITIAL) return Step.PLAY;
        return Step.NONE;
    }

    long lastBufferTime() {
        return lastBufferTime;
    }

    void setGain(float gain) {
        this.gain = gain;
        AL10.alSourcef(pointer, AL10.AL_GAIN, gain);
    }

    void setPosition(boolean relative, float x, float y, float z) {
        AL10.alSourcei(pointer, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSource3f(pointer, AL10.AL_POSITION, x, y, z);
    }

    /** Upstream closeSync. */
    void close() {
        AL10.alSourceStop(pointer);
        checkErrors("Source stop");
        queue.clear();
        // A source still in its initial state is played and stopped so its buffers can be deleted.
        if (AL10.alGetSourcei(pointer, AL10.AL_SOURCE_STATE) == AL10.AL_INITIAL) {
            AL10.alSourcePlay(pointer);
            checkErrors("Source play");
            AL10.alSourceStop(pointer);
            checkErrors("Source stop");
        }
        removeProcessedBuffers();
        AL10.alDeleteBuffers(buffers);
        checkErrors("Delete buffers");
        AL10.alDeleteSources(pointer);
        checkErrors("Delete source");
    }

    /** Playback thread, while debug logging is enabled: the OpenAL side of the stream, no audio. */
    String describe(long now) {
        int state = AL10.alGetSourcei(pointer, AL10.AL_SOURCE_STATE);
        int queued = AL10.alGetSourcei(pointer, AL10.AL_BUFFERS_QUEUED);
        int processed = AL10.alGetSourcei(pointer, AL10.AL_BUFFERS_PROCESSED);
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) lastError = error;
        return "alState=" + stateName(state) + ", alQueued=" + queued + ", alProcessed=" + processed
                + ", pcmQueue=" + queue.size() + ", availableBuffer=" + (availableBuffer != -1) + ", emptyFilled=" + emptyFilled
                + ", submitted=" + submitted + ", consumed=" + consumed + ", dropped=" + dropped + ", restarts=" + restarts
                + ", reprimes=" + reprimes + ", gain=" + gain + ", lastWriteAge=" + (now - lastBufferTime) + "ms"
                + ", lastProgressAge=" + (now - lastProgress) + "ms, lastAlError="
                + (lastError == AL10.AL_NO_ERROR ? "none" : "0x" + Integer.toHexString(lastError));
    }

    static String stateName(int state) {
        switch (state) {
            case AL10.AL_INITIAL: return "INITIAL";
            case AL10.AL_PLAYING: return "PLAYING";
            case AL10.AL_PAUSED: return "PAUSED";
            case AL10.AL_STOPPED: return "STOPPED";
            default: return "0x" + Integer.toHexString(state);
        }
    }

    private void queueWithEmptyBuffers() {
        for (int i = 0; i < numBuffers; i++) queue.add(emptyBuffer);
        emptyFilled = true;
    }

    private void fillQueue() {
        for (int i = 0; i < numBuffers; i++) fillAndPushBuffer(buffers.get(i));
    }

    private boolean fillAndPushBuffer(int buffer) {
        short[] samples = queue.poll();
        if (samples == null) return false;
        if (upload.capacity() < samples.length) upload = BufferUtils.createShortBuffer(samples.length);
        upload.clear();
        upload.put(samples).flip();
        AL10.alBufferData(buffer, format, upload, sampleRate);
        if (checkErrors("Assigning buffer data")) return false;
        AL10.alSourceQueueBuffers(pointer, buffer);
        return !checkErrors("Queue buffer data");
    }

    private void removeProcessedBuffers() {
        int processed = AL10.alGetSourcei(pointer, AL10.AL_BUFFERS_PROCESSED);
        checkErrors("Get processed buffers");
        while (processed-- > 0) {
            AL10.alSourceUnqueueBuffers(pointer);
            checkErrors("Unqueue buffer");
        }
    }

    /** Upstream AlUtil.checkErrors. */
    private boolean checkErrors(String section) {
        int error = AL10.alGetError();
        if (error == AL10.AL_NO_ERROR) return false;
        lastError = error;
        LOGGER.error("{}: {}", section, errorMessage(error));
        return true;
    }

    private static String errorMessage(int error) {
        switch (error) {
            case AL10.AL_INVALID_NAME: return "Invalid name parameter.";
            case AL10.AL_INVALID_ENUM: return "Invalid enumerated parameter value.";
            case AL10.AL_INVALID_VALUE: return "Invalid parameter parameter value.";
            case AL10.AL_INVALID_OPERATION: return "Invalid operation.";
            case AL10.AL_OUT_OF_MEMORY: return "Unable to allocate memory.";
            default: return "An unrecognized error occurred.";
        }
    }
}
