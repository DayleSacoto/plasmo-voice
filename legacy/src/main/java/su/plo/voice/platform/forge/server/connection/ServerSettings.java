package su.plo.voice.platform.forge.server.connection;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.apache.logging.log4j.Logger;

/** Persisted subset of upstream VoiceServerConfig, stored as a Forge config file. */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ServerSettings {
    private static final List<String> OPUS_MODES = Arrays.asList("VOIP", "AUDIO", "RESTRICTED_LOWDELAY");
    private static final List<Integer> SAMPLE_RATES = Arrays.asList(8_000, 12_000, 24_000, 48_000);

    private final UUID serverId;
    private final String hostIp;
    private final int hostPort;
    /** Null when the public address is not configured, like upstream's optional [host.public]. */
    private final String publicIp;
    private final int publicPort;
    private final int sampleRate;
    private final int mtuSize;
    private final int keepAliveTimeoutMs;
    private final String clientModMinVersion;
    private final List<Integer> proximityDistances;
    private final int proximityDefaultDistance;
    private final String opusMode;
    private final int opusBitrate;

    public static ServerSettings defaults() {
        return new ServerSettings(UUID.randomUUID(), "0.0.0.0", 0, null, 0, 48_000, 1024, 15_000,
                ServerConnection.DEFAULT_CLIENT_MOD_MIN_VERSION, Arrays.asList(8, 16, 32), 16, "VOIP", -1000);
    }

    public static ServerSettings load(File file, Logger logger) {
        Configuration config = new Configuration(file);
        ServerSettings defaults = defaults();

        Property serverIdProperty = config.get("general", "server_id", "",
                "Used to store server-related config file on the client\n"
                        + "Set it to a single value on different servers if you want them to share config");
        UUID serverId = parseUuid(serverIdProperty.getString());
        if (serverId == null) {
            serverId = defaults.serverId;
            serverIdProperty.set(serverId.toString());
        }
        UUID envServerId = parseUuid(System.getenv("PLASMO_VOICE_SERVER_ID"));
        if (envServerId != null) serverId = envServerId;

        String hostIp = config.get("host", "ip", defaults.hostIp,
                "IP address for the voice server to bind to\n0.0.0.0 = bind to all available interfaces").getString();
        if (hostIp.trim().isEmpty()) hostIp = invalid(logger, "host.ip", hostIp, defaults.hostIp);
        int hostPort = intValue(config.get("host", "port", defaults.hostPort,
                "UDP port for the voice server\n0 = same port as Minecraft server"), 0, 65535, logger, "host.port");

        String publicIp = config.get("host.public", "ip", "",
                "Public IP and port that clients will connect to\nEmpty ip = use the address from [host]").getString().trim();
        int publicPort = intValue(config.get("host.public", "port", 0,
                "If port set to 0, port from [host] will be used"), 0, 65535, logger, "host.public.port");

        int sampleRate = intValue(config.get("voice", "sample_rate", defaults.sampleRate,
                "Supported sample rates: [8000, 12000, 24000, 48000]\n"
                        + "Don't change this to reduce bandwidth; adjust opus bitrate instead"), 0, Integer.MAX_VALUE, logger, "voice.sample_rate");
        if (!SAMPLE_RATES.contains(sampleRate)) sampleRate = invalid(logger, "voice.sample_rate", sampleRate, defaults.sampleRate);
        int mtuSize = intValue(config.get("voice", "mtu_size", defaults.mtuSize,
                "Maximum packet size for voice data transmission"), 128, 5000, logger, "voice.mtu_size");
        int keepAliveTimeoutMs = intValue(config.get("voice", "keep_alive_timeout_ms", defaults.keepAliveTimeoutMs,
                "Time before voice client connection is considered timed out\n"
                        + "Server will try to automatically reconnect the client after timeout"), 1_000, 120_000, logger,
                "voice.keep_alive_timeout_ms");
        String clientModMinVersion = config.get("voice", "client_mod_min_version", defaults.clientModMinVersion,
                "Minimum required version for a client with the mod to connect to the voice server\n"
                        + "This will not kick the player but will simply not connect them to the voice server").getString();

        Property distancesProperty = config.get("voice.proximity", "distances",
                defaults.proximityDistances.stream().mapToInt(Integer::intValue).toArray(), "");
        List<Integer> distances = new ArrayList<>();
        for (int distance : distancesProperty.getIntList()) distances.add(distance);
        if (!distancesProperty.isIntList()) {
            distances = invalid(logger, "voice.proximity.distances", Arrays.toString(distancesProperty.getStringList()),
                    new ArrayList<>(defaults.proximityDistances));
        }
        Collections.sort(distances);
        int defaultDistance = intValue(config.get("voice.proximity", "default_distance", defaults.proximityDefaultDistance, ""),
                Integer.MIN_VALUE, Integer.MAX_VALUE, logger, "voice.proximity.default_distance");

        String opusMode = config.get("voice.opus", "mode", defaults.opusMode,
                "Opus application mode\nSupported values: VOIP, AUDIO, RESTRICTED_LOWDELAY\nDefault is VOIP").getString();
        if (!OPUS_MODES.contains(opusMode)) opusMode = invalid(logger, "voice.opus.mode", opusMode, defaults.opusMode);
        int opusBitrate = intValue(config.get("voice.opus", "bitrate", defaults.opusBitrate,
                "Opus bitrate\nSupported values: -1000 (auto), -1 (max), [500-512_000]\nDefault is -1000"),
                Integer.MIN_VALUE, Integer.MAX_VALUE, logger, "voice.opus.bitrate");
        if (opusBitrate != -1 && opusBitrate != -1000 && (opusBitrate < 500 || opusBitrate > 512_000)) {
            opusBitrate = invalid(logger, "voice.opus.bitrate", opusBitrate, defaults.opusBitrate);
        }

        if (config.hasChanged()) config.save();
        return new ServerSettings(serverId, hostIp, hostPort, publicIp.isEmpty() ? null : publicIp, publicPort,
                sampleRate, mtuSize, keepAliveTimeoutMs, clientModMinVersion, Collections.unmodifiableList(distances),
                defaultDistance, opusMode, opusBitrate);
    }

    /** Upstream: 0 means the Minecraft server port, and a random port when that is unknown (singleplayer). */
    public int bindPort(int minecraftPort) {
        if (hostPort != 0) return hostPort;
        return Math.max(minecraftPort, 0);
    }

    /** Advertised ConnectionPacket address; port 0 is resolved to the bound UDP port by {@link UdpServer}. */
    public String advertisedIp() {
        return publicIp != null ? publicIp : hostIp;
    }

    public int advertisedPort() {
        return publicIp != null ? publicPort : 0;
    }

    private static int intValue(Property property, int min, int max, Logger logger, String path) {
        int fallback = Integer.parseInt(property.getDefault());
        if (!property.isIntValue()) return invalid(logger, path, property.getString(), fallback);
        int value = property.getInt();
        return value < min || value > max ? invalid(logger, path, value, fallback) : value;
    }

    private static <T> T invalid(Logger logger, String path, Object value, T fallback) {
        logger.warn("Invalid voice server config value {} = {}; using {}", path, value, fallback);
        return fallback;
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
