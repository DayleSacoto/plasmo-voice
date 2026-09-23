package su.plo.voice.platform.forge.audio.codec;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import su.plo.voice.proto.data.audio.codec.CodecInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusEncoderInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusMode;

/** Port of upstream OpusCodecSupplier: native opus-jni-rust first, pure-Java Concentus as fallback. */
public final class OpusCodec {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    private static volatile boolean nativesFailedToLoad;

    private OpusCodec() {
    }

    public static AudioEncoder createEncoder(CodecInfo codecInfo, int sampleRate, boolean stereo, int mtuSize) {
        OpusEncoderInfo info;
        try {
            info = new OpusEncoderInfo(codecInfo);
        } catch (IOException e) {
            throw new IllegalStateException("Bad codec info received", e);
        }
        if (isNativesSupported()) {
            try {
                Class.forName("com.plasmoverse.opus.OpusEncoder");
                NativeOpusEncoder encoder = new NativeOpusEncoder(sampleRate, stereo, info.getMode(), mtuSize);
                encoder.encoder.setBitrate(info.getBitrate());
                return encoder;
            } catch (ClassNotFoundException ignored) {
                // natives are not packaged
            } catch (Exception | LinkageError e) {
                LOGGER.warn("Failed to load native opus. Falling back to pure java impl", e);
                nativesFailedToLoad = true;
            }
        }
        try {
            JavaOpusEncoder encoder = new JavaOpusEncoder(sampleRate, stereo, info.getMode(), mtuSize);
            encoder.encoder.setBitrate(info.getBitrate());
            return encoder;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open java opus encoder", e);
        }
    }

    public static AudioDecoder createDecoder(int sampleRate, boolean stereo, int frameSize) {
        if (isNativesSupported()) {
            try {
                Class.forName("com.plasmoverse.opus.OpusDecoder");
                return new NativeOpusDecoder(sampleRate, stereo, frameSize);
            } catch (ClassNotFoundException ignored) {
                // natives are not packaged
            } catch (Exception | LinkageError e) {
                LOGGER.warn("Failed to load native opus. Falling back to pure java impl", e);
                nativesFailedToLoad = true;
            }
        }
        try {
            return new JavaOpusDecoder(sampleRate, stereo, frameSize);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open java opus decoder", e);
        }
    }

    /** Upstream Natives.kt: opt-out property, and macOS older than 11 has no compatible binary. */
    static boolean isNativesSupported() {
        if (nativesFailedToLoad || Boolean.getBoolean("plasmovoice.disable_natives")) return false;
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return true;
        try {
            return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]) >= 11;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static final class NativeOpusEncoder implements AudioEncoder {
        private final com.plasmoverse.opus.OpusEncoder encoder;

        NativeOpusEncoder(int sampleRate, boolean stereo, OpusMode mode, int mtuSize) throws Exception {
            com.plasmoverse.opus.OpusMode nativeMode = null;
            for (com.plasmoverse.opus.OpusMode candidate : com.plasmoverse.opus.OpusMode.values()) {
                if (candidate.getApplication() == mode.getApplication()) nativeMode = candidate;
            }
            if (nativeMode == null) throw new IOException("Invalid opus application mode");
            this.encoder = com.plasmoverse.opus.OpusEncoder.create(sampleRate, stereo, mtuSize, nativeMode);
        }

        @Override
        public byte[] encode(short[] samples) throws IOException {
            try {
                return encoder.encode(samples);
            } catch (com.plasmoverse.opus.OpusException e) {
                throw new IOException("Failed to encode audio", e);
            }
        }

        @Override
        public int getBitrate() {
            try {
                return encoder.getBitrate();
            } catch (com.plasmoverse.opus.OpusException e) {
                return 0;
            }
        }

        @Override
        public void reset() {
            encoder.reset();
        }

        @Override
        public void close() {
            if (encoder.isOpen()) encoder.close();
        }
    }

