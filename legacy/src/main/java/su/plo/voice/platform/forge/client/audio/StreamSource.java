package su.plo.voice.platform.forge.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

/**
 * Upstream StreamAlSource: one OpenAL source fed through a ring of queued buffers.
 * Lives in the voice output context; every call happens on the playback thread.
 */
@SideOnly(Side.CLIENT)
final class StreamSource {
    /** Upstream advanced.al_playback_buffers default. */
    static final int NUM_BUFFERS = 5;
    private static final int QUEUE_LIMIT = 100;

    final boolean stereo;
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

    StreamSource(boolean stereo, int sampleRate, int frameSize, long now) {
        this.stereo = stereo;
        this.format = stereo ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        this.sampleRate = sampleRate;
        this.emptyBuffer = new short[frameSize * (stereo ? 2 : 1)];
        this.upload = BufferUtils.createShortBuffer(emptyBuffer.length);

        AL10.alGetError();
        pointer = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new IllegalStateException("Failed to allocate an OpenAL source");
        buffers = BufferUtils.createIntBuffer(NUM_BUFFERS);
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
    }

    void write(short[] samples, long now) {
        short[] frame = samples.length == 0 ? emptyBuffer : samples;
        if (queue.size() > QUEUE_LIMIT) return;
        queue.add(frame);
        if (frame != emptyBuffer) {
            emptyFilled = false;
            lastBufferTime = now;
        }
    }

    /** Refills processed buffers; returns true when the stream ran dry and was re-primed with silence. */
    boolean update() {
        int processed = AL10.alGetSourcei(pointer, AL10.AL_BUFFERS_PROCESSED);
        while (processed > 0 || availableBuffer != -1) {
            if (availableBuffer == -1) availableBuffer = AL10.alSourceUnqueueBuffers(pointer);
            if (!fillAndPushBuffer(availableBuffer)) break;
            availableBuffer = -1;
            processed--;
        }

        int state = AL10.alGetSourcei(pointer, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_STOPPED && queue.isEmpty() && !emptyFilled) {
            // A stopped source may drop its whole queue at once; all buffers are free again.
            AL10.alSourcei(pointer, AL10.AL_BUFFER, 0);
            availableBuffer = -1;
            queueWithEmptyBuffers();
            fillQueue();
            return true;
        }
        if (state == AL10.AL_INITIAL || (state == AL10.AL_STOPPED && !queue.isEmpty())) AL10.alSourcePlay(pointer);
        return false;
    }

    long lastBufferTime() {
        return lastBufferTime;
    }

    void setGain(float gain) {
        AL10.alSourcef(pointer, AL10.AL_GAIN, gain);
    }

    void setPosition(boolean relative, float x, float y, float z) {
        AL10.alSourcei(pointer, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSource3f(pointer, AL10.AL_POSITION, x, y, z);
    }

    void close() {
        AL10.alSourceStop(pointer);
        AL10.alDeleteSources(pointer);
        AL10.alDeleteBuffers(buffers);
        queue.clear();
    }

    private void queueWithEmptyBuffers() {
        for (int i = 0; i < NUM_BUFFERS; i++) queue.add(emptyBuffer);
        emptyFilled = true;
    }

    private void fillQueue() {
        for (int i = 0; i < NUM_BUFFERS; i++) fillAndPushBuffer(buffers.get(i));
    }

    private boolean fillAndPushBuffer(int buffer) {
        short[] samples = queue.poll();
        if (samples == null) return false;
        if (upload.capacity() < samples.length) upload = BufferUtils.createShortBuffer(samples.length);
        upload.clear();
        upload.put(samples).flip();
        AL10.alBufferData(buffer, format, upload, sampleRate);
        AL10.alSourceQueueBuffers(pointer, buffer);
        return true;
    }
}
