package su.plo.voice.platform.forge.server.connection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;

/** Handshake subset of upstream su.plo.voice.util.version.SemanticVersion, kept identical in behavior. */
@RequiredArgsConstructor
final class VoiceVersion {
    private static final Pattern VERSION_PATTERN = Pattern.compile(".*((-)?(\\d+)\\.(\\d+)\\.(\\d+).*)");

    final int major;
    final int minor;
    final int patch;

    static VoiceVersion parse(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.matches()) throw new IllegalArgumentException("Bad version. Valid format: X.X.X");
        try {
            return new VoiceVersion(
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad version. Valid format: X.X.X", e);
        }
    }

    int asInt() {
        return major * 100 + minor * 10 + patch;
    }
}
