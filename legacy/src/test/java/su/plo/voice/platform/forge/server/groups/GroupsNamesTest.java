package su.plo.voice.platform.forge.server.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Last-known member names: remembered while online, persisted for persistent groups, with fallbacks. */
public class GroupsNamesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final GroupsManager manager = new GroupsManager(new GroupsListener() { });
    private final GroupProfile alice = new GroupProfile(UUID.randomUUID(), "Alice");
    private final GroupProfile bob = new GroupProfile(UUID.randomUUID(), "Bob");

    @Test
    public void namesSurviveGoingOfflineAndFallBackToTheCacheThenTheShortUuid() {
        Group group = manager.create("Alpha", null, false, Collections.<String>emptySet(), alice);
        manager.join(bob, group);
        manager.playerOffline(bob.getId());
        assertEquals("Bob", manager.nameOf(bob.getId()));

        UUID unknown = UUID.fromString("0a0b0c0d-1111-2222-3333-444455556666");
        assertEquals("0a0b0c0d", manager.nameOf(unknown));
        manager.setNameFallback(id -> id.equals(unknown) ? "Cached" : null);
        assertEquals("Cached", manager.nameOf(unknown));
        manager.setNameFallback(id -> {
            throw new IllegalStateException("broken cache");
        });
        assertEquals("0a0b0c0d", manager.nameOf(unknown));
    }

    @Test
    public void persistentMembersKeepTheirNamesAcrossARestart() {
        Group group = manager.create("Alpha", null, true, Collections.<String>emptySet(), alice);
        manager.join(bob, group);

        File file = new File(folder.getRoot(), "groups.json");
        GroupsStore store = new GroupsStore(file, LogManager.getLogger());
        store.load();
        store.save(manager.snapshot());

        GroupsManager restarted = new GroupsManager(new GroupsListener() { });
        restarted.restore(new GroupsStore(file, LogManager.getLogger()).load());
        assertEquals("Bob", restarted.nameOf(bob.getId()));
        assertEquals("Alice", restarted.nameOf(alice.getId()));
        assertNotNull(restarted.groupOf(bob.getId()));
    }

    @Test
    public void filesFromBeforeMemberNamesStillLoad() throws IOException {
        UUID id = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        File file = folder.newFile("old.json");
        String json = "{\"groups\":[{\"id\":\"" + id + "\",\"name\":\"Old\",\"persistent\":true,"
                + "\"playersIds\":[\"" + member + "\"],\"bannedPlayers\":[],\"permissions\":[]}],"
                + "\"groupByPlayer\":{\"" + member + "\":\"" + id + "\"}}";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

        GroupsManager loaded = new GroupsManager(new GroupsListener() { });
        loaded.restore(new GroupsStore(file, LogManager.getLogger()).load());

        assertEquals("Old", loaded.group(id).getName());
        assertEquals(member.toString().substring(0, 8), loaded.nameOf(member));
    }
}
