package su.plo.voice.platform.forge.client.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.platform.forge.client.hud.HudOptions.GroupOverlayVisibility;
import su.plo.voice.platform.forge.network.groups.GroupsState.Member;

import static org.junit.Assert.*;

/** Group Overlay visibility modes: Off, Always, When Other Members Online. */
public class GroupHudVisibilityTest {
    private static final UUID SELF = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID THIRD = UUID.randomUUID();

    private static Member member(UUID id, String name, boolean online) {
        return new Member(id, name, online);
    }

    private static boolean visible(GroupOverlayVisibility mode, Member... members) {
        return GroupHudModel.visible(mode, Arrays.asList(members), SELF);
    }

    @Test
    public void offNeverShows() {
        assertFalse(visible(GroupOverlayVisibility.OFF, member(SELF, "Me", true)));
        assertFalse(visible(GroupOverlayVisibility.OFF, member(SELF, "Me", true), member(OTHER, "Other", true)));
    }

    @Test
    public void alwaysShowsWhileInAGroupLikeBefore() {
        assertTrue(visible(GroupOverlayVisibility.ALWAYS, member(SELF, "Me", true)));
        assertTrue(visible(GroupOverlayVisibility.ALWAYS, member(SELF, "Me", true), member(OTHER, "Other", false)));
        assertTrue(visible(GroupOverlayVisibility.ALWAYS, member(SELF, "Me", true), member(OTHER, "Other", true)));
        // a stored value from nowhere behaves like the default
        assertTrue(visible(null, member(SELF, "Me", true)));
    }

    @Test
    public void whenOthersOnlineHidesWithOnlyTheLocalPlayer() {
        assertFalse(visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, member(SELF, "Me", true)));
        assertFalse(GroupHudModel.visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, Collections.<Member>emptyList(), SELF));
    }

    @Test
    public void whenOthersOnlineShowsWithAnotherOnlineMember() {
        assertTrue(visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, member(SELF, "Me", true), member(OTHER, "Other", true)));
        // the local player's own row is not required for that
        assertTrue(visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, member(OTHER, "Other", true)));
    }

    @Test
    public void offlineMembersDoNotCount() {
        assertFalse(visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, member(SELF, "Me", true),
                member(OTHER, "Other", false), member(THIRD, "Third", false)));
        assertTrue(visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, member(SELF, "Me", true),
                member(OTHER, "Other", false), member(THIRD, "Third", true)));
    }

    @Test
    public void followsMembersJoiningAndLeaving() {
        List<Member> group = new ArrayList<>();
        group.add(member(SELF, "Me", true));
        assertFalse(GroupHudModel.visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, group, SELF));

        // another member joins: shown on the next frame
        group.add(member(OTHER, "Other", true));
        assertTrue(GroupHudModel.visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, group, SELF));

        // that member goes offline: hidden at once
        group.set(1, member(OTHER, "Other", false));
        assertFalse(GroupHudModel.visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, group, SELF));

        // comes back online, then leaves the group: shown, then hidden
        group.set(1, member(OTHER, "Other", true));
        assertTrue(GroupHudModel.visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, group, SELF));
        group.remove(1);
        assertFalse(GroupHudModel.visible(GroupOverlayVisibility.WHEN_OTHERS_ONLINE, group, SELF));
    }

    @Test
    public void oldSwitchMapsToTheModes() {
        assertEquals(GroupOverlayVisibility.ALWAYS, GroupOverlayVisibility.fromEnabled(true));
        assertEquals(GroupOverlayVisibility.OFF, GroupOverlayVisibility.fromEnabled(false));
    }
}
