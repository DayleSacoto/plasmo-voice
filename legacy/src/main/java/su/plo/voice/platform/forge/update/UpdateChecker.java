package su.plo.voice.platform.forge.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import su.plo.voice.platform.forge.PlasmoVoiceMod;

/**
 * Legacy extension: tells about a newer release of Plasmo Voice Legacy Forge. Reads the public GitHub release list
 * of its repository once per game or dedicated server process and never downloads anything. Only releases tagged
 * {@code legacy-X.Y.Z} are compared; releases of upstream Plasmo Voice are never an update for this mod.
 * Common code without client classes, so it is safe on a dedicated server.
 */
public final class UpdateChecker {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    static final String RELEASES_API = "https://api.github.com/repos/DayleSacoto/plasmo-voice/releases?per_page=100";
    static final String RELEASES_PAGE = "https://github.com/DayleSacoto/plasmo-voice/releases/";
    static final int TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 4 << 20;

    public static final UpdateChecker INSTANCE = new UpdateChecker(UpdateChecker::fetch, Version.current(), task -> {
        Thread thread = new Thread(task, "plasmo-voice-update-check");
        thread.setDaemon(true);
        thread.start();
    });

    private final Fetcher fetcher;
    private final Version current;
    private final Executor executor;
    private final AtomicBoolean started = new AtomicBoolean();

    UpdateChecker(Fetcher fetcher, Version current, Executor executor) {
        this.fetcher = fetcher;
        this.current = current;
        this.executor = executor;
    }

    /** The release version of this jar. */
    public Version getCurrent() {
        return current;
    }

    /**
     * Starts the one check of this process in the background, unless it is disabled or already started.
     * {@code onNewer} runs on the checker thread when a newer backport release exists. Every failure is only logged.
     */
    public void start(boolean enabled, Consumer<Release> onNewer) {
        if (!enabled || current == null || !started.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                Release newest = newest(fetcher.fetch(RELEASES_API));
                if (newest != null && newest.version.compareTo(current) > 0) {
                    onNewer.accept(newest);
                } else {
                    LOGGER.debug("No newer Plasmo Voice Legacy Forge release than {}", current.label());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Plasmo Voice update check failed: {}", e.toString());
            }
        });
    }

    /**
     * The newest Legacy Forge release in a GitHub release list: drafts and tags that are not a Legacy Forge version
     * (see {@link Version#parse}) are skipped, published pre-releases count (releases are tested as pre-releases).
     * Null when there is none.
     */
    static Release newest(String json) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonArray()) throw new JsonParseException("not a release list");
        Release newest = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (bool(release, "draft")) continue;
            String tag = string(release, "tag_name");
            Version version = Version.parse(tag);
            if (version == null || newest != null && version.compareTo(newest.version) <= 0) continue;
            // Only a release page of this repository is offered as a link.
            String url = string(release, "html_url");
            newest = new Release(version, url != null && url.startsWith(RELEASES_PAGE) ? url : RELEASES_PAGE + "tag/" + tag);
        }
        return newest;
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** A plain GET without credentials or cookies; only the User-Agent GitHub requires is added. */
    static String fetch(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "plasmo-voice-forge-1.7.10-update-check");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    if (out.size() + read > MAX_RESPONSE_BYTES) throw new IOException("response too large");
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    interface Fetcher {
        String fetch(String address) throws IOException;
    }

    public static final class Release {
        final Version version;
        private final String url;

        Release(Version version, String url) {
            this.version = version;
            this.url = url;
        }

        public String label() {
            return version.label();
        }

        public String url() {
            return url;
        }
    }

    /**
     * A Legacy Forge release version, X.Y.Z, compared numerically. A release of this project is tagged
     * {@code legacy-X.Y.Z}. Every other tag of the repository is not a Legacy Forge release: tags that follow
     * upstream Plasmo Voice numbering, and the releases published before 0.4.0.
     */
    public static final class Version implements Comparable<Version> {
        static final String TAG_PREFIX = "legacy-";
        private static final Pattern VERSION = Pattern.compile("(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})");

        private final int major;
        private final int minor;
        private final int patch;

        private Version(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        /** The version of a release tag; null for every tag that is not {@code legacy-X.Y.Z}. */
        static Version parse(String tag) {
            return tag != null && tag.startsWith(TAG_PREFIX) ? of(tag.substring(TAG_PREFIX.length())) : null;
        }

        /** A plain X.Y.Z version; null when it is anything else. */
        static Version of(String version) {
            Matcher matcher = VERSION.matcher(version);
            if (!matcher.matches()) return null;
            return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        }

        /** The version of this build: legacyVersion from gradle.properties. */
        static Version current() {
            return of(PlasmoVoiceMod.VERSION);
        }

        public String label() {
            return major + "." + minor + "." + patch;
        }

        @Override
        public int compareTo(Version other) {
            int compared = Integer.compare(major, other.major);
            if (compared == 0) compared = Integer.compare(minor, other.minor);
            return compared != 0 ? compared : Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return label();
        }
    }
}
