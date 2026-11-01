package su.plo.voice.platform.forge.client.groups;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.network.groups.GroupsAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GroupsFormsTest {
    @Test
    public void permissionsAreSplitTrimmedAndDeduplicated() {
        assertEquals(Arrays.asList("a.b", "c.d"), GroupsForms.parsePermissions(" a.b , c.d,,a.b ; "));
        assertTrue(GroupsForms.parsePermissions("  ").isEmpty());
        assertTrue(GroupsForms.parsePermissions(null).isEmpty());
        assertEquals("a.b, c.d", GroupsForms.formatPermissions(Arrays.asList("a.b", "c.d")));
        assertEquals("", GroupsForms.formatPermissions(null));
    }

    @Test
    public void createSendsOnlyAllowedAndFilledFields() {
        GroupsAction full = GroupsForms.create(7, "  Team ", "pw", true, "a, b", true, true, true, true);
        assertEquals(GroupsAction.Type.CREATE, full.type);
        assertEquals(7, full.requestId);
        assertEquals("Team", full.name);
        assertEquals("pw", full.password);
        assertEquals(Boolean.TRUE, full.persistent);
        assertEquals(Arrays.asList("a", "b"), full.permissions);

        GroupsAction empty = GroupsForms.create(8, " ", "", false, "", true, true, true, true);
        assertNull(empty.name);
        assertNull(empty.password);
        assertNull(empty.persistent);
        assertNull(empty.permissions);

        GroupsAction denied = GroupsForms.create(9, "Team", "pw", true, "a", false, false, false, false);
        assertNull(denied.name);
        assertNull(denied.password);
        assertNull(denied.persistent);
        assertNull(denied.permissions);
    }

    @Test
    public void joinSendsPasswordOnlyWhenGiven() {
        UUID id = UUID.randomUUID();
        assertEquals("secret", GroupsForms.join(1, id, "secret").password);
        assertNull(GroupsForms.join(2, id, "").password);
        assertNull(GroupsForms.join(3, id, null).password);
        assertEquals(id, GroupsForms.join(4, id, null).groupId);
        assertFalse(GroupsForms.join(5, id, "secret").toString().contains("secret"));
    }

    @Test
    public void updatesCarryOneFieldEach() {
        GroupsAction rename = GroupsForms.rename(1, " New ");
        assertEquals(GroupsAction.Type.UPDATE, rename.type);
        assertEquals("New", rename.name);
        assertNull(rename.password);

        GroupsAction password = GroupsForms.setPassword(2, "pw");
        assertEquals("pw", password.password);
        assertFalse(password.clearPassword);
        assertNull(password.name);

        GroupsAction clear = GroupsForms.clearPassword(3);
        assertTrue(clear.clearPassword);
        assertNull(clear.password);

        assertEquals(Boolean.FALSE, GroupsForms.setPersistent(4, false).persistent);
        assertEquals(Collections.emptyList(), GroupsForms.setPermissions(5, "").permissions);
        assertEquals(Arrays.asList("x"), GroupsForms.setPermissions(6, "x").permissions);
    }

    @Test
    public void nameLengthChecks() {
        assertTrue(GroupsForms.nameLengthOk("", 3, 16));
        assertTrue(GroupsForms.nameLengthOk("abc", 3, 16));
        assertFalse(GroupsForms.nameLengthOk("ab", 3, 16));
        assertFalse(GroupsForms.nameLengthOk("12345678901234567", 3, 16));
        assertEquals("a", GroupsForms.blankToNull(" a "));
        assertNull(GroupsForms.blankToNull("   "));
    }
}
