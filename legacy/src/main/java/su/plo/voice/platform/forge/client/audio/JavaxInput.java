package su.plo.voice.platform.forge.client.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;

/**
 * Upstream JavaxInputDevice and JavaxInputDeviceFactory: Java Sound microphone capture, used with
 * voice.use_javax_input and when the OpenAL microphone cannot open. Capture thread only, except {@link #deviceNames()}.
 */
@SideOnly(Side.CLIENT)
public final class JavaxInput implements AutoCloseable {
    private static final Line.Info CAPTURE_LINE = new Line.Info(TargetDataLine.class);

    private final TargetDataLine line;
    private final String name;

    private JavaxInput(TargetDataLine line, String name) {
        this.line = line;
        this.name = name;
    }

    /** Upstream VoiceServerInfo.createFormat: signed 16-bit little-endian PCM. */
    static AudioFormat format(int sampleRate, int channels) {
        return new AudioFormat(sampleRate, 16, channels, true, false);
    }

    /** Upstream getDeviceNames: every mixer with a capture line; the first one is the default. */
    public static List<String> deviceNames() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(CAPTURE_LINE)) names.add(info.getName());
        }
        return names;
    }

    /** Opens the named mixer; like upstream, a name that is not a Java Sound mixer means the default one. */
    static JavaxInput open(String name, int sampleRate, int channels) throws LineUnavailableException {
        List<String> names = deviceNames();
        if (names.isEmpty()) throw new LineUnavailableException("No Java Sound capture device");
        String device = names.contains(name) ? name : names.get(0);
        AudioFormat format = format(sampleRate, channels);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (!info.getName().equals(device)) continue;
            Mixer mixer = AudioSystem.getMixer(info);
            if (!mixer.isLineSupported(lineInfo)) continue;
            TargetDataLine line;
            try {
                line = (TargetDataLine) mixer.getLine(lineInfo);
            } catch (Exception ignored) {
                continue;
            }
            line.open(format);
            line.start();
            return new JavaxInput(line, device);
        }
        throw new LineUnavailableException(device + " cannot capture " + format);
    }

    /** Upstream printSupportedLines, logged when no microphone could be opened. */
    static void logSupportedLines(Logger logger) {
        if (!logger.isDebugEnabled()) return;
        logger.debug("Supported target data lines:");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (!mixer.isLineSupported(CAPTURE_LINE)) continue;
            for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                logger.debug("{}: {}", info.getName(), lineInfo);
                if (!(lineInfo instanceof DataLine.Info)) continue;
                for (AudioFormat format : ((DataLine.Info) lineInfo).getFormats()) logger.debug("  {}", format);
            }
        }
    }

    String getName() {
        return name;
    }

    boolean isOpen() {
        return line.isOpen();
    }

    /** One frame of {@code samples} interleaved samples once it is buffered, else null. */
    short[] read(int samples) {
        byte[] bytes = new byte[samples * 2];
        if (line.available() < bytes.length) return null;
        if (line.read(bytes, 0, bytes.length) < bytes.length) return null;
        return toShorts(bytes);
    }

    @Override
    public void close() {
        line.stop();
        line.flush();
        line.close();
    }

    /** Upstream AudioUtil.bytesToShorts: little-endian pairs. */
    static short[] toShorts(byte[] bytes) {
        short[] shorts = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }
}
