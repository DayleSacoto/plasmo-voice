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
    private static final String MC = "1.7.10";

    @Test
    public void noReleases() {
        assertNull(UpdateChecker.newest("[]", MC));
    }

    @Test
    public void baseTagIsRevisionZero() {
        Version base = Version.parse("2.1.17-forge-1.7.10", MC);
        assertEquals(0, base.revision);
        assertEquals("2.1.17", base.label());
        assertTrue(base.compareTo(Version.parse("2.1.17-forge-1.7.10-r1", MC)) < 0);
    }

    @Test
    public void revisionsParse() {
        assertEquals(1, Version.parse("2.1.17-forge-1.7.10-r1", MC).revision);
        assertEquals(2, Version.parse("2.1.17-forge-1.7.10-r2", MC).revision);
        assertEquals("2.1.17-r2", Version.parse("2.1.17-forge-1.7.10-r2", MC).label());
        assertEquals("2.1.17-r2", newest(release("2.1.17-forge-1.7.10-r1"), release("2.1.17-forge-1.7.10-r2"),
                release("2.1.17-forge-1.7.10")).label());
    }

    @Test
    public void revisionsCompareNumerically() {
        assertTrue(Version.parse("2.1.17-forge-1.7.10-r10", MC).compareTo(Version.parse("2.1.17-forge-1.7.10-r9", MC)) > 0);
        assertEquals("2.1.17-r10", newest(release("2.1.17-forge-1.7.10-r9"), release("2.1.17-forge-1.7.10-r10"),
                release("2.1.17-forge-1.7.10-r2")).label());
    }

    @Test
    public void upstreamVersionComparesBeforeTheRevision() {
        assertTrue(Version.parse("2.1.18-forge-1.7.10-r1", MC).compareTo(Version.parse("2.1.17-forge-1.7.10-r9", MC)) > 0);
        assertTrue(Version.parse("2.1.17-forge-1.7.10-r9", MC).compareTo(Version.parse("2.1.10-forge-1.7.10-r9", MC)) > 0);
        assertEquals(0, Version.parse("2.1-forge-1.7.10", MC).compareTo(Version.parse("2.1.0-forge-1.7.10", MC)));
    }

    @Test
    public void unrelatedAndMalformedTagsAreIgnored() {
        for (String tag : new String[] {"2.1.17", "v2.1.17", "2.1.18", "2.1.17-forge-1.12.2-r5", "2.1.17-fabric-1.7.10-r5",
                "2.1.17-forge-1.7.10-rX", "2.1.17-forge-1.7.10-r", "-forge-1.7.10-r5", "2.1.17-forge-1.7.10-r5-hotfix",
                "2.1.17-forge-1.7.10-R5", "2.1.17-forge-1x7x10-r5", "latest", ""}) {
            assertNull(tag, Version.parse(tag, MC));
        }
        String json = "[" + release("2.2.0") + "," + release("v2.2.0") + "," + release("2.2.0-forge-1.12.2-r9") + ","
                + release("2.1.17-forge-1.7.10-r1") + ",{\"tag_name\":null},{\"draft\":false},7,\"text\"]";
        assertEquals("2.1.17-r1", UpdateChecker.newest(json, MC).label());
    }

    @Test
    public void draftsAreIgnoredAndPublishedPreReleasesCount() {
        String json = "[" + release("2.1.17-forge-1.7.10-r5", true, false) + ","
                + release("2.1.17-forge-1.7.10-r4", false, true) + "," + release("2.1.17-forge-1.7.10-r3") + "]";
        assertEquals("2.1.17-r4", UpdateChecker.newest(json, MC).label());
    }

    @Test
    public void onlyReleasePagesOfTheRepositoryAreLinked() {
        String own = "[{\"tag_name\":\"2.1.17-forge-1.7.10-r3\",\"draft\":false,\"prerelease\":true,"
                + "\"html_url\":\"https://github.com/DayleSacoto/plasmo-voice/releases/tag/2.1.17-forge-1.7.10-r3\"}]";
        assertEquals("https://github.com/DayleSacoto/plasmo-voice/releases/tag/2.1.17-forge-1.7.10-r3",
                UpdateChecker.newest(own, MC).url());
        String foreign = own.replace("https://github.com/DayleSacoto/plasmo-voice/releases/", "https://example.com/");
        assertEquals("https://github.com/DayleSacoto/plasmo-voice/releases/tag/2.1.17-forge-1.7.10-r3",
                UpdateChecker.newest(foreign, MC).url());
    }

    @Test
    public void sameReleaseIsNoUpdate() {
        assertTrue(check("2.1.17-forge-1.7.10-r2", list("2.1.17-forge-1.7.10-r2", "2.1.17-forge-1.7.10-r1")).isEmpty());
    }

    @Test
    public void olderReleaseGetsTheUpdate() {
        List<Release> found = check("2.1.17-forge-1.7.10-r2", list("2.1.17-forge-1.7.10-r3", "2.1.17-forge-1.7.10-r2"));
        assertEquals(1, found.size());
        assertEquals("2.1.17-r3", found.get(0).label());
    }

    @Test
    public void newerDevelopmentBuildIsNotOfferedADowngrade() {
        assertTrue(check("2.1.17-forge-1.7.10-r4", list("2.1.17-forge-1.7.10-r3")).isEmpty());
        assertTrue(check("2.1.18-forge-1.7.10", list("2.1.17-forge-1.7.10-r9")).isEmpty());
    }

    @Test
    public void malformedJsonFailsSafely() {
        for (String body : new String[] {"{", "{\"message\":\"API rate limit exceeded\"}", "<html>", "", "null"}) {
            List<Release> found = new ArrayList<>();
            checker(address -> body, "2.1.17-forge-1.7.10-r2").start(true, found::add);
            assertTrue(body, found.isEmpty());
        }
    }

    @Test
    public void networkAndHttpFailuresFailSafely() {
        List<Release> found = new ArrayList<>();
        checker(address -> {
            throw new UnknownHostException("api.github.com");
        }, "2.1.17-forge-1.7.10-r2").start(true, found::add);
        checker(address -> {
            throw new IOException("HTTP 403");
        }, "2.1.17-forge-1.7.10-r2").start(true, found::add);
        assertTrue(found.isEmpty());
    }

    @Test
    public void disabledCheckMakesNoRequest() {
        AtomicInteger requests = new AtomicInteger();
        checker(address -> {
            requests.incrementAndGet();
            return list("2.1.17-forge-1.7.10-r9");
        }, "2.1.17-forge-1.7.10-r2").start(false, release -> fail());
        assertEquals(0, requests.get());
    }

    @Test
    public void repeatedLifecycleEventsCheckAndNotifyOnce() {
        AtomicInteger requests = new AtomicInteger();
        List<Release> found = new ArrayList<>();
        UpdateChecker checker = checker(address -> {
            requests.incrementAndGet();
            assertEquals(UpdateChecker.RELEASES_API, address);
            return list("2.1.17-forge-1.7.10-r3");
        }, "2.1.17-forge-1.7.10-r2");
        for (int i = 0; i < 3; i++) checker.start(true, found::add);
        assertEquals(1, requests.get());
        assertEquals(1, found.size());
    }

    @Test
    public void currentVersionComesFromTheBuild() {
        Version current = Version.current();
        assertNotNull("plasmovoice/backport.properties is filled in by processResources", current);
        assertEquals(MC, current.minecraftVersion);
        assertTrue(current.label().startsWith(PlasmoVoiceMod.VERSION));
        assertNotNull(UpdateChecker.INSTANCE.getCurrent());
    }

    private static List<Release> check(String current, String json) {
        List<Release> found = new ArrayList<>();
        checker(address -> json, current).start(true, found::add);
        return found;
    }

    private static UpdateChecker checker(UpdateChecker.Fetcher fetcher, String current) {
        return new UpdateChecker(fetcher, Version.parse(current, MC), Runnable::run);
    }

    private static Release newest(String... releases) {
        return UpdateChecker.newest("[" + String.join(",", releases) + "]", MC);
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
