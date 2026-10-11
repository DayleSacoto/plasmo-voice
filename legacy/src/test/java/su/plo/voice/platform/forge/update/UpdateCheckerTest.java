package su.plo.voice.platform.forge.update;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.update.UpdateChecker.Release;
import su.plo.voice.platform.forge.update.UpdateChecker.Version;

import static org.junit.Assert.*;

/** Network free: releases come from local JSON, the fetcher is a fake and the check runs synchronously. */
public class UpdateCheckerTest {
    /** The releases published before the project got its own version numbers. */
    private static final String[] HISTORICAL = {"2.1.17-forge-1.7.10", "2.1.17-forge-1.7.10-r1", "2.1.17-forge-1.7.10-r2",
            "2.1.17-forge-1.7.10-r3"};
    /** Tags in upstream Plasmo Voice numbering, and plain versions without the prefix of this project. */
    private static final String[] NOT_LEGACY = {"2.1.18", "v2.1.18", "2.2.0", "v2.2.0", "2.2.0-beta.1", "3.0.0", "v0.5.0",
            "0.5.0"};

    @Test
    public void noReleases() {
        assertNull(UpdateChecker.newest("[]"));
    }

    @Test
    public void legacyReleaseTagsParse() {
        assertEquals("0.4.0", Version.parse("legacy-0.4.0").label());
        assertEquals("1.12.3", Version.parse("legacy-1.12.3").label());
        assertEquals("0.5.0", UpdateChecker.newest(list("legacy-0.4.0", "legacy-0.5.0", "legacy-0.4.9")).label());
    }

    @Test
    public void versionsCompareNumerically() {
        assertNewer("0.4.1", "0.4.0");
        assertNewer("0.5.0", "0.4.9");
        assertNewer("0.10.0", "0.9.9");
        assertNewer("1.0.0", "0.99.0");
        assertEquals(0, Version.parse("legacy-0.4.0").compareTo(Version.of("0.4.0")));
        assertEquals("0.10.0", UpdateChecker.newest(list("legacy-0.9.9", "legacy-0.10.0", "legacy-0.2.0")).label());
    }

    /** Releases of upstream Plasmo Voice have no effect, however much larger their numbers are. */
    @Test
    public void upstreamAndUnprefixedTagsAreNeverAnUpdate() {
        for (String tag : NOT_LEGACY) assertNull(tag, Version.parse(tag));
        assertNull(UpdateChecker.newest(list(NOT_LEGACY)));
        assertTrue(check("0.4.0", list(NOT_LEGACY)).isEmpty());
        assertEquals("0.4.1", UpdateChecker.newest(list(with(NOT_LEGACY, "legacy-0.4.1"))).label());
    }

    /** 2.1.17-forge-1.7.10 and its r1..r3 revisions stay published and are skipped without an error. */
    @Test
    public void historicalReleasesAreNeverAnUpdate() {
        for (String tag : HISTORICAL) assertNull(tag, Version.parse(tag));
        assertNull(UpdateChecker.newest(list(HISTORICAL)));
        assertTrue(check("0.4.0", list(HISTORICAL)).isEmpty());
        assertEquals("0.4.1", UpdateChecker.newest(list(with(HISTORICAL, "legacy-0.4.1"))).label());
    }

    @Test
    public void unrelatedAndMalformedTagsAreIgnored() {
        for (String tag : new String[] {"legacy-0.4", "legacy-0.4.0.1", "legacy-0.4.0-beta", "legacy-0.4.x", "legacy-v0.4.0",
                "legacy-", "legacy0.4.0", "Legacy-0.4.0", "legacy-0.4.0-forge-1.7.10", "legacy-0.4.0-r1", "r4", "2.1.17-r4",
                "0.4.0-forge-1.7.10", "latest", ""}) {
            assertNull(tag, Version.parse(tag));
        }
        String json = "[" + release("legacy-0.5.0-beta") + "," + release("latest") + "," + release("legacy-0.4.1")
                + ",{\"tag_name\":null},{\"draft\":false},7,\"text\"]";
        assertEquals("0.4.1", UpdateChecker.newest(json).label());
    }

    @Test
    public void draftsAreIgnoredAndPublishedPreReleasesCount() {
        String json = "[" + release("legacy-0.6.0", true, false) + "," + release("legacy-0.5.0", false, true) + ","
                + release("legacy-0.4.1") + "]";
        assertEquals("0.5.0", UpdateChecker.newest(json).label());
    }

    @Test
    public void onlyReleasePagesOfTheRepositoryAreLinked() {
        String page = "https://github.com/DayleSacoto/plasmo-voice/releases/tag/legacy-0.4.1";
        String own = "[{\"tag_name\":\"legacy-0.4.1\",\"draft\":false,\"prerelease\":true,\"html_url\":\"" + page + "\"}]";
        assertEquals(page, UpdateChecker.newest(own).url());
        String foreign = own.replace("https://github.com/DayleSacoto/plasmo-voice/releases/", "https://example.com/");
        assertEquals(page, UpdateChecker.newest(foreign).url());
    }

