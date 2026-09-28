package su.plo.voice.platform.forge.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
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

/**
 * Legacy extension: tells about a newer release of this Forge 1.7.10 backport. Reads the public GitHub release list
 * of the backport repository once per game or dedicated server process and never downloads anything.
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

    /** The release of this jar; null when the build did not fill in plasmovoice/backport.properties. */
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
                Release newest = newest(fetcher.fetch(RELEASES_API), current.minecraftVersion);
                if (newest != null && newest.version.compareTo(current) > 0) {
                    onNewer.accept(newest);
                } else {
                    LOGGER.debug("No newer Plasmo Voice backport release than {}", current.label());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Plasmo Voice update check failed: {}", e.toString());
            }
        });
    }

    /**
     * The newest backport release in a GitHub release list: drafts and tags of other formats are skipped, published
     * pre-releases count (backport releases are tested as pre-releases). Null when there is none.
     */
    static Release newest(String json, String minecraftVersion) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonArray()) throw new JsonParseException("not a release list");
        Release newest = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (bool(release, "draft")) continue;
            Version version = Version.parse(string(release, "tag_name"), minecraftVersion);
            if (version == null || newest != null && version.compareTo(newest.version) <= 0) continue;
            // Only a release page of this repository is offered as a link.
            String url = string(release, "html_url");
            newest = new Release(version, url != null && url.startsWith(RELEASES_PAGE) ? url : RELEASES_PAGE + "tag/" + version.tag);
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
     * A backport tag: {@code <upstream version>-forge-<minecraft version>[-r<revision>]}; the tag without a revision
     * is revision 0. Compared by upstream version, then revision, both numerically.
     */
    public static final class Version implements Comparable<Version> {
        final String tag;
        private final int[] base;
        final int revision;
        final String minecraftVersion;

        private Version(String tag, int[] base, int revision, String minecraftVersion) {
            this.tag = tag;
            this.base = base;
            this.revision = revision;
            this.minecraftVersion = minecraftVersion;
        }

        /** Null for any other tag, including backports for another Minecraft version. */
        static Version parse(String tag, String minecraftVersion) {
            if (tag == null) return null;
            Matcher matcher = Pattern.compile("(\\d{1,9}(?:\\.\\d{1,9}){0,3})-forge-" + Pattern.quote(minecraftVersion)
                    + "(?:-r(\\d{1,9}))?").matcher(tag);
            if (!matcher.matches()) return null;
            String[] parts = matcher.group(1).split("\\.");
            int[] base = new int[parts.length];
            for (int i = 0; i < parts.length; i++) base[i] = Integer.parseInt(parts[i]);
            return new Version(tag, base, matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)), minecraftVersion);
        }

        /** From plasmovoice/backport.properties, which the build fills in from the artifact version. */
        static Version current() {
            Properties properties = new Properties();
            try (InputStream in = UpdateChecker.class.getResourceAsStream("/plasmovoice/backport.properties")) {
                if (in == null) return null;
                properties.load(in);
            } catch (IOException e) {
                return null;
            }
            String minecraftVersion = properties.getProperty("minecraft_version", "");
            String revision = properties.getProperty("legacy_revision", "");
            return parse(properties.getProperty("upstream_version", "") + "-forge-" + minecraftVersion
                    + (revision.isEmpty() ? "" : "-" + revision), minecraftVersion);
        }

        /** Upstream version and revision, e.g. 2.1.17-r3, or 2.1.17 for the first backport release. */
        public String label() {
            StringBuilder label = new StringBuilder();
            for (int part : base) label.append(label.length() == 0 ? "" : ".").append(part);
            return revision == 0 ? label.toString() : label + "-r" + revision;
        }

        @Override
        public int compareTo(Version other) {
            for (int i = 0; i < Math.max(base.length, other.base.length); i++) {
                int compared = Integer.compare(i < base.length ? base[i] : 0, i < other.base.length ? other.base[i] : 0);
                if (compared != 0) return compared;
            }
            return Integer.compare(revision, other.revision);
        }

        @Override
        public String toString() {
            return tag;
        }
    }
}
