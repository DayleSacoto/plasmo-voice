package su.plo.voice.platform.forge.audio.codec;

import java.io.IOException;

/** Encodes one 20 ms PCM frame; not thread-safe, one instance per capture stream. */
public interface AudioEncoder extends AutoCloseable {
    byte[] encode(short[] samples) throws IOException;

    int getBitrate();

    void reset();

    @Override
    void close();
}
