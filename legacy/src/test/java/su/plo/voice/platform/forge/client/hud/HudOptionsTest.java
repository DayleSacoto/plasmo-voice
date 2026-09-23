package su.plo.voice.platform.forge.client.hud;

import org.junit.Test;

import static org.junit.Assert.*;

public class HudOptionsTest {
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;

    @Test
    public void activationIconFollowsUpstreamAnchors() {
        assertEquals(16, HudOptions.IconPosition.TOP_LEFT.iconX(WIDTH));
        assertEquals(16, HudOptions.IconPosition.TOP_LEFT.iconY(HEIGHT));
        assertEquals(WIDTH / 2 - 8, HudOptions.IconPosition.BOTTOM_CENTER.iconX(WIDTH));
        assertEquals(HEIGHT - 36 - 16, HudOptions.IconPosition.BOTTOM_CENTER.iconY(HEIGHT));
        assertEquals(WIDTH - 16 - 16, HudOptions.IconPosition.BOTTOM_RIGHT.iconX(WIDTH));
        assertEquals(HEIGHT - 16 - 16, HudOptions.IconPosition.BOTTOM_RIGHT.iconY(HEIGHT));
    }

    @Test
    public void overlayStartsAtItsCorner() {
        assertEquals(4, HudOptions.OverlayPosition.TOP_LEFT.startX(WIDTH));
        assertEquals(4, HudOptions.OverlayPosition.TOP_LEFT.startY(HEIGHT));
        assertEquals(WIDTH - 4, HudOptions.OverlayPosition.BOTTOM_RIGHT.startX(WIDTH));
        assertEquals(HEIGHT - 4, HudOptions.OverlayPosition.BOTTOM_RIGHT.startY(HEIGHT));
        assertTrue(HudOptions.OverlayPosition.BOTTOM_RIGHT.isRight());
        assertTrue(HudOptions.OverlayPosition.BOTTOM_LEFT.isBottom());
        assertFalse(HudOptions.OverlayPosition.TOP_RIGHT.isBottom());
    }

    @Test
    public void overlayStyles() {
        assertTrue(HudOptions.OverlayStyle.NAME_SKIN.hasName() && HudOptions.OverlayStyle.NAME_SKIN.hasSkin());
        assertFalse(HudOptions.OverlayStyle.SKIN.hasName());
        assertFalse(HudOptions.OverlayStyle.NAME.hasSkin());
    }
}
