package su.plo.voice.platform.forge.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class ServerProtocolTest {
    private static final Logger LOGGER = LogManager.getLogger("test");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void bundledLanguageSplitsServerAndClientKeys() throws Exception {
        File languagesFolder = new File(folder.getRoot(), "languages");
        ServerLanguages languages = ServerLanguages.load(languagesFolder, "en_us", null, LOGGER);

        // Copied for the administrator like upstream's languages folder.
        assertTrue(new File(languagesFolder, "en_us.toml").isFile());
        assertEquals("Proximity", languages.client("en_us").get("pv.activation.proximity"));
        assertFalse(languages.client("en_us").containsKey("pv.mutes.unmuted"));
        assertEquals("You've been unmuted", languages.format("en_us", "pv.mutes.unmuted"));
        assertEquals("Muted Steve for 10 min. Reason: spam",
                languages.format("en_us", "pv.command.mute.temporarily_muted", "Steve", "for 10 min", "spam"));
        // Upstream "&c" colour codes, and the multiline kick message.
        assertTrue(languages.format(null, "pv.error.no_permissions").startsWith("\u00a7cI'm sorry"));
        assertTrue(languages.format(null, "pv.error.mod_missing_kick_message")
                .startsWith("Sorry, you need to install the Plasmo Voice mod to play on this server.\nDownload here:"));
        // An unknown client language falls back to the default one; a missing key is shown as is.
        assertEquals("Proximity", languages.client("xx_yy").get("pv.activation.proximity"));
        assertEquals("pv.unknown", languages.format("en_us", "pv.unknown"));
    }

    @Test
    public void administratorLanguagesOverrideAndForcedLanguageWins() throws Exception {
        File languagesFolder = folder.newFolder("languages");
        Files.write(new File(languagesFolder, "ru_ru.toml").toPath(),
                "[client.pv.activation]\nproximity = \"\\u0420\\u044f\\u0434\\u043e\\u043c\"\n".getBytes(StandardCharsets.UTF_8));

        ServerLanguages languages = ServerLanguages.load(languagesFolder, "en_us", null, LOGGER);
        assertEquals("\u0420\u044f\u0434\u043e\u043c", languages.client("ru_RU").get("pv.activation.proximity"));
        // Keys missing from a translation come from the default language.
        assertEquals("You've been unmuted", languages.format("ru_ru", "pv.mutes.unmuted"));

        ServerLanguages forced = ServerLanguages.load(languagesFolder, "en_us", "ru_ru", LOGGER);
        assertEquals("\u0420\u044f\u0434\u043e\u043c", forced.client("en_us").get("pv.activation.proximity"));
    }

    @Test
    public void mutesSurviveARestartAndExpire() throws Exception {
        File file = new File(folder.getRoot(), "voice_mutes.json");
        UUID permanent = UUID.randomUUID();
        UUID temporary = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        long now = System.currentTimeMillis();

        VoiceMutes mutes = new VoiceMutes(file, LOGGER);
        mutes.load();
        assertNotNull(mutes.mute(permanent, admin, 0, null, false));
        assertNotNull(mutes.mute(temporary, null, now + 60_000L, "spam", true));
        assertNull(mutes.mute(UUID.randomUUID(), null, now - 1L, null, false));

        VoiceMutes loaded = new VoiceMutes(file, LOGGER);
        loaded.load();
        assertTrue(loaded.isMuted(permanent));
        assertEquals(admin, loaded.get(permanent).getMutedById());
        assertEquals("spam", loaded.get(temporary).getReason());
        assertTrue(loaded.get(temporary).isSilent());
        assertEquals(2, loaded.all().size());
        assertTrue(loaded.expired(now).isEmpty());
        assertEquals(1, loaded.expired(now + 120_000L).size());

        // Upstream JsonMuteStorage layout: player id keys with the ServerMuteInfo fields.
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"" + permanent + "\":{\"playerUUID\":\"" + permanent + "\""));
        assertTrue(json.contains("\"mutedToTime\":0"));

        assertNotNull(loaded.unmute(permanent));
        assertNull(loaded.unmute(permanent));
        VoiceMutes reloaded = new VoiceMutes(file, LOGGER);
        reloaded.load();
        assertFalse(reloaded.isMuted(permanent));
    }

    @Test
    public void muteDurationsFollowUpstreamSyntax() {
        long now = 1_000_000L;
        assertEquals(now + 10 * 60_000L, VoiceCommands.MuteDuration.parse("10m").mutedToTime(now));
        assertEquals(now + 5_000L, VoiceCommands.MuteDuration.parse("5").mutedToTime(now));
        assertEquals(now + 2 * 604_800_000L, VoiceCommands.MuteDuration.parse("2w").mutedToTime(now));
        assertEquals(1_700_000_000_000L, VoiceCommands.MuteDuration.parse("1700000000u").mutedToTime(now));
        assertEquals(0, VoiceCommands.MuteDuration.parse("permanent").mutedToTime(now));
        // Not durations: the argument starts the reason.
        assertNull(VoiceCommands.MuteDuration.parse("spam"));
        assertNull(VoiceCommands.MuteDuration.parse("m"));
    }

    @Test
    public void tomlSubsetParsesEscapesAndMultilineStrings() {
        Map<String, String> keys = ServerLanguages.parse("# comment\n[a.b]\nx = \"say \\\"hi\\\"\\n\"\ny = \"\"\"\nline one\nline two\"\"\"\n");
        assertEquals("say \"hi\"\n", keys.get("a.b.x"));
        assertEquals("line one\nline two", keys.get("a.b.y"));
    }
}
