package su.plo.voice.platform.forge.client.hud;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The head style rule shared by the Groups tab and the Group HUD. */
public class PlayerHeadsTest {
    @Test
    public void groupSpeakingOnlineMembersGetTheWhiteOutline() {
        PlayerHeads.Style speaking = PlayerHeads.Style.of(true, true);
        assertTrue(speaking.outlined);
        assertFalse(speaking.dimmed);
    }

    @Test
    public void silentOnlineMembersAreNormalHeads() {
        PlayerHeads.Style silent = PlayerHeads.Style.of(true, false);
        assertFalse(silent.outlined);
        assertFalse(silent.dimmed);
    }

    @Test
    public void offlineMembersAreDimmedAndNeverOutlined() {
        assertTrue(PlayerHeads.Style.of(false, false).dimmed);
        assertFalse(PlayerHeads.Style.of(false, true).outlined);
        assertTrue(PlayerHeads.Style.of(false, true).dimmed);
    }

    @Test
    public void theOutlineIsThinAndMatchesTheHudSlot() {
        assertTrue(PlayerHeads.OUTLINE >= 1 && PlayerHeads.OUTLINE <= 2);
        assertTrue(GroupHudModel.OUTLINE == PlayerHeads.OUTLINE);
        assertTrue(PlayerHeads.OUTLINE_COLOR == 0xFFFFFFFF);
    }
}
