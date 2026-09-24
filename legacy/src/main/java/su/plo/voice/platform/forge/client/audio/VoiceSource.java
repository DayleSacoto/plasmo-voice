package su.plo.voice.platform.forge.client.audio;

import java.io.IOException;
import java.security.GeneralSecurityException;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.audio.codec.AudioDecoder;
import su.plo.voice.platform.forge.audio.codec.OpusCodec;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.encryption.AesEncryption;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.SourceInfo;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * One remote voice source (upstream BaseClientAudioSource + ClientPlayerSource).
 * Packets and source info arrive from the UDP/client threads; decoding and OpenAL writes run on the playback thread.
 */
@SideOnly(Side.CLIENT)
final class VoiceSource {
    /** Upstream waits for late frames after SourceAudioEnd before resetting the decoder. */
    static final long END_DELAY_MS = 100L;
    /** Upstream closeTimeoutMs: the stream is reset when SourceAudioEnd was lost. */
    static final long RESET_TIMEOUT_MS = 500L;
    /** Upstream StreamAlSource closes an idle OpenAL source after 25 seconds. */
    static final long STREAM_IDLE_CLOSE_MS = 25_000L;
    /** Upstream traces per audio frame on its source coroutine; world access here stays on the client thread. */
    static final long OCCLUSION_INTERVAL_MS = 50L;
    /** Upstream BaseClientAudioSource.OUTER_ANGLE. */
    private static final double OUTER_ANGLE = 180D;

    final JitterBuffer buffer = new JitterBuffer();
    volatile SourceInfo info;
    /** Eye position of the source player, or null while the entity is not loaded; set on the client thread. */
    volatile double[] position;
    /** Look vector of the source player, for directional sources; set on the client thread. */
    volatile double[] look;
    /** Upstream calculateOcclusion result, refreshed by the client thread at most every {@link #OCCLUSION_INTERVAL_MS}. */
    volatile double occlusion;
    /** Client thread only. */
    long occlusionAt;
    /** Upstream BaseClientAudioSource.canHear, read by the overlay. */
    volatile boolean canHear;
    private volatile long endRequestedAt = -1L;
    private volatile long endSequenceNumber = -1L;

    // Playback thread only.
    private AudioDecoder decoder;
    private boolean decoderStereo;
    private StreamSource stream;
    /** Upstream lastOcclusion: the applied occlusion follows the traced one in 0.05 steps per frame. */
    private double lastOcclusion = -1D;
    private long lastSequenceNumber = -1L;
    private long lastActivation;
    /** Upstream isActivated; written by the playback thread, read by the player icons. */
    volatile boolean activated;

    VoiceSource(SourceInfo info) {
        this.info = info;
    }

    void offer(SourceAudioPacket packet, long now) {
        buffer.offer(packet, now);
    }

    void end(SourceAudioEndPacket packet, long now) {
        buffer.offer(packet, now);
        endSequenceNumber = packet.getSequenceNumber();
        endRequestedAt = now;
    }

    /** Playback thread, with the voice output context current. */
    void pump(ClientConfig config, ClientState state, double[] listener, double volume, long now) {
        buffer.configure(state.isAdaptiveJitterBuffer(), state.getJitterPacketDelay());
        while (true) {
            Object next = buffer.poll(now);
            if (next == null) {
                // Upstream: when the adaptive schedule is due but the frame has not arrived, conceal it (PLC).
                if (!buffer.isAdaptive() || !activated || buffer.isEmpty() || now - lastActivation <= 20L
                        || decoder == null || stream == null || stream.stereo) break;
                try {
                    write(decoder.decode(null), lastSequenceNumber + 1, now);
                } catch (IOException e) {
                    break;
                }
                lastSequenceNumber++;
                lastActivation = now;
                continue;
            }
            if (next instanceof SourceAudioPacket) {
                process((SourceAudioPacket) next, config, state, listener, volume, now);
            } else if (activated) {
                lastSequenceNumber = ((SourceAudioEndPacket) next).getSequenceNumber();
            }
        }

        long endAt = endRequestedAt;
        if (endAt >= 0 && now - endAt >= END_DELAY_MS) {
            endRequestedAt = -1L;
            reset();
        }
        if (activated && now - lastActivation > RESET_TIMEOUT_MS) reset();

        if (stream == null) return;
        if (stream.update()) reset();
        if (now - stream.lastBufferTime() > STREAM_IDLE_CLOSE_MS) closeStream();
    }

