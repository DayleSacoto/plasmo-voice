package su.plo.voice.platform.forge.network.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;

import org.junit.Test;

public class GroupsProtocolTest {
    private static final UUID A = UUID.fromString("00000000-0000-0001-0000-000000000002");
    private static final UUID B = UUID.fromString("00000000-0000-0003-0000-000000000004");

    private static void assertRejected(byte[] data) {
        try {
            Object decoded = GroupsProtocol.decode(data);
            fail("accepted " + decoded);
        } catch (IOException expected) {
            // good
        }
    }

    @Test
    public void actionRoundTripsEveryField() throws IOException {
        for (GroupsAction.Type type : GroupsAction.Type.values()) {
            GroupsAction in = new GroupsAction(77, type, A, B, "Name", "pw", true, Boolean.FALSE,
                    Arrays.asList("a.b", "c.d"));
            GroupsAction out = (GroupsAction) GroupsProtocol.decode(GroupsProtocol.encode(in));
            assertEquals(77, out.requestId);
            assertEquals(type, out.type);
            assertEquals(A, out.groupId);
            assertEquals(B, out.playerId);
            assertEquals("Name", out.name);
            assertEquals("pw", out.password);
            assertTrue(out.clearPassword);
            assertEquals(Boolean.FALSE, out.persistent);
            assertEquals(Arrays.asList("a.b", "c.d"), out.permissions);
        }
        GroupsAction empty = (GroupsAction) GroupsProtocol.decode(
                GroupsProtocol.encode(GroupsAction.simple(5, GroupsAction.Type.REFRESH)));
        assertNull(empty.groupId);
        assertNull(empty.name);
        assertNull(empty.persistent);
        assertNull(empty.permissions);

        GroupsAction update = (GroupsAction) GroupsProtocol.decode(GroupsProtocol.encode(new GroupsAction(1,
                GroupsAction.Type.UPDATE, null, null, null, null, false, Boolean.TRUE, Collections.<String>emptyList())));
        assertEquals(Boolean.TRUE, update.persistent);
        assertTrue(update.permissions.isEmpty());
    }

    @Test
    public void actionToStringNeverShowsThePassword() {
        assertFalse(GroupsAction.join(1, A, "hunter2").toString().contains("hunter2"));
    }

    @Test
    public void stateRoundTrips() throws IOException {
        GroupsState.CurrentGroup current = new GroupsState.CurrentGroup(A, "Alpha", B, "Owner", true, true,
                Arrays.asList("p.one", "p.two"),
                Arrays.asList(new GroupsState.Member(A, "Alice", true), new GroupsState.Member(B, "Bob", false)),
                Collections.singletonList(new GroupsState.Member(UUID.randomUUID(), "Banned", false)), 0x7FF);
        GroupsState in = new GroupsState(0x7F, 3, 32, current, Arrays.asList(
                new GroupsState.BrowseEntry(A, "Alpha", "Owner", 1, 2, true, false, true),
                new GroupsState.BrowseEntry(B, "Beta", null, 0, 0, false, true, false)), 250);

        GroupsState out = (GroupsState) GroupsProtocol.decode(GroupsProtocol.encode(in));

        assertEquals(0x7F, out.capabilities);
        assertEquals(3, out.nameMin);
        assertEquals(32, out.nameMax);
        assertEquals(A, out.current.id);
        assertEquals("Owner", out.current.ownerName);
        assertTrue(out.current.hasPassword);
        assertTrue(out.current.persistent);
        assertEquals(Arrays.asList("p.one", "p.two"), out.current.permissions);
        assertEquals(2, out.current.members.size());
        assertEquals("Bob", out.current.members.get(1).name);
        assertFalse(out.current.members.get(1).online);
        assertEquals(1, out.current.banned.size());
        assertEquals(0x7FF, out.current.capabilities);
        assertEquals(2, out.browse.size());
        assertTrue(out.browse.get(0).hasPassword);
        assertTrue(out.browse.get(0).joinable);
        assertTrue(out.browse.get(1).persistent);
        assertFalse(out.browse.get(1).joinable);
        assertNull(out.browse.get(1).ownerName);
        assertEquals(250, out.browseTotal);

        GroupsState none = (GroupsState) GroupsProtocol.decode(GroupsProtocol.encode(GroupsState.EMPTY));
        assertNull(none.current);
        assertTrue(none.browse.isEmpty());
    }

    @Test
    public void resultRoundTrips() throws IOException {
        GroupsResult out = (GroupsResult) GroupsProtocol.decode(
                GroupsProtocol.encode(new GroupsResult(9, false, "error.no_permission", "§cNo permission")));
        assertEquals(9, out.requestId);
        assertFalse(out.ok);
        assertEquals("error.no_permission", out.key);
        assertEquals("§cNo permission", out.message);
    }

    @Test
    public void garbageIsRejected() {
        assertRejected(null);
        assertRejected(new byte[0]);
        assertRejected(new byte[] {1});
        Random random = new Random(42);
        int accepted = 0;
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[1 + random.nextInt(80)];
            random.nextBytes(junk);
            try {
                GroupsProtocol.decode(junk);
                accepted++;
            } catch (IOException expected) {
                // rejected; anything but an IOException would fail the test
            }
        }
        assertTrue("accepted " + accepted, accepted < 5);
    }

    @Test
    public void trailingBytesTruncationUnknownVersionAndKindAreRejected() throws IOException {
        byte[] good = GroupsProtocol.encode(GroupsAction.simple(1, GroupsAction.Type.LEAVE));
        GroupsProtocol.decode(good);

        assertRejected(Arrays.copyOf(good, good.length + 1));
        assertRejected(Arrays.copyOf(good, good.length - 1));

        byte[] version = good.clone();
        version[0] = (byte) (GroupsProtocol.VERSION + 1);
        assertRejected(version);

        byte[] kind = good.clone();
        kind[1] = 99;
        assertRejected(kind);

        byte[] type = good.clone();
        type[2 + 4] = (byte) GroupsAction.Type.values().length;
        assertRejected(type);
    }

    @Test
    public void oversizedStringsListsAndFlagsAreRejected() throws IOException {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i <= GroupsProtocol.MAX_NAME; i++) longName.append('x');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        // name one character over the limit
        out.writeByte(GroupsProtocol.VERSION);
        out.writeByte(GroupsProtocol.KIND_ACTION);
        out.writeInt(1);
        out.writeByte(GroupsAction.Type.CREATE.ordinal());
        out.writeBoolean(false); // groupId
        out.writeBoolean(false); // playerId
        out.writeBoolean(true); // name present
        out.writeUTF(longName.toString());
        out.writeBoolean(false); // password
        out.writeBoolean(false); // clearPassword
        out.writeByte(0); // persistent
        out.writeBoolean(false); // permissions
        assertRejected(bytes.toByteArray());

        // one permission too many
        bytes.reset();
        out.writeByte(GroupsProtocol.VERSION);
        out.writeByte(GroupsProtocol.KIND_ACTION);
        out.writeInt(1);
        out.writeByte(GroupsAction.Type.CREATE.ordinal());
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeByte(0);
        out.writeBoolean(true);
        out.writeByte(GroupsProtocol.MAX_PERMISSIONS + 1);
        assertRejected(bytes.toByteArray());

        // invalid persistent flag
        bytes.reset();
        out.writeByte(GroupsProtocol.VERSION);
        out.writeByte(GroupsProtocol.KIND_ACTION);
        out.writeInt(1);
        out.writeByte(GroupsAction.Type.CREATE.ordinal());
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeByte(3);
        out.writeBoolean(false);
        assertRejected(bytes.toByteArray());
    }
}
