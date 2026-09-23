package su.plo.voice.platform.forge.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.Logger;

/**
 * Upstream VoiceServerLanguages without Crowdin: the bundled en_us.toml is copied to
 * config/plasmovoice/languages, every *.toml there is loaded, and missing keys fall back to the default
 * language and en_us. "server.*" keys translate server messages, "client.*" keys go to clients in LanguagePacket.
 */
public final class ServerLanguages {
    private static final String FALLBACK_LANGUAGE = "en_us";
    private static final String BUNDLED = "/plasmovoice/languages/" + FALLBACK_LANGUAGE + ".toml";

    private final Map<String, Map<String, String>> server = new HashMap<>();
    private final Map<String, Map<String, String>> client = new HashMap<>();
    private final String defaultLanguage;
    private final String forcedLanguage;

    private ServerLanguages(String defaultLanguage, String forcedLanguage) {
        this.defaultLanguage = defaultLanguage;
        this.forcedLanguage = forcedLanguage;
    }

    public static ServerLanguages load(File folder, String defaultLanguage, String forcedLanguage, Logger logger) {
        ServerLanguages languages = new ServerLanguages(defaultLanguage, forcedLanguage);
        Map<String, String> bundled;
        try (InputStream in = ServerLanguages.class.getResourceAsStream(BUNDLED)) {
            if (in == null) throw new IOException(BUNDLED + " is missing");
            byte[] data = in.readAllBytes();
            bundled = parse(new String(data, StandardCharsets.UTF_8));
            File file = new File(folder, FALLBACK_LANGUAGE + ".toml");
            if (!file.exists()) {
                folder.mkdirs();
                Files.write(file.toPath(), data);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load the bundled language", e);
        }

        Map<String, Map<String, String>> loaded = new HashMap<>();
        loaded.put(FALLBACK_LANGUAGE, bundled);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".toml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 5).toLowerCase(Locale.ROOT);
                try {
                    Map<String, String> language = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    if (name.equals(FALLBACK_LANGUAGE)) bundled.forEach(language::putIfAbsent);
                    loaded.put(name, language);
                } catch (IOException | IllegalArgumentException e) {
                    logger.warn("Failed to load language {}: {}", file.getName(), e.getMessage());
                }
            }
        }

        Map<String, String> defaults = loaded.getOrDefault(defaultLanguage, Collections.emptyMap());
        Map<String, String> fallback = loaded.get(FALLBACK_LANGUAGE);
        loaded.forEach((name, keys) -> {
            Map<String, String> merged = new HashMap<>(keys);
            defaults.forEach(merged::putIfAbsent);
            fallback.forEach(merged::putIfAbsent);
            languages.server.put(name, scope(merged, "server."));
            languages.client.put(name, scope(merged, "client."));
        });
        return languages;
    }

    /** Client scope for LanguagePacket. */
    public Map<String, String> client(String language) {
        return resolve(client, language);
    }

    /**
     * A server message in the language of its receiver, with upstream's "&" colour codes;
     * a missing key is returned as is, like an untranslated component.
     */
    public String format(String language, String key, Object... args) {
        String template = resolve(server, language).get(key);
        if (template == null) return key;
        String text;
        try {
            text = String.format(template, args);
        } catch (IllegalFormatException e) {
            text = template;
        }
        return text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    /** Upstream getLanguage: forced language, the requested one, then the default language. */
    private Map<String, String> resolve(Map<String, Map<String, String>> scope, String language) {
        if (forcedLanguage != null) return scope.getOrDefault(forcedLanguage, Collections.emptyMap());
        Map<String, String> requested = language == null ? null : scope.get(language.toLowerCase(Locale.ROOT));
        if (requested != null) return requested;
        return scope.getOrDefault(defaultLanguage, Collections.emptyMap());
    }

    private static Map<String, String> scope(Map<String, String> keys, String prefix) {
        Map<String, String> scoped = new HashMap<>();
        keys.forEach((key, value) -> {
            if (key.startsWith(prefix)) scoped.put(key.substring(prefix.length()), value);
        });
        return Collections.unmodifiableMap(scoped);
    }

    /** The TOML subset of the upstream language files: tables, bare keys and basic or multiline strings. */
    static Map<String, String> parse(String text) {
        Map<String, String> keys = new HashMap<>();
        String table = "";
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) throw new IllegalArgumentException("Bad table on line " + (i + 1));
                table = line.substring(1, line.length() - 1).trim() + ".";
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Bad entry on line " + (i + 1));
            String key = table + line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (value.startsWith("\"\"\"")) {
                StringBuilder multiline = new StringBuilder(value.substring(3));
                while (multiline.indexOf("\"\"\"") < 0) {
                    if (++i >= lines.length) throw new IllegalArgumentException("Unclosed string for " + key);
                    multiline.append('\n').append(lines[i]);
                }
                String raw = multiline.substring(0, multiline.indexOf("\"\"\""));
                // TOML trims the newline right after the opening quotes.
                keys.put(key, unescape(raw.startsWith("\n") ? raw.substring(1) : raw));
            } else if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                keys.put(key, unescape(value.substring(1, value.length() - 1)));
            } else if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                keys.put(key, value.substring(1, value.length() - 1));
            } else {
                throw new IllegalArgumentException("Unsupported value for " + key);
            }
        }
        return keys;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n':
                    out.append('\n');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default:
                    out.append('\\').append(next);
            }
        }
        return out.toString();
    }
}
