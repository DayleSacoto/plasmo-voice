package su.plo.voice.platform.forge.client.lang;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.*;

public class LangFileTest {
    @Test
    public void parsesKeyValueLines() {
        LangFile.Result result = LangFile.parse("a=1\nb=two words\n");
        assertEquals("1", result.entries.get("a"));
        assertEquals("two words", result.entries.get("b"));
        assertFalse(result.hasWarnings());
    }

    @Test
    public void skipsCommentsAndBlankLines() {
        LangFile.Result result = LangFile.parse("# header\n\n   \n  # indented comment\na=1\n");
        assertEquals(Collections.singletonMap("a", "1"), result.entries);
        assertFalse(result.hasWarnings());
    }

    @Test
    public void hashInsideValueIsText() {
        assertEquals("#1 # two", LangFile.parse("a=#1 # two").entries.get("a"));
    }

    @Test
    public void splitsOnFirstEquals() {
        assertEquals("b=c=d", LangFile.parse("a=b=c=d").entries.get("a"));
    }

    @Test
    public void trimsKeyAndValue() {
        assertEquals("value", LangFile.parse("  key  =  value  ").entries.get("key"));
    }

    @Test
    public void emptyValueIsAllowed() {
        LangFile.Result result = LangFile.parse("a=");
        assertEquals("", result.entries.get("a"));
        assertFalse(result.hasWarnings());
    }

    @Test
    public void duplicateKeyLastValueWins() {
        LangFile.Result result = LangFile.parse("a=1\nb=x\na=2\n");
        assertEquals("2", result.entries.get("a"));
        assertEquals(Collections.singletonList(3), result.duplicateLines);
    }

    @Test
    public void malformedLinesAreSkippedWithLineNumbers() {
        LangFile.Result result = LangFile.parse("no separator\n=no key\na=1\n   =\n");
        assertEquals(Collections.singletonMap("a", "1"), result.entries);
        assertEquals(Arrays.asList(1, 2, 4), result.malformedLines);
    }

    @Test
    public void garbageDoesNotThrow() {
        byte[] garbage = new byte[256];
        for (int i = 0; i < garbage.length; i++) garbage[i] = (byte) i;
        LangFile.parse(garbage);
        LangFile.parse((String) null);
        LangFile.parse("");
        assertTrue(LangFile.parse(new byte[0]).entries.isEmpty());
    }

    @Test
    public void invalidUtf8BecomesReplacementCharacter() {
        byte[] data = {'a', '=', (byte) 0xC3, (byte) 0x28};
        assertEquals("�(", LangFile.parse(data).entries.get("a"));
        assertFalse(LangFile.isValidUtf8(data));
    }

    @Test
    public void handlesCrLfCrAndBom() {
        LangFile.Result result = LangFile.parse("﻿a=1\r\nb=2\rc=3");
        assertEquals("1", result.entries.get("a"));
        assertEquals("2", result.entries.get("b"));
        assertEquals("3", result.entries.get("c"));
    }

    @Test
    public void keepsBackslashNLiteral() {
        assertEquals("line\\nnext", LangFile.parse("a=line\\nnext").entries.get("a"));
    }

    @Test
    public void decodesCyrillicUtf8() {
        byte[] data = "a=Шумоподавление\nb=Гучність\n".getBytes(StandardCharsets.UTF_8);
        LangFile.Result result = LangFile.parse(data);
        assertEquals("Шумоподавление", result.entries.get("a"));
        assertEquals("Гучність", result.entries.get("b"));
        assertTrue(LangFile.isValidUtf8(data));
    }

    @Test
    public void decodesGermanAndFrenchUtf8() {
        byte[] data = "a=Lautstärke Größe\nb=Périphérique d’entrée « ç » œ\n".getBytes(StandardCharsets.UTF_8);
        LangFile.Result result = LangFile.parse(data);
        assertEquals("Lautstärke Größe", result.entries.get("a"));
        assertEquals("Périphérique d’entrée « ç » œ", result.entries.get("b"));
    }

    @Test
    public void numericConversionsBecomeStringLikeVanilla() {
        LangFile.Result result = LangFile.parse("a=%d blocks\nb=%1$d and %2$.1f\nc=%s 100%%");
        assertEquals("%s blocks", result.entries.get("a"));
        assertEquals("%1$s and %2$s", result.entries.get("b"));
        assertEquals("%s 100%%", result.entries.get("c"));
    }
}
