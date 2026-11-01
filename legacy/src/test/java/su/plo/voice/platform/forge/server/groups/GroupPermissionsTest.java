package su.plo.voice.platform.forge.server.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GroupPermissionsTest {
    private static final String[] EVERYONE = {
            "browse", "create", "join", "leave", "invite.owner", "invite.member", "info.owner", "info.member",
            "set.owner", "unset.owner", "delete.owner", "transfer.owner", "kick.owner", "ban.owner",
            "flag.name", "flag.password"};

    @Test
    public void prefixHelper() {
        assertEquals("pv.addon.groups.", GroupPermissions.PREFIX);
        assertEquals("pv.addon.groups.join", GroupPermissions.addon("join"));
    }

    @Test
    public void defaultNodesAreOpenToEveryone() {
        for (String sub : EVERYONE) {
            assertTrue(sub, GroupPermissions.has(GroupPermissions.addon(sub), false));
            assertTrue(sub, GroupPermissions.has(GroupPermissions.addon(sub), true));
        }
        assertTrue(GroupPermissions.has("pv.activation.groups", false));
    }

    @Test
    public void everythingElseIsOpOnly() {
        String[] opOnly = {"pv.addon.groups.*", "pv.addon.groups.join.all", "pv.addon.groups.join.*",
                "pv.addon.groups.flag.permissions", "pv.addon.groups.flag.persistent",
                "pv.addon.groups.kick.all", "pv.addon.groups.delete.all", "group.example", "", "pv.activation.other"};
        for (String node : opOnly) {
            assertFalse(node, GroupPermissions.has(node, false));
            assertTrue(node, GroupPermissions.has(node, true));
        }
    }
}
