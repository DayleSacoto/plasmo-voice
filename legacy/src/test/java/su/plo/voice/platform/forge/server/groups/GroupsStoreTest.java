package su.plo.voice.platform.forge.server.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GroupsStoreTest {
    private static final Logger LOGGER = LogManager.getLogger("test");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private final GroupProfile alice = GroupsManagerTest.profile("Alice");
    private final GroupProfile bob = GroupsManagerTest.profile("Bob");

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), "groups.json");
    }

    private GroupsStore store() {
        return new GroupsStore(file, LOGGER);
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String text) throws IOException {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private GroupsStore.Data sampleData() {
        GroupsManager manager = new GroupsManager(new RecordingListener());
        Group g = manager.create("Grüppe <1>", "pw", true, new HashSet<>(Arrays.asList("b.perm", "a.perm")), alice);
        manager.join(bob, g);
        manager.ban(g, GroupsManagerTest.profile("Carol"));
        manager.create("temp", null, false, Collections.<String>emptySet(), GroupsManagerTest.profile("Dave"));
        return manager.snapshot();
    }

    @Test
    public void missingFileLoadsEmpty() {
        GroupsStore.Data data = store().load();
        assertTrue(data.getGroups().isEmpty());
        assertTrue(data.getGroupByPlayer().isEmpty());
    }

    @Test
    public void roundTripThroughManager() {
        store().save(sampleData());

        GroupsManager restored = new GroupsManager(new RecordingListener());
        restored.restore(store().load());
        assertEquals(1, restored.groups().size());
        Group g = restored.groups().iterator().next();
        assertEquals("Grüppe <1>", g.getName());
        assertEquals("pw", g.getPassword());
        assertTrue(g.isPersistent());
        assertEquals(alice, g.getOwner());
        assertEquals("Alice", g.getOwner().getName());
        assertEquals(new HashSet<>(Arrays.asList("a.perm", "b.perm")), g.getPermissions());
        assertEquals(2, g.memberCount());
        assertTrue(g.isBanned(GroupsManagerTest.profile("Carol").getId()));
        assertEquals("Carol", g.getBanned().get(0).getName());
        assertEquals(g, restored.groupOf(alice.getId()));
        assertEquals(g, restored.groupOf(bob.getId()));
    }

    @Test
    public void outputIsDeterministicPrettyUtf8WithUpstreamFields() throws IOException {
        GroupsStore.Data data = sampleData();
        store().save(data);
        String first = read(file);
        File other = new File(tmp.getRoot(), "other.json");
        new GroupsStore(other, LOGGER).save(data);
        assertEquals(first, read(other));
        assertTrue(first.contains("\n"));
        assertTrue(first.contains("Grüppe <1>"));
        assertTrue(first.contains("\"playersIds\""));
        assertTrue(first.contains("\"bannedPlayers\""));
        assertTrue(first.contains("\"groupByPlayer\""));
        assertTrue(first.contains("\"uuid\""));
        assertTrue(first.indexOf("a.perm") < first.indexOf("b.perm"));
    }

    @Test
    public void malformedFileIsMovedAsideAndLoadsEmpty() throws IOException {
        write(file, "{ not json");
        GroupsStore.Data data = store().load();
        assertTrue(data.getGroups().isEmpty());
        assertFalse(file.exists());
        File[] broken = tmp.getRoot().listFiles((dir, name) -> name.startsWith("groups.json.broken-"));
        assertNotNull(broken);
        assertEquals(1, broken.length);
        assertEquals("{ not json", read(broken[0]));
    }

    @Test
    public void malformedFileDoesNotBlockLaterSaves() throws IOException {
        write(file, "[1,2");
        GroupsStore s = store();
        s.load();
        s.save(sampleData());
        assertTrue(file.exists());
        assertEquals(1, store().load().getGroups().size());
    }

    @Test
    public void invalidEntriesAreSkippedRestKept() throws IOException {
        String good = UUID.randomUUID().toString();
        String member = alice.getId().toString();
        String json = "{\"groups\":["
                + "{\"id\":\"not-a-uuid\",\"name\":\"bad\"},"
                + "{\"id\":\"" + UUID.randomUUID() + "\"},"
                + "{\"id\":\"" + good + "\",\"name\":\"ok\",\"persistent\":true,"
                + "\"playersIds\":[\"" + member + "\",\"zzz\"],"
                + "\"bannedPlayers\":[{\"uuid\":\"bad\",\"name\":\"x\"},{\"uuid\":\"" + bob.getId() + "\",\"name\":\"Bob\"}],"
                + "\"owner\":{\"uuid\":\"" + member + "\",\"name\":\"Alice\"}}],"
                + "\"groupByPlayer\":{\"" + member + "\":\"" + good + "\",\"bad\":\"" + good + "\"}}";
        write(file, json);

        GroupsStore.Data data = store().load();
        assertEquals(1, data.getGroups().size());
        GroupsStore.Entry e = data.getGroups().get(0);
        assertEquals(UUID.fromString(good), e.getId());
        assertEquals("ok", e.getName());
        assertNull(e.getPassword());
        assertEquals(Collections.singleton(alice.getId()), e.getMembers());
        assertEquals(1, e.getBanned().size());
        assertEquals(bob, e.getBanned().get(0));
        assertEquals(alice, e.getOwner());
        assertTrue(e.getPermissions().isEmpty());
        assertEquals(1, data.getGroupByPlayer().size());
        assertTrue(file.exists());
    }

    @Test
    public void unreadableFileLoadsEmptyAndRefusesSaves() {
        assertTrue(file.mkdir()); // reading a directory fails with an IOException
        GroupsStore s = store();
        assertTrue(s.load().getGroups().isEmpty());
        s.save(sampleData());
        assertTrue(file.isDirectory());
    }

    @Test
    public void identicalSaveIsSkipped() throws IOException {
        GroupsStore s = store();
        GroupsStore.Data data = sampleData();
        s.save(data);
        write(file, "sentinel");
        s.save(data);
        assertEquals("sentinel", read(file));

        GroupsManager m = new GroupsManager(new RecordingListener());
        m.create("other", null, true, Collections.<String>emptySet(), null);
        s.save(m.snapshot());
        assertTrue(read(file).contains("other"));
    }

    @Test
    public void saveRightAfterLoadOfSameContentIsSkipped() throws IOException {
        store().save(sampleData());
        GroupsStore second = store();
        GroupsStore.Data loaded = second.load();
        write(file, "sentinel");
        second.save(loaded);
        assertEquals("sentinel", read(file));
    }

    @Test
    public void atomicWriteLeavesNoTmpFile() {
        store().save(sampleData());
        assertTrue(file.exists());
        assertFalse(new File(tmp.getRoot(), "groups.json.tmp").exists());
        assertEquals(1, tmp.getRoot().listFiles().length);
    }

    @Test
    public void saveCreatesMissingParentDirectory() {
        File nested = new File(new File(tmp.getRoot(), "a/b"), "groups.json");
        new GroupsStore(nested, LOGGER).save(sampleData());
        assertTrue(nested.exists());
    }
}
