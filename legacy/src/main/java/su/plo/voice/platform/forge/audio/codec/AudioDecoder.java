package su.plo.voice.platform.forge.audio.codec;

import java.io.IOException;

/** Decodes one frame; null or empty input runs packet loss concealment. One instance per source. */
public interface AudioDecoder extends AutoCloseable {
    short[] decode(byte[] encoded) throws IOException;

    void reset();

    @Override
    void close();
}
