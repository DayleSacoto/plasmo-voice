package su.plo.voice.platform.forge.client.lang;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class VoiceTranslationsTest {
    private static final Logger LOGGER = LogManager.getLogger("VoiceTranslationsTest");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Map<String, String> bundledTexts = new HashMap<>();
    private final Function<String, byte[]> bundled = language -> {
        String text = bundledTexts.get(language);
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    };

    {
        bundledTexts.put("en_us", "title=Settings\nonly_en=English only\nvolume=Volume %s\npercent=100%%\nbad=%q\n");
        bundledTexts.put("ru_ru", "title=Настройки\nvolume=Громкость %s\n");
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private VoiceTranslations load(File folder, String code) {
        return VoiceTranslations.load(folder, code, bundled, LOGGER);
    }

    @Test
    public void normalizesMinecraftLanguageCodes() {
        assertEquals("ru_ru", VoiceTranslations.normalize("ru_RU"));
        assertEquals("uk_ua", VoiceTranslations.normalize("uk_UA"));
        assertEquals("de_de", VoiceTranslations.normalize("de_DE"));
        assertEquals("fr_fr", VoiceTranslations.normalize("fr_FR"));
        assertEquals("en_us", VoiceTranslations.normalize("en_US"));
        assertEquals("pt_br", VoiceTranslations.normalize(" pt-BR "));
        assertEquals("en_us", VoiceTranslations.normalize(null));
        assertEquals("en_us", VoiceTranslations.normalize("  "));
    }

    @Test
    public void usesSelectedLanguage() {
        VoiceTranslations ru = load(null, "ru_RU");
        assertEquals("ru_ru", ru.language());
        assertEquals("Настройки", ru.get("title"));
        assertEquals("Громкость 50", ru.format("volume", 50));
    }

    @Test
    public void missingKeyFallsBackToEnglish() {
        assertEquals("English only", load(null, "ru_RU").get("only_en"));
    }

    @Test
    public void unknownLanguageFallsBackToEnglish() {
        VoiceTranslations de = load(null, "xx_XX");
        assertEquals("Settings", de.get("title"));
    }

    @Test
    public void regionalVariantFallsBackToSameLanguage() {
        VoiceTranslations ruUa = load(null, "ru_UA");
        assertEquals("ru_ua", ruUa.language());
        assertEquals("Настройки", ruUa.get("title"));
        assertEquals("English only", ruUa.get("only_en"));
    }

    @Test
    public void regionalFileBeatsSameLanguageFallback() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "ru_ua.lang"), "title=Региональные настройки\n");
        VoiceTranslations ruUa = load(folder, "ru_UA");
        assertEquals("Региональные настройки", ruUa.get("title"));
        assertEquals("Громкость 3", ruUa.format("volume", 3));
    }

    @Test
    public void regionalFallbackUsesUserFileOfSameLanguage() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "pt_br.lang"), "title=Configurações\n");
        write(new File(folder, "pt_pt.lang"), "only_en=Só português europeu\n");
        VoiceTranslations ptAo = load(folder, "pt_AO");
        assertEquals("Configurações", ptAo.get("title"));
        assertEquals("English only", ptAo.get("only_en"));
    }

    @Test
    public void regionalFallbackPrefersBundledLanguage() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "ru_by.lang"), "title=Другой регион\n");
        assertEquals("Настройки", load(folder, "ru_KZ").get("title"));
    }

    @Test
    public void baseLanguageOf() {
        assertEquals("fr", VoiceTranslations.baseLanguage("fr_ca"));
        assertEquals("de", VoiceTranslations.baseLanguage("de_at"));
        assertEquals("qya", VoiceTranslations.baseLanguage("qya_aa"));
        assertEquals("lol", VoiceTranslations.baseLanguage("lol"));
    }

    @Test
    public void missingEverywhereReturnsKeyNotNull() {
        VoiceTranslations ru = load(null, "ru_RU");
        assertEquals("no.such.key", ru.get("no.such.key"));
        assertEquals("no.such.key", ru.format("no.such.key", 1, 2));
        assertFalse(ru.has("no.such.key"));
        assertEquals("null", ru.get(null));
    }

    @Test
    public void userFileOverridesBundled() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "ru_ru.lang"), "title=Мои настройки\n");
        VoiceTranslations ru = load(folder, "ru_RU");
        assertEquals("Мои настройки", ru.get("title"));
        assertEquals("Громкость 1", ru.format("volume", 1));
    }

    @Test
    public void bundledSelectedLanguageBeatsUserEnglish() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "en_us.lang"), "title=My Settings\nonly_en=Mine\n");
        VoiceTranslations ru = load(folder, "ru_RU");
        assertEquals("Настройки", ru.get("title"));
        assertEquals("Mine", ru.get("only_en"));
    }

    @Test
    public void userFileForLanguageWithoutBundledFileWorks() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "pl_PL.lang"), "title=Ustawienia\n");
        VoiceTranslations pl = load(folder, "pl_PL");
        assertEquals("Ustawienia", pl.get("title"));
        assertEquals("English only", pl.get("only_en"));
    }

    @Test
    public void formatKeepsVanillaPercentAndPlaceholderBehavior() {
        VoiceTranslations en = load(null, "en_US");
        assertEquals("100%", en.format("percent"));
        assertEquals("Volume 7", en.format("volume", 7));
        assertEquals("%q", en.format("bad"));
        assertEquals("Volume %s", en.get("volume"));
    }

    @Test
    public void unreadableOrMalformedUserFileDoesNotBreakLookup() throws Exception {
        File folder = temp.newFolder("lang");
        assertTrue(new File(folder, "ru_ru.lang").mkdir());
        write(new File(folder, "en_us.lang"), "garbage line\n=\nonly_en=Fixed\n");
        VoiceTranslations ru = load(folder, "ru_RU");
        assertEquals("Настройки", ru.get("title"));
        assertEquals("Fixed", ru.get("only_en"));
    }

    @Test
    public void provisionCreatesFolderAndMissingFiles() throws Exception {
        File folder = new File(temp.getRoot(), "config/plasmovoice/lang");
        VoiceTranslations.provision(folder, bundled, LOGGER);
        assertEquals(bundledTexts.get("en_us"), read(new File(folder, "en_us.lang")));
        assertEquals(bundledTexts.get("ru_ru"), read(new File(folder, "ru_ru.lang")));
        assertFalse("no bundled file, nothing written", new File(folder, "de_de.lang").exists());
    }

    @Test
    public void provisionNeverOverwritesExistingFile() throws Exception {
        File folder = temp.newFolder("lang");
        File ru = new File(folder, "ru_ru.lang");
        write(ru, "# мой перевод\ntitle=Мои настройки\n");
        assertTrue(ru.setLastModified(1_000_000_000_000L));
        VoiceTranslations.provision(folder, bundled, LOGGER);
        VoiceTranslations.provision(folder, bundled, LOGGER);
        assertEquals("# мой перевод\ntitle=Мои настройки\n", read(ru));
        assertEquals(1_000_000_000_000L, ru.lastModified());
        assertTrue(new File(folder, "en_us.lang").isFile());
    }

    @Test
    public void provisionIgnoresCaseOfExistingNames() throws Exception {
        File folder = temp.newFolder("lang");
        write(new File(folder, "ru_RU.lang"), "title=Свой\n");
        VoiceTranslations.provision(folder, bundled, LOGGER);
        String[] names = folder.list((dir, name) -> name.equalsIgnoreCase("ru_ru.lang"));
        assertEquals(1, names.length);
        assertEquals("Свой", load(folder, "ru_RU").get("title"));
    }

    @Test
    public void provisionRecreatesDeletedFile() throws Exception {
        File folder = temp.newFolder("lang");
        VoiceTranslations.provision(folder, bundled, LOGGER);
        File en = new File(folder, "en_us.lang");
        assertTrue(en.delete());
        VoiceTranslations.provision(folder, bundled, LOGGER);
        assertEquals(bundledTexts.get("en_us"), read(en));
    }

    @Test
    public void provisionWhenFolderIsAFileDoesNotThrow() throws Exception {
        File notAFolder = temp.newFile("lang");
        VoiceTranslations.provision(notAFolder, bundled, LOGGER);
        VoiceTranslations.provision(null, bundled, LOGGER);
        assertEquals("Settings", load(notAFolder, "en_US").get("title"));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