    @Test
    public void sameReleaseIsNoUpdate() {
        assertTrue(check("0.4.0", list("legacy-0.4.0", "legacy-0.3.9")).isEmpty());
    }

    @Test
    public void olderReleaseGetsTheUpdate() {
        List<Release> found = check("0.4.0", list("legacy-0.4.1", "legacy-0.4.0"));
        assertEquals(1, found.size());
        assertEquals("0.4.1", found.get(0).label());
    }

    @Test
    public void newerDevelopmentBuildIsNotOfferedADowngrade() {
        assertTrue(check("0.5.0", list("legacy-0.4.9")).isEmpty());
        assertTrue(check("1.0.0", list("legacy-0.99.0")).isEmpty());
    }

    @Test
    public void malformedJsonFailsSafely() {
        for (String body : new String[] {"{", "{\"message\":\"API rate limit exceeded\"}", "<html>", "", "null"}) {
            List<Release> found = new ArrayList<>();
            checker(address -> body, "0.4.0").start(true, found::add);
            assertTrue(body, found.isEmpty());
        }
    }

    @Test
    public void networkAndHttpFailuresFailSafely() {
        List<Release> found = new ArrayList<>();
        checker(address -> {
            throw new UnknownHostException("api.github.com");
        }, "0.4.0").start(true, found::add);
        checker(address -> {
            throw new IOException("HTTP 403");
        }, "0.4.0").start(true, found::add);
        assertTrue(found.isEmpty());
    }

    @Test
    public void disabledCheckMakesNoRequest() {
        AtomicInteger requests = new AtomicInteger();
        checker(address -> {
            requests.incrementAndGet();
            return list("legacy-9.0.0");
        }, "0.4.0").start(false, release -> fail());
        assertEquals(0, requests.get());
    }

    @Test
    public void repeatedLifecycleEventsCheckAndNotifyOnce() {
        AtomicInteger requests = new AtomicInteger();
        List<Release> found = new ArrayList<>();
        UpdateChecker checker = checker(address -> {
            requests.incrementAndGet();
            assertEquals(UpdateChecker.RELEASES_API, address);
            return list("legacy-0.4.1");
        }, "0.4.0");
        for (int i = 0; i < 3; i++) checker.start(true, found::add);
        assertEquals(1, requests.get());
        assertEquals(1, found.size());
    }

    /** The installed version is the plain release version of this build, never the voice compatibility version. */
    @Test
    public void installedVersionIsTheLegacyReleaseVersion() {
        Version current = UpdateChecker.INSTANCE.getCurrent();
        assertNotNull(current);
        assertEquals(PlasmoVoiceMod.VERSION, current.label());
        assertNotEquals(PlasmoVoiceMod.VOICE_COMPAT_VERSION, current.label());
        // The tag of this build's own release is the same version; only a later legacy release is an update.
        assertEquals(0, Version.parse("legacy-" + PlasmoVoiceMod.VERSION).compareTo(current));
        assertTrue(Version.parse("legacy-999.0.0").compareTo(current) > 0);
        assertTrue(check(PlasmoVoiceMod.VERSION, list(with(HISTORICAL, NOT_LEGACY))).isEmpty());
    }

    private static void assertNewer(String newer, String older) {
        assertTrue(newer + " > " + older, Version.of(newer).compareTo(Version.of(older)) > 0);
        assertTrue(older + " < " + newer, Version.of(older).compareTo(Version.of(newer)) < 0);
    }

    private static List<Release> check(String current, String json) {
        List<Release> found = new ArrayList<>();
        checker(address -> json, current).start(true, found::add);
        return found;
    }

    private static UpdateChecker checker(UpdateChecker.Fetcher fetcher, String current) {
        return new UpdateChecker(fetcher, Version.of(current), Runnable::run);
    }

    private static String[] with(String[] tags, String... more) {
        String[] all = java.util.Arrays.copyOf(tags, tags.length + more.length);
        System.arraycopy(more, 0, all, tags.length, more.length);
        return all;
    }

    private static String list(String... tags) {
        List<String> releases = new ArrayList<>();
        for (String tag : tags) releases.add(release(tag));
        return "[" + String.join(",", releases) + "]";
    }

    private static String release(String tag) {
        return release(tag, false, false);
    }

    private static String release(String tag, boolean draft, boolean prerelease) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease
                + ",\"html_url\":\"https://github.com/DayleSacoto/plasmo-voice/releases/tag/" + tag + "\"}";
    }
}
