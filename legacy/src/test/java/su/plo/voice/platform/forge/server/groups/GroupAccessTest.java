package su.plo.voice.platform.forge.server.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.Test;

import su.plo.voice.platform.forge.server.groups.GroupAccess.Result;

public class GroupAccessTest {
    private static final Predicate<String> NOTHING = node -> false;
    private final UUID player = UUID.randomUUID();

    private static Predicate<String> has(String... nodes) {
        Set<String> set = new HashSet<>(Arrays.asList(nodes));
        return set::contains;
    }

    private static Group group(String password, String... permissions) {
        Group g = new Group(UUID.randomUUID(), "g");
        g.setPassword(password);
        g.setPermissions(new HashSet<>(Arrays.asList(permissions)));
        return g;
    }

    @Test
    public void openGroupAllowsEveryone() {
        assertEquals(Result.OK, GroupAccess.checkJoin(group(null), player, null, NOTHING));
    }

    @Test
    public void passwordCorrectWrongMissing() {
        Group g = group("secret");
        assertEquals(Result.OK, GroupAccess.checkJoin(g, player, "secret", NOTHING));
        assertEquals(Result.WRONG_PASSWORD, GroupAccess.checkJoin(g, player, "Secret", NOTHING));
        assertEquals(Result.WRONG_PASSWORD, GroupAccess.checkJoin(g, player, "", NOTHING));
        assertEquals(Result.PASSWORD_MISSING, GroupAccess.checkJoin(g, player, null, NOTHING));
    }

    @Test
    public void passwordIgnoredWhenGroupHasNone() {
        assertEquals(Result.OK, GroupAccess.checkJoin(group(null), player, "whatever", NOTHING));
    }

    @Test
    public void requiredPermissionNodes() {
        Group single = group(null, "group.a");
        assertEquals(Result.NO_PERMISSION, GroupAccess.checkJoin(single, player, null, NOTHING));
        assertEquals(Result.OK, GroupAccess.checkJoin(single, player, null, has("group.a")));

        Group multi = group(null, "group.a", "group.b");
        assertEquals(Result.NO_PERMISSION, GroupAccess.checkJoin(multi, player, null, has("group.c")));
        assertEquals(Result.OK, GroupAccess.checkJoin(multi, player, null, has("group.b")));
    }

    @Test
    public void orderBannedThenPermissionThenPassword() {
        Group g = group("pw", "group.a");
        assertEquals(Result.NO_PERMISSION, GroupAccess.checkJoin(g, player, null, NOTHING));
        assertEquals(Result.PASSWORD_MISSING, GroupAccess.checkJoin(g, player, null, has("group.a")));
        g.ban(new GroupProfile(player, "P"));
        assertEquals(Result.BANNED, GroupAccess.checkJoin(g, player, "pw", has("group.a")));
    }

    @Test
    public void joinAllJoinStarAndWildcardBypassEverything() {
        Group g = group("pw", "group.a");
        g.ban(new GroupProfile(player, "P"));
        assertEquals(Result.BANNED, GroupAccess.checkJoin(g, player, "pw", has("pv.addon.groups.join")));
        assertEquals(Result.OK, GroupAccess.checkJoin(g, player, null, has("pv.addon.groups.join.all")));
        assertEquals(Result.OK, GroupAccess.checkJoin(g, player, null, has("pv.addon.groups.join.*")));
        assertEquals(Result.OK, GroupAccess.checkJoin(g, player, null, has("pv.addon.groups.*")));
    }

    @Test
    public void groupHasPermissionWithEmptyFilter() {
        Group g = group(null);
        assertEquals(Collections.emptySet(), g.getPermissions());
        assertTrue(g.hasPermission(NOTHING));
    }
}