    static final class JavaOpusEncoder implements AudioEncoder {
        private final org.concentus.OpusEncoder encoder;
        private final int channels;
        private final byte[] buffer;

        JavaOpusEncoder(int sampleRate, boolean stereo, OpusMode mode, int mtuSize) throws IOException {
            this.channels = stereo ? 2 : 1;
            this.buffer = new byte[mtuSize];
            try {
                this.encoder = new org.concentus.OpusEncoder(sampleRate, channels, application(mode));
            } catch (org.concentus.OpusException e) {
                throw new IOException("Failed to open opus encoder", e);
            }
        }

        @Override
        public byte[] encode(short[] samples) throws IOException {
            try {
                int length = encoder.encode(samples, 0, samples.length / channels, buffer, 0, buffer.length);
                byte[] encoded = new byte[length];
                System.arraycopy(buffer, 0, encoded, 0, length);
                return encoded;
            } catch (org.concentus.OpusException e) {
                throw new IOException("Failed to encode audio", e);
            }
        }

        @Override
        public int getBitrate() {
            return encoder.getBitrate();
        }

        @Override
        public void reset() {
            encoder.resetState();
        }

        @Override
        public void close() {
            // Concentus has no native state.
        }

        private static org.concentus.OpusApplication application(OpusMode mode) {
            switch (mode) {
                case AUDIO:
                    return org.concentus.OpusApplication.OPUS_APPLICATION_AUDIO;
                case RESTRICTED_LOWDELAY:
                    return org.concentus.OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY;
                default:
                    return org.concentus.OpusApplication.OPUS_APPLICATION_VOIP;
            }
        }
    }

    static final class NativeOpusDecoder implements AudioDecoder {
        private final com.plasmoverse.opus.OpusDecoder decoder;

        NativeOpusDecoder(int sampleRate, boolean stereo, int frameSize) throws Exception {
            this.decoder = com.plasmoverse.opus.OpusDecoder.create(sampleRate, stereo, frameSize);
        }

        @Override
        public short[] decode(byte[] encoded) throws IOException {
            try {
                return decoder.decode(encoded);
            } catch (com.plasmoverse.opus.OpusException e) {
                throw new IOException("Failed to decode audio", e);
            }
        }

        @Override
        public void reset() {
            decoder.reset();
        }

        @Override
        public void close() {
            if (decoder.isOpen()) decoder.close();
        }
    }

    static final class JavaOpusDecoder implements AudioDecoder {
        private final org.concentus.OpusDecoder decoder;
        private final int channels;
        private final int frameSize;
        private final short[] buffer;

        JavaOpusDecoder(int sampleRate, boolean stereo, int frameSize) throws IOException {
            this.channels = stereo ? 2 : 1;
            this.frameSize = frameSize;
            this.buffer = new short[frameSize * channels];
            try {
                this.decoder = new org.concentus.OpusDecoder(sampleRate, channels);
            } catch (org.concentus.OpusException e) {
                throw new IOException("Failed to open opus decoder", e);
            }
        }

        @Override
        public short[] decode(byte[] encoded) throws IOException {
            boolean plc = encoded == null || encoded.length == 0;
            try {
                int samples = plc
                        ? decoder.decode(null, 0, 0, buffer, 0, frameSize, false)
                        : decoder.decode(encoded, 0, encoded.length, buffer, 0, frameSize, false);
                // Upstream quirk kept as-is: PLC returns the per-channel sample count.
                short[] decoded = new short[plc ? samples : samples * channels];
                System.arraycopy(buffer, 0, decoded, 0, decoded.length);
                return decoded;
            } catch (org.concentus.OpusException e) {
                throw new IOException("Failed to decode audio", e);
            }
        }

        @Override
        public void reset() {
            decoder.resetState();
        }

        @Override
        public void close() {
            // Concentus has no native state.
        }
    }
}
