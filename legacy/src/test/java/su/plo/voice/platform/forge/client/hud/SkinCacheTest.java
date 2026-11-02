package su.plo.voice.platform.forge.client.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Entry;
import su.plo.voice.platform.forge.client.hud.GroupHudModel.Layout;

import static org.junit.Assert.*;

/** The session skin cache behind the Groups heads (GroupsTab, Group HUD). */
public class SkinCacheTest {
    private static final String STEVE = "steve";

    /** A fake vanilla download: records every start, the test decides each skin's status. */
    private static final class FakeLookup implements SkinCache.Lookup<String> {
        final List<String> started = new ArrayList<>();
        final List<String> discarded = new ArrayList<>();
        final Map<String, SkinCache.Status> status = new HashMap<>();

        @Override
        public String start(String nickname) {
            started.add(nickname);
            String skin = "skins/" + nickname;
            if (!status.containsKey(skin)) status.put(skin, SkinCache.Status.LOADING);
            return skin;
        }

        @Override
        public SkinCache.Status status(String skin) {
            return status.get(skin);
        }

        @Override
        public void discard(String skin) {
            discarded.add(skin);
            status.remove(skin);
        }
    }

    private final FakeLookup lookup = new FakeLookup();
    private long now = 1_000L;
    private final SkinCache<String> cache = new SkinCache<>(lookup, () -> now);

    @Test
    public void theFirstRequestStartsExactlyOneLookup() {
        assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
        assertEquals(Arrays.asList("Alex"), lookup.started);
        assertEquals(1, cache.entry("Alex").lookups);
    }

    @Test
    public void repeatedRequestsReuseTheSameEntry() {
        SkinCache.Entry<String> first = null;
        for (int frame = 0; frame < 500; frame++) {
            assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
            if (first == null) first = cache.entry("Alex");
            assertSame(first, cache.entry("Alex"));
        }
        assertEquals(1, lookup.started.size());
        assertEquals(1, cache.size());

        lookup.status.put("skins/Alex", SkinCache.Status.LOADED);
        assertEquals("skins/Alex", cache.resolve("Alex", null, STEVE));
        assertEquals("skins/Alex", cache.resolve("Alex", null, STEVE));
        assertEquals(1, lookup.started.size());
    }

    @Test
    public void theKeyIsTheRealNicknameNotADecoratedLabel() {
        cache.resolve("Alex", null, STEVE);
        cache.resolve("§7alex", null, STEVE);
        cache.resolve(" ALEX ", null, STEVE);
        assertEquals(1, cache.size());
        assertEquals(1, lookup.started.size());
        assertNull(SkinCache.key(null));
        assertNull(SkinCache.key("§c "));
        // a missing name never touches the cache: the live skin or the fallback, like the generic overlay
        assertEquals(STEVE, cache.resolve(null, null, STEVE));
        assertEquals("live", cache.resolve("", "live", STEVE));
        assertEquals(1, cache.size());
    }

    @Test
    public void groupsTabAndHudShareResolvedEntries() {
        UUID id = UUID.randomUUID();
        // GroupsTab draws the member by its real name...
        assertEquals("skins/Alex/live", cache.resolve("Alex", "skins/Alex/live", STEVE));
        // ...the HUD row carries the same real name even when its text is cut with "..."
        Layout layout = GroupHudModel.layout(Arrays.asList(new Entry(id, "Alex", false)), false, false,
                text -> text.length() * 6, count -> "+" + count);
        String hudName = layout.rows.get(0).skinName;
        assertEquals("Alex", hudName);
        assertEquals("skins/Alex/live", cache.resolve(hudName, null, STEVE));
        assertTrue(lookup.started.isEmpty());

        Layout longName = GroupHudModel.layout(Arrays.asList(new Entry(id, "AVeryLongPlayerName12345", false)),
                false, false, text -> text.length() * 20, count -> "+" + count);
        assertTrue(longName.rows.get(0).text.endsWith("..."));
        assertEquals("AVeryLongPlayerName12345", longName.rows.get(0).skinName);
    }