    /** Playback thread: the output context is about to go away. */
    void closeStream() {
        if (stream != null) stream.close();
        stream = null;
    }

    /** Playback thread, on shutdown. */
    void release() {
        canHear = false;
        closeStream();
        if (decoder != null) decoder.close();
        decoder = null;
    }

    private void process(SourceAudioPacket packet, ClientConfig config, ClientState state, double[] listener, double volume, long now) {
        SourceInfo current = info;
        long sequenceNumber = packet.getSequenceNumber();
        if (stateDiff(current.getState(), packet.getSourceState()) >= 10) return;
        if (lastSequenceNumber >= 0 && sequenceNumber <= lastSequenceNumber
                && lastSequenceNumber - sequenceNumber < 10L) return;
        endRequestedAt = -1L;

        int sampleRate = config.getPacket().getCaptureInfo().getSampleRate();
        int frameSize = sampleRate / 1000 * 20;
        if (decoder == null || decoderStereo != current.isStereo()) {
            if (decoder != null) decoder.close();
            decoder = OpusCodec.createDecoder(sampleRate, current.isStereo(), frameSize);
            decoderStereo = current.isStereo();
            lastSequenceNumber = -1L;
        }
        if (stream != null && stream.stereo != current.isStereo()) closeStream();
        if (stream == null) stream = new StreamSource(current.isStereo(), sampleRate, frameSize, state.getAlPlaybackBuffers(), now);
        updateStream(current, state, packet.getDistance(), listener, volume);

        try {
            if (lastSequenceNumber >= 0) {
                long lost = sequenceNumber - (lastSequenceNumber + 1);
                // Upstream packet compensation: conceal up to four lost frames.
                for (long i = 1; lost >= 1 && lost <= 4 && i <= lost; i++) {
                    write(current.isStereo() ? new short[0] : decoder.decode(null), lastSequenceNumber + i, now);
                }
            }
            byte[] data = packet.getData();
            AesEncryption encryption = config.getEncryption();
            if (encryption != null) data = encryption.decrypt(data);
            write(decoder.decode(data), sequenceNumber, now);
        } catch (GeneralSecurityException | IOException ignored) {
            // A corrupted or foreign frame is dropped; the next one is independent.
        }

        lastSequenceNumber = sequenceNumber;
        lastActivation = now;
        activated = true;
    }

    private void write(short[] samples, long sequenceNumber, long now) {
        int channels = stream.stereo ? 2 : 1;
        if (!activated) {
            samples = fadeIn(samples, channels);
        } else if (sequenceNumber + 1 == endSequenceNumber) {
            samples = fadeOut(samples, channels);
        }
        stream.write(samples, now);
    }

