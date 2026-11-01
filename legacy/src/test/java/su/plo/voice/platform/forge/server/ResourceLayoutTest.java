package su.plo.voice.platform.forge.server;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import su.plo.voice.platform.forge.client.lang.VoiceTranslations;

import static org.junit.Assert.*;

/** The bundled localization lives under plasmovoice/lang/{client,server/core,server/groups} and nowhere else. */
public class ResourceLayoutTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static boolean exists(String path) {
        return ResourceLayoutTest.class.getResource(path) != null;
    }

    @Test
    public void clientLoaderReadsEveryBundledLocaleFromTheClientFolder() {
        for (String language : VoiceTranslations.BUNDLED) {
            assertNotNull(language, VoiceTranslations.CLASSPATH.apply(language));
            assertTrue(exists("/plasmovoice/lang/client/" + language + ".lang"));
        }
    }

    @Test
    public void coreServerLoaderReadsItsBundledEnglishAndGroupsLoaderItsFive() throws Exception {
        assertTrue(exists("/plasmovoice/lang/server/core/en_us.toml"));
        File folder = temp.newFolder("languages");
        ServerLanguages.load(folder, "en_us", null, LogManager.getLogger("ResourceLayoutTest"));
        assertTrue(new File(folder, "en_us.toml").isFile());
        for (String language : ServerLanguages.GROUPS_LANGUAGES) {
            assertTrue(exists("/plasmovoice/lang/server/groups/" + language + ".toml"));
            assertTrue(new File(folder, "groups/" + language + ".toml").isFile());
        }
    }

    @Test
    public void oldLocationsAreGone() {
        for (String path : new String[] {
                "/plasmovoice/lang/en_us.lang", "/plasmovoice/lang/ru_ru.lang", "/plasmovoice/lang/uk_ua.lang",
                "/plasmovoice/lang/de_de.lang", "/plasmovoice/lang/fr_fr.lang",
                "/plasmovoice/languages/en_us.toml", "/plasmovoice/languages/groups/en_us.toml",
                "/LICENSE-pv-addon-groups.txt"}) {
            assertFalse(path, exists(path));
        }
        assertTrue(exists("/META-INF/licenses/pv-addon-groups-MIT.txt"));
    }
}
