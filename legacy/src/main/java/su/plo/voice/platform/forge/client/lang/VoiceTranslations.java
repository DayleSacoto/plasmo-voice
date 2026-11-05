package su.plo.voice.platform.forge.client.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.logging.log4j.Logger;

/**
 * One immutable snapshot of the settings screen texts for one language. Lookup order, first hit wins:
 * config/plasmovoice/lang/&lt;language&gt;.lang (user edits), the bundled &lt;language&gt;.lang, then the same
 * language in another region (user, then bundled; fr_ca uses fr_fr), the user's en_us.lang, the bundled
 * en_us.lang, and finally the key itself. Nothing here returns null or throws.
 */
public final class VoiceTranslations {
    public static final String FALLBACK = "en_us";
    /** Bundled files, in the order they are provisioned. */
    public static final List<String> BUNDLED = Collections.unmodifiableList(
            Arrays.asList("en_us", "ru_ru", "uk_ua", "de_de", "fr_fr", "zh_cn"));
    private static final String EXTENSION = ".lang";

    /** Default source of bundled files: classpath resources under plasmovoice/lang/client. */
    public static final Function<String, byte[]> CLASSPATH = language -> {
        try (InputStream in = VoiceTranslations.class.getResourceAsStream("/plasmovoice/lang/client/" + language + EXTENSION)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    };

    private final String language;
    private final Map<String, String> texts;

    private VoiceTranslations(String language, Map<String, String> texts) {
        this.language = language;
        this.texts = texts;
    }

    /** Minecraft codes like "ru_RU" (or "ru-RU") become file names like "ru_ru"; null or blank means English. */
    public static String normalize(String code) {
        if (code == null || code.trim().isEmpty()) return FALLBACK;
        return code.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    /**
     * Builds a snapshot. {@code folder} may be null or missing (bundled texts only); {@code bundled} returns
     * a bundled file's bytes or null.
     */
    public static VoiceTranslations load(File folder, String languageCode, Function<String, byte[]> bundled,
                                         Logger logger) {
        String language = normalize(languageCode);
        Map<String, File> userFiles = userFiles(folder);
        String sameLanguage = sameLanguageFallback(language, userFiles, bundled);
        Map<String, String> texts = new HashMap<>();
        // Lowest priority first, so later layers overwrite earlier ones.
        putBundled(texts, FALLBACK, bundled, logger);
        putUser(texts, userFiles.get(FALLBACK), logger);
        if (sameLanguage != null) {
            putBundled(texts, sameLanguage, bundled, logger);
            putUser(texts, userFiles.get(sameLanguage), logger);
        }
        if (!language.equals(FALLBACK)) {
            putBundled(texts, language, bundled, logger);
            putUser(texts, userFiles.get(language), logger);
        }
        return new VoiceTranslations(language, texts);
    }

    /**
     * Copies each bundled file into {@code folder} only when no file with that name exists (ignoring case).
     * Existing files are never touched. Errors are logged, never thrown.
     */
    public static void provision(File folder, Function<String, byte[]> bundled, Logger logger) {
        if (folder == null) return;
        if (!folder.isDirectory() && !folder.mkdirs()) {
            logger.warn("Can't create the language folder {}, using built-in texts", folder);
            return;
        }
        Set<String> existing = new HashSet<>(userFiles(folder).keySet());
        for (String language : BUNDLED) {
            if (existing.contains(language)) continue;
            byte[] data = bundled.apply(language);
            if (data == null) continue;
            try {
                Files.write(new File(folder, language + EXTENSION).toPath(), data, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {
                // Created by someone else in the meantime: it's theirs now.
            } catch (IOException e) {
                logger.warn("Can't create {}{} in {}: {}", language, EXTENSION, folder, e.toString());
            }
        }
    }

    public String language() {
        return language;
    }

    public boolean has(String key) {
        return texts.containsKey(key);
    }

    /** The raw text, or the key itself when no layer has it. */
    public String get(String key) {
        String text = texts.get(key);
        return text != null ? text : String.valueOf(key);
    }

    /** Like I18n.format: always runs String.format (so "%%" becomes "%"); a bad pattern returns the raw text. */
    public String format(String key, Object... args) {
        String text = get(key);
        try {
            return String.format(text, args);
        } catch (IllegalFormatException e) {
            return text;
        }
    }

    /** "fr_ca" → "fr": the part before the region. */
    public static String baseLanguage(String language) {
        int separator = language.indexOf('_');
        return separator < 0 ? language : language.substring(0, separator);
    }

    /**
     * Another region of the same language, used under the selected one (fr_ca → fr_fr, de_at → de_de):
     * a bundled language first, otherwise the alphabetically first user file. Null when there is none.
     */
    private static String sameLanguageFallback(String language, Map<String, File> userFiles,
                                               Function<String, byte[]> bundled) {
        String base = baseLanguage(language);
        for (String candidate : BUNDLED) {
            if (isSibling(candidate, language, base) && bundled.apply(candidate) != null) return candidate;
        }
        return userFiles.keySet().stream()
                .filter(candidate -> isSibling(candidate, language, base))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static boolean isSibling(String candidate, String language, String base) {
        return !candidate.equals(language) && !candidate.equals(FALLBACK) && baseLanguage(candidate).equals(base);
    }

    /** Lower-case language name to file, for *.lang files in the folder; an exact lower-case name wins. */
    private static Map<String, File> userFiles(File folder) {
        Map<String, File> files = new HashMap<>();
        File[] listed = folder == null ? null : folder.listFiles();
        if (listed == null) return files;
        for (File file : listed) {
            String name = file.getName();
            if (!file.isFile() || !name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) continue;
            String language = name.substring(0, name.length() - EXTENSION.length()).toLowerCase(Locale.ROOT);
            if (!files.containsKey(language) || name.equals(language + EXTENSION)) files.put(language, file);
        }
        return files;
    }

    private static void putBundled(Map<String, String> texts, String language, Function<String, byte[]> bundled,
                                   Logger logger) {
        byte[] data = bundled.apply(language);
        if (data != null) put(texts, "built-in " + language + EXTENSION, data, logger);
    }

    private static void putUser(Map<String, String> texts, File file, Logger logger) {
        if (file == null) return;
        try {
            put(texts, file.getPath(), Files.readAllBytes(file.toPath()), logger);
        } catch (IOException e) {
            logger.warn("Can't read {}: {}", file, e.toString());
        }
    }

    private static void put(Map<String, String> texts, String source, byte[] data, Logger logger) {
        LangFile.Result result = LangFile.parse(data);
        if (!result.malformedLines.isEmpty()) {
            logger.warn("{}: skipped {} line(s) without key=value, first at line {}", source,
                    result.malformedLines.size(), result.malformedLines.get(0));
        }
        if (!result.duplicateLines.isEmpty()) {
            logger.warn("{}: {} duplicate key(s), the last value wins, first at line {}", source,
                    result.duplicateLines.size(), result.duplicateLines.get(0));
        }
        texts.putAll(result.entries);
    }
}