    private void updateStream(SourceInfo current, ClientState state, short distance, double[] listener, double volume) {
        // ponytail: only player sources are positional; other types play centered until the API sources are backported.
        if (!(current instanceof PlayerSourceInfo)) {
            stream.setGain((float) volume);
            stream.setPosition(true, 0F, 0F, 0F);
            return;
        }
        double[] source = position;
        if (source == null || listener == null) {
            stream.setGain(0F);
            if (distance > 0) canHear = false;
            return;
        }
        double dx = source[0] - listener[0];
        double dy = source[1] - listener[1];
        double dz = source[2] - listener[2];
        double sourceDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Upstream updateSource order: occlusion, slider curve, directional angle gain, distance gain.
        if (state.isSoundOcclusion()) {
            double occlusion = smoothOcclusion(lastOcclusion, this.occlusion);
            if (lastOcclusion >= 0) lastOcclusion = occlusion;
            volume *= 1D - occlusion;
            if (lastOcclusion == -1D) lastOcclusion = occlusion;
        }
        volume = sliderGain(volume, state.isExponentialVolumeSlider());
        double[] look = this.look;
        if ((state.isDirectionalSources() || current.getAngle() > 0) && look != null && sourceDistance > 0) {
            double innerAngle = current.getAngle() > 0 ? current.getAngle() / 2D : state.getDirectionalSourcesAngle() / 2D;
            volume *= angleGain(-dx / sourceDistance, -dy / sourceDistance, -dz / sourceDistance, look, innerAngle,
                    state.isExponentialDistanceGain());
        }
        stream.setGain((float) Math.max(0D, volume * distanceGain(sourceDistance, distance, state.isExponentialDistanceGain())));
        if (distance > 0) canHear = sourceDistance <= distance;
        // Upstream advanced.panning off: the source plays at the listener, only its distance gain remains.
        if (state.isPanning()) {
            stream.setPosition(false, (float) source[0], (float) source[1], (float) source[2]);
        } else {
            stream.setPosition(true, 0F, 0F, 0F);
        }
    }

    private void reset() {
        canHear = false;
        if (!activated) return;
        if (decoder != null) decoder.reset();
        activated = false;
    }

    /** Upstream advanced.exponential_volume_slider (default on): quieter settings follow a cubic curve. */
    static double sliderGain(double volume, boolean exponential) {
        return exponential && volume < 1D ? volume * volume * volume : volume;
    }

    /** Upstream: towards a louder trace the applied occlusion rises by 0.05, towards a quieter one it falls by 0.05. */
    static double smoothOcclusion(double last, double traced) {
        if (last < 0) return traced;
        return traced > last ? Math.max(last + 0.05D, 0D) : Math.max(last - 0.05D, traced);
    }

    /**
     * Upstream directional gain: the angle between the speaker's look and the direction to the listener; outside
     * the inner cone the volume falls off towards 180 degrees (cubic with exponential distance gain).
     */
    static double angleGain(double toListenerX, double toListenerY, double toListenerZ, double[] look, double innerAngle,
                            boolean exponential) {
        double dot = toListenerX * look[0] + toListenerY * look[1] + toListenerZ * look[2];
        double angle = Math.toDegrees(Math.acos(Math.max(-1D, Math.min(1D, dot))));
        if (angle <= innerAngle) return 1D;
        double gain = 1D - (angle - innerAngle) / (OUTER_ANGLE - innerAngle);
        return exponential ? gain * gain * gain : gain;
    }

    /** Upstream calculateDistanceGain; advanced.exponential_distance_gain (default on) makes it cubic. */
    static double distanceGain(double sourceDistance, double maxDistance, boolean exponential) {
        if (maxDistance <= 0) return 1D;
        double gain = 1D - Math.min(sourceDistance, maxDistance) / maxDistance;
        return exponential ? gain * gain * gain : gain;
    }

    /** Upstream Byte.diff: forward distance between source states, wrapping at the byte range. */
    static byte stateDiff(byte current, byte other) {
        return (byte) (other > current ? other - current : Byte.MAX_VALUE - current + (other - Byte.MIN_VALUE) + 1);
    }

    static short[] fadeIn(short[] samples, int channels) {
        short[] processed = new short[samples.length];
        for (int index = 0; index < samples.length; index += channels) {
            float fade = Math.min(index / (float) samples.length, 1F);
            for (int channel = 0; channel < channels; channel++) {
                processed[index + channel] = (short) (samples[index + channel] * fade);
            }
        }
        return processed;
    }

    static short[] fadeOut(short[] samples, int channels) {
        short[] processed = new short[samples.length];
        for (int index = 0; index < samples.length; index += channels) {
            float fade = Math.max((samples.length - index) / (float) samples.length, 0F);
            for (int channel = 0; channel < channels; channel++) {
                processed[index + channel] = (short) (samples[index + channel] * fade);
            }
        }
        return processed;
    }
}
