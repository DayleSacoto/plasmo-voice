package su.plo.voice.platform.forge.client.lang;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser for the settings screen .lang files, close to vanilla Locale.loadLocaleData: key=value split on the
 * first '=', '#' comments, %d/%f turned into %s. It also trims whitespace, skips blank lines, strips a BOM and
 * never throws: bad lines are skipped and reported by line number.
 */
public final class LangFile {
    /** Same rule as vanilla 1.7.10 Locale: numeric conversions become %s, so any argument type works. */
    private static final Pattern NUMERIC_FORMAT = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");

    /** Entries in file order (last duplicate wins), plus the 1-based numbers of skipped and duplicate lines. */
    public static final class Result {
        public final Map<String, String> entries;
        public final List<Integer> malformedLines;
        public final List<Integer> duplicateLines;

        private Result(Map<String, String> entries, List<Integer> malformedLines, List<Integer> duplicateLines) {
            this.entries = Collections.unmodifiableMap(entries);
            this.malformedLines = Collections.unmodifiableList(malformedLines);
            this.duplicateLines = Collections.unmodifiableList(duplicateLines);
        }

        public boolean hasWarnings() {
            return !malformedLines.isEmpty() || !duplicateLines.isEmpty();
        }
    }

    private LangFile() {
    }

    /** Decodes UTF-8 leniently: invalid bytes become U+FFFD instead of failing the whole file. */
    public static Result parse(byte[] data) {
        return parse(new String(data, StandardCharsets.UTF_8));
    }

    /** True when the bytes are valid UTF-8; used by tests to check the bundled files. */
    public static boolean isValidUtf8(byte[] data) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    public static Result parse(String text) {
        Map<String, String> entries = new LinkedHashMap<>();
        List<Integer> malformed = new ArrayList<>();
        List<Integer> duplicates = new ArrayList<>();
        if (text == null) return new Result(entries, malformed, duplicates);
        if (text.startsWith("﻿")) text = text.substring(1);

        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            String key = separator < 0 ? "" : line.substring(0, separator).trim();
            if (key.isEmpty()) {
                malformed.add(i + 1);
                continue;
            }
            String value = NUMERIC_FORMAT.matcher(line.substring(separator + 1).trim()).replaceAll("%$1s");
            if (entries.put(key, value) != null) duplicates.add(i + 1);
        }
        return new Result(entries, malformed, duplicates);
    }
}
