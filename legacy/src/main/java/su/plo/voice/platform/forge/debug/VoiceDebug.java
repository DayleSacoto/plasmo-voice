package su.plo.voice.platform.forge.debug;

import java.util.Objects;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

/**
 * Config-enabled Plasmo Voice diagnostics (client.cfg / server.cfg {@code debug.enabled}), one switch per side: an
 * integrated server logs through {@link #SERVER} and its client through {@link #CLIENT}, each with its own setting.
 * <p>
 * Lines are written at INFO/WARN so they reach fml-client-latest.log and fml-server-latest.log without a Log4j
 * config. Callers on hot paths check {@link #enabled()} before building arguments. Never pass key material, secrets
 * or audio payloads.
 */
public final class VoiceDebug {
    public static final VoiceDebug CLIENT = new VoiceDebug(Sink.log4j());
    public static final VoiceDebug SERVER = new VoiceDebug(Sink.log4j());

    public enum Category {
        STATE, TCP, UDP, KEEPALIVE, AUDIO, CODEC, DEVICE, SOURCE, THREAD;

        final String prefix = "[PV DEBUG/" + name() + "] ";
    }

    /** Where diagnostic lines go; tests capture them, production writes to the "Plasmo Voice" logger. */
    public interface Sink {
        void log(Level level, String message, Object[] args, Throwable error);

        static Sink log4j() {
            org.apache.logging.log4j.Logger logger = LogManager.getLogger("Plasmo Voice");
            return (level, message, args, error) -> {
                if (error == null) logger.log(level, message, args);
                else logger.log(level, logger.getMessageFactory().newMessage(message, args), error);
            };
        }
    }

    private final Sink sink;
    private volatile boolean enabled;

    VoiceDebug(Sink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        boolean changed = this.enabled != enabled;
        this.enabled = enabled;
        if (changed && enabled) sink.log(Level.INFO, Category.STATE.prefix + "debug logging enabled", new Object[0], null);
    }

    public void log(Category category, String message, Object... args) {
        if (enabled) sink.log(Level.INFO, category.prefix + message, args, null);
    }

    public void warn(Category category, String message, Object... args) {
        if (enabled) sink.log(Level.WARN, category.prefix + message, args, null);
    }

    /** A failure with its stack trace; the message arguments must not contain the throwable. */
    public void error(Category category, String message, Throwable error, Object... args) {
        if (enabled) sink.log(Level.WARN, category.prefix + message, args, error);
    }

    /** Age of a timestamp for snapshots; -1 when it never happened. */
    public static long age(long now, long timestamp) {
        return timestamp <= 0L ? -1L : now - timestamp;
    }

    /** Thread name for lifecycle lines. */
    public static String thread() {
        return Thread.currentThread().getName();
    }
}