    @Test
    public void theEditorPreviewUsesTheFallbackWithoutALookup() {
        Layout preview = GroupHudModel.layout(Arrays.asList(new Entry(UUID.randomUUID(), "PlayerOne", false, null)),
                false, false, text -> text.length() * 6, count -> "+" + count);
        assertNull(preview.rows.get(0).skinName);
        assertEquals(STEVE, cache.resolve(preview.rows.get(0).skinName, null, STEVE));
        assertTrue(lookup.started.isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    public void aMissingLiveEntityFallsBackToTheCachedSkin() {
        assertEquals("skins/Alex/live", cache.resolve("Alex", "skins/Alex/live", STEVE));
        // unloaded, out of range or offline: no entity any more
        for (int frame = 0; frame < 10; frame++) {
            assertEquals("skins/Alex/live", cache.resolve("Alex", null, STEVE));
        }
        assertTrue(lookup.started.isEmpty());
        // a newer live skin replaces the remembered one
        assertEquals("skins/Alex/new", cache.resolve("Alex", "skins/Alex/new", STEVE));
        assertEquals("skins/Alex/new", cache.resolve("Alex", null, STEVE));
    }

    @Test
    public void anUnresolvedEntryUsesTheFallback() {
        assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
        assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
        assertEquals(SkinCache.State.PENDING, cache.entry("Alex").state);
        // a texture another mod registered is used as it is
        lookup.status.put("skins/Alex", SkinCache.Status.UNKNOWN);
        assertEquals("skins/Alex", cache.resolve("Alex", null, STEVE));
    }

    @Test
    public void aFailedLookupDoesNotRetryEveryFrame() {
        cache.resolve("Alex", null, STEVE);
        lookup.status.put("skins/Alex", SkinCache.Status.FAILED);
        for (int frame = 0; frame < 1000; frame++) {
            assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
            now += 10;
        }
        assertEquals(1, lookup.started.size());
        assertEquals(SkinCache.State.FAILED, cache.entry("Alex").state);

        now = cache.entry("Alex").failedAt + SkinCache.RETRY_COOLDOWN_MS;
        assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
        assertEquals(2, lookup.started.size());
        assertEquals(Arrays.asList("skins/Alex"), lookup.discarded);
        assertEquals(SkinCache.State.PENDING, cache.entry("Alex").state);
        lookup.status.put("skins/Alex", SkinCache.Status.LOADED);
        assertEquals("skins/Alex", cache.resolve("Alex", null, STEVE));
        assertEquals(2, lookup.started.size());
    }

    @Test
    public void anAccountWithoutACustomSkinStopsBeingLookedUp() {
        // the default-skin account: the name download fails every time (no custom skin to fetch)
        for (int frame = 0; frame < 20_000; frame++) {
            assertEquals(STEVE, cache.resolve("NoSkin", null, STEVE));
            lookup.status.put("skins/NoSkin", SkinCache.Status.FAILED);
            now += 50;
        }
        assertEquals(SkinCache.MAX_LOOKUPS, lookup.started.size());
        assertEquals(SkinCache.State.FAILED, cache.entry("NoSkin").state);
        // the live skin, once the player shows one, still wins
        assertEquals("skins/NoSkin/live", cache.resolve("NoSkin", "skins/NoSkin/live", STEVE));
        assertEquals("skins/NoSkin/live", cache.resolve("NoSkin", null, STEVE));
        // a new session tries again
        cache.clear();
        cache.resolve("NoSkin", null, STEVE);
        assertEquals(SkinCache.MAX_LOOKUPS + 1, lookup.started.size());
    }

    @Test
    public void theServerNamePlaceholderIsNotANickname() {
        UUID id = UUID.fromString("a38bbf5d-d14a-32a9-8cb2-ac7054080001");
        assertTrue(SkinCache.isPlaceholder(id, "a38bbf5d"));
        assertTrue(SkinCache.isPlaceholder(id, "A38BBF5D"));
        assertFalse(SkinCache.isPlaceholder(id, "SacotoDayle"));
        assertFalse(SkinCache.isPlaceholder(null, "a38bbf5d"));
        assertFalse(SkinCache.isPlaceholder(id, null));
    }

    @Test
    public void caseVariantsOfOneUsernameShareOneEntry() {
        assertEquals("skins/Alex/live", cache.resolve("Alex", "skins/Alex/live", STEVE));
        assertEquals("skins/Alex/live", cache.resolve("alex", null, STEVE));
        assertEquals("skins/Alex/live", cache.resolve("ALEX", null, STEVE));
        assertEquals(1, cache.size());
        assertTrue(lookup.started.isEmpty());
    }

    @Test
    public void aResetClearsTheCache() {
        cache.resolve("Alex", "skins/Alex/live", STEVE);
        cache.resolve("Steve2", null, STEVE);
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.entry("Alex"));
        // the next session resolves again
        assertEquals(STEVE, cache.resolve("Alex", null, STEVE));
        assertEquals(Arrays.asList("Steve2", "Alex"), lookup.started);
    }

    @Test
    public void unloadingDoesNotRemoveTheEntry() {
        cache.resolve("Alex", "skins/Alex/live", STEVE);
        cache.resolve("Bob", null, STEVE);
        // many frames where neither player is loaded or visible any more
        for (int frame = 0; frame < 100; frame++) {
            cache.resolve("Alex", null, STEVE);
            cache.resolve("Bob", null, STEVE);
            now += 1000;
        }
        assertEquals(2, cache.size());
        assertNotNull(cache.entry("Alex"));
        assertNotNull(cache.entry("Bob"));
        assertEquals("skins/Alex/live", cache.resolve("Alex", null, STEVE));
    }
}
