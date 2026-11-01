package su.plo.voice.platform.forge.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GroupsLanguagesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?s");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyBundledLanguageHasTheEnglishKeysAndPlaceholders() throws IOException {
        Map<String, String> english = bundled("en_us");
        for (String language : ServerLanguages.GROUPS_LANGUAGES) {
            Map<String, String> translated = bundled(language);
            assertEquals(language + " keys", english.keySet(), translated.keySet());
            english.forEach((key, value) ->
                    assertEquals(language + " " + key, placeholders(value), placeholders(translated.get(key))));
        }
    }

    @Test
    public void groupsKeysFillEveryLanguageAndCoreKeysFallBackToEnglish() throws IOException {
        ServerLanguages languages = ServerLanguages.load(folder.getRoot(), "en_us", null, LogManager.getLogger());
        String english = languages.format("en_us", "pv.addon.groups.error.group_not_found");
        assertEquals("§cGroup not found", english);
        for (String language : ServerLanguages.GROUPS_LANGUAGES) {
            assertNotEquals(language, "pv.addon.groups.error.group_not_found",
                    languages.format(language, "pv.addon.groups.error.group_not_found"));
            assertNotNull(language, languages.client(language).get("pv.activation.groups"));
            if (!language.equals("en_us")) {
                assertNotEquals(language, english, languages.format(language, "pv.addon.groups.error.group_not_found"));
            }
        }
        assertEquals(languages.format("en_us", "pv.command.reload.message"),
                languages.format("ru_ru", "pv.command.reload.message"));
        assertTrue(new File(folder.getRoot(), "groups/uk_ua.toml").isFile());
    }

    @Test
    public void editedGroupsFileOverridesTheBundledKeys() throws IOException {
        File groups = folder.newFolder("groups");
        Files.write(new File(groups, "en_us.toml").toPath(),
                "[server.pv.addon.groups.error]\ngroup_not_found = \"&cNo such group\"\n".getBytes(StandardCharsets.UTF_8));
        ServerLanguages languages = ServerLanguages.load(folder.getRoot(), "en_us", null, LogManager.getLogger());
        assertEquals("§cNo such group", languages.format("en_us", "pv.addon.groups.error.group_not_found"));
        assertEquals("§cIncorrect password", languages.format("en_us", "pv.addon.groups.command.join.error.wrong_password"));
    }

    private static Map<String, String> bundled(String language) throws IOException {
        try (InputStream in = GroupsLanguagesTest.class.getResourceAsStream("/plasmovoice/languages/groups/" + language + ".toml")) {
            assertNotNull(language + " is bundled", in);
            return ServerLanguages.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Positional placeholders may be reordered by a translation; the set of arguments must stay the same. */
    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        int next = 1;
        while (matcher.find()) found.add(matcher.group(1) != null ? matcher.group(1) : (next++) + "$");
        Collections.sort(found);
        return found;
    }
}
