package su.plo.voice.platform.forge.client.lang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.*;

/** The five bundled settings screen languages must stay complete and format-compatible with English. */
public class BundledLangTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]");

    private static byte[] bytes(String language) {
        byte[] data = VoiceTranslations.CLASSPATH.apply(language);
        assertNotNull("bundled " + language + ".lang is missing", data);
        return data;
    }

    private static LangFile.Result parse(String language) {
        return LangFile.parse(bytes(language));
    }

    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) found.add(matcher.group());
        Collections.sort(found);
        return found;
    }

    private static int lineBreaks(String text) {
        return text.split("\\\\n", -1).length - 1;
    }

    @Test
    public void allFilesAreStrictUtf8WithoutBomAndParseCleanly() {
        for (String language : VoiceTranslations.BUNDLED) {
            byte[] data = bytes(language);
            assertTrue(language + " is not valid UTF-8", LangFile.isValidUtf8(data));
            String text = new String(data, StandardCharsets.UTF_8);
            assertFalse(language + " starts with a BOM", text.startsWith("﻿"));
            assertFalse(language + " has CR line endings", text.contains("\r"));
            LangFile.Result result = parse(language);
            assertEquals(language + " malformed lines", Collections.emptyList(), result.malformedLines);
            assertEquals(language + " duplicate keys", Collections.emptyList(), result.duplicateLines);
        }
    }

    @Test
    public void allLanguagesHaveExactlyTheEnglishKeys() {
        Map<String, String> en = parse("en_us").entries;
        assertTrue(en.size() > 90);
        for (String language : VoiceTranslations.BUNDLED) {
            assertEquals(language + " key set", en.keySet(), parse(language).entries.keySet());
        }
    }

    @Test
    public void noEmptyValues() {
        for (String language : VoiceTranslations.BUNDLED) {
            parse(language).entries.forEach((key, value) ->
                    assertFalse(language + " " + key + " is empty", value.trim().isEmpty()));
        }
    }

    @Test
    public void placeholdersAndLineBreaksMatchEnglish() {
        Map<String, String> en = parse("en_us").entries;
        for (String language : VoiceTranslations.BUNDLED) {
            Map<String, String> translated = parse(language).entries;
            en.forEach((key, english) -> {
                String text = translated.get(key);
                assertEquals(language + " " + key + " placeholders", placeholders(english), placeholders(text));
                assertEquals(language + " " + key + " line breaks", lineBreaks(english), lineBreaks(text));
            });
        }
    }

    @Test
    public void everyTextFormatsWithItsArguments() {
        for (String language : VoiceTranslations.BUNDLED) {
            VoiceTranslations translations = VoiceTranslations.load(null, language, VoiceTranslations.CLASSPATH,
                    org.apache.logging.log4j.LogManager.getLogger("BundledLangTest"));
            parse(language).entries.forEach((key, text) -> {
                int args = placeholders(text).stream().mapToInt(p -> p.equals("%%") ? 0 : 1).sum();
                Object[] values = new Object[args];
                for (int i = 0; i < args; i++) values[i] = "ARG" + i;
                String formatted = String.format(text, values);
                for (Object value : values) {
                    assertTrue(language + " " + key + " lost " + value, formatted.contains((String) value));
                }
                assertEquals(formatted, translations.format(key, values));
            });
        }
    }

    @Test
    public void migratedKeysAreGoneFromResourceAssets() throws Exception {
        byte[] data = BundledLangTest.class.getResourceAsStream("/assets/plasmovoice/lang/en_US.lang").readAllBytes();
        Map<String, String> assets = LangFile.parse(data).entries;
        for (String key : parse("en_us").entries.keySet()) {
            assertFalse(key + " is still in assets/plasmovoice/lang/en_US.lang", assets.containsKey(key));
        }
        assertTrue(assets.containsKey("key.plasmovoice.settings"));
        assertTrue(assets.containsKey("message.plasmovoice.on"));
        assertTrue(assets.containsKey("pv.activation.proximity"));
    }
}
