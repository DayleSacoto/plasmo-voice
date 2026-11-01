package su.plo.voice.platform.forge.client.hud;

import org.junit.Test;
import su.plo.voice.platform.forge.client.hud.GroupHudPosition.Anchor;
import su.plo.voice.platform.forge.client.hud.GroupHudPosition.Placement;
import su.plo.voice.platform.forge.client.hud.GroupHudPosition.Point;

import static org.junit.Assert.*;

public class GroupHudPositionTest {
    private static final int SCREEN_W = 427;
    private static final int SCREEN_H = 240;
    private static final int BOX_W = 100;
    private static final int BOX_H = 50;

    private static Point origin(Anchor anchor, int offsetX, int offsetY) {
        return GroupHudPosition.origin(anchor, offsetX, offsetY, BOX_W, BOX_H, SCREEN_W, SCREEN_H);
    }

    private static Placement drag(int x, int y) {
        return GroupHudPosition.fromDrag(x, y, BOX_W, BOX_H, SCREEN_W, SCREEN_H);
    }

    @Test
    public void everyAnchorPlacesItsOwnCornerAtTheOffset() {
        assertEquals(new Point(4, 4), origin(Anchor.TOP_LEFT, 4, 4));
        assertEquals(new Point(SCREEN_W - 4 - BOX_W, 4), origin(Anchor.TOP_RIGHT, -4, 4));
        assertEquals(new Point(4, SCREEN_H - 4 - BOX_H), origin(Anchor.BOTTOM_LEFT, 4, -4));
        assertEquals(new Point(SCREEN_W - 4 - BOX_W, SCREEN_H - 4 - BOX_H), origin(Anchor.BOTTOM_RIGHT, -4, -4));
        // Larger distances from the corner.
        assertEquals(new Point(SCREEN_W - 30 - BOX_W, 20), origin(Anchor.TOP_RIGHT, -30, 20));
        assertEquals(new Point(50, SCREEN_H - 60 - BOX_H), origin(Anchor.BOTTOM_LEFT, 50, -60));
    }

    @Test
    public void defaultIsTheTopRightCornerWithTheHudMargin() {
        assertEquals(Anchor.TOP_RIGHT, GroupHudPosition.DEFAULT.anchor);
        assertEquals(-4, GroupHudPosition.DEFAULT.offsetX);
        assertEquals(4, GroupHudPosition.DEFAULT.offsetY);
        Point point = GroupHudPosition.origin(GroupHudPosition.DEFAULT, BOX_W, BOX_H, SCREEN_W, SCREEN_H);
        // The right edge is 4px from the screen edge, the top edge 4px from the top.
        assertEquals(4, SCREEN_W - (point.x + BOX_W));
        assertEquals(4, point.y);
    }

    @Test
    public void nearestAnchorIsTheQuarterHoldingTheBoxCentre() {
        assertEquals(Anchor.TOP_LEFT, drag(20, 20).anchor);
        assertEquals(Anchor.TOP_RIGHT, drag(300, 20).anchor);
        assertEquals(Anchor.BOTTOM_LEFT, drag(20, 160).anchor);
        assertEquals(Anchor.BOTTOM_RIGHT, drag(300, 160).anchor);
        // The centre decides, not the corner: the box starts left of the middle but its centre is right of it.
        assertEquals(Anchor.TOP_RIGHT, drag(180, 20).anchor);
        // A centre exactly on the midline counts as left / top.
        assertEquals(Anchor.TOP_LEFT, GroupHudPosition.fromDrag(150, 95, BOX_W, BOX_H, 400, 240).anchor);
    }

    @Test
    public void dragThenRenderLandsWhereTheBoxWasDropped() {
        int[][] drops = {{50, 60}, {300, 150}, {200, 100}, {20, 170}, {310, 30}, {40, 40}};
        for (int[] drop : drops) {
            Placement placement = drag(drop[0], drop[1]);
            Point point = GroupHudPosition.origin(placement, BOX_W, BOX_H, SCREEN_W, SCREEN_H);
            assertEquals("x of " + drop[0], drop[0], point.x);
            assertEquals("y of " + drop[1], drop[1], point.y);
        }
        assertEquals(new Placement(Anchor.BOTTOM_RIGHT, -27, -40), drag(300, 150));
        assertEquals(new Placement(Anchor.TOP_LEFT, 50, 60), drag(50, 60));
    }

    @Test
    public void droppingAtTheDefaultSpotGivesTheDefaultPlacement() {
        Point point = GroupHudPosition.origin(GroupHudPosition.DEFAULT, BOX_W, BOX_H, SCREEN_W, SCREEN_H);
        assertEquals(GroupHudPosition.DEFAULT, drag(point.x, point.y));
    }

    @Test
    public void aResizeKeepsTheBoxAnchoredToItsCorner() {
        Placement placement = new Placement(Anchor.BOTTOM_RIGHT, -10, -20);
        Point small = GroupHudPosition.origin(placement, BOX_W, BOX_H, 427, 240);
        Point large = GroupHudPosition.origin(placement, BOX_W, BOX_H, 854, 480);
        assertEquals(10, 427 - (small.x + BOX_W));
        assertEquals(20, 240 - (small.y + BOX_H));
        assertEquals(10, 854 - (large.x + BOX_W));
        assertEquals(20, 480 - (large.y + BOX_H));
        // A left anchored box does not move at all.
        Placement left = new Placement(Anchor.TOP_LEFT, 12, 8);
        assertEquals(GroupHudPosition.origin(left, BOX_W, BOX_H, 427, 240),
                GroupHudPosition.origin(left, BOX_W, BOX_H, 854, 480));
    }

    @Test
    public void guiScaleChangesOnlyTheScaledScreenSize() {
        // GUI scale 2 and 3 on a 854x480 window: the scaled screens are 427x240 and 284x160. The offsets are scaled
        // pixels, so the gap to the anchored edges is the same at both scales.
        Placement placement = new Placement(Anchor.TOP_RIGHT, -4, 4);
        Point scale2 = GroupHudPosition.origin(placement, BOX_W, BOX_H, 427, 240);
        Point scale3 = GroupHudPosition.origin(placement, BOX_W, BOX_H, 284, 160);
        assertEquals(4, 427 - (scale2.x + BOX_W));
        assertEquals(4, 284 - (scale3.x + BOX_W));
        assertEquals(scale2.y, scale3.y);
    }

    @Test
    public void aBoxIsAlwaysFullyOnScreen() {
        int[] offsets = {-100000, -500, -1, 0, 1, 500, 100000};
        for (Anchor anchor : Anchor.values()) {
            for (int offsetX : offsets) {
                for (int offsetY : offsets) {
                    Point point = origin(anchor, offsetX, offsetY);
                    String label = anchor + " " + offsetX + "," + offsetY;
                    assertTrue(label, point.x >= 0 && point.x + BOX_W <= SCREEN_W);
                    assertTrue(label, point.y >= 0 && point.y + BOX_H <= SCREEN_H);
                }
            }
        }
        // A box larger than the screen is pinned to the top left instead of being pushed to a negative origin.
        assertEquals(new Point(0, 0), GroupHudPosition.origin(Anchor.BOTTOM_RIGHT, -4, -4, 600, 300, SCREEN_W, SCREEN_H));
    }

    @Test
    public void clampingDoesNotRewriteTheStoredPlacement() {
        Placement stored = new Placement(Anchor.TOP_RIGHT, 500, -30);
        Point point = GroupHudPosition.origin(stored, BOX_W, BOX_H, SCREEN_W, SCREEN_H);
        assertEquals(new Point(SCREEN_W - BOX_W, 0), point);
        assertEquals(new Placement(Anchor.TOP_RIGHT, 500, -30), stored);
    }

    @Test
    public void invalidStoredValuesRecoverToAnOnScreenPlacement() {
        assertEquals(GroupHudPosition.DEFAULT_ANCHOR, GroupHudPosition.sanitize(null, 1, 2).anchor);
        assertEquals(new Placement(Anchor.TOP_LEFT, 4096, -4096), GroupHudPosition.sanitize(Anchor.TOP_LEFT, 99999, -99999));
        assertEquals(new Placement(Anchor.TOP_LEFT, -4096, 4096),
                GroupHudPosition.sanitize(Anchor.TOP_LEFT, Integer.MIN_VALUE, Integer.MAX_VALUE));
        // Wrong sign: a right anchored box with a positive offset would sit beyond the right edge.
        Placement wrongSign = new Placement(Anchor.TOP_RIGHT, 50, 4);
        assertEquals(new Placement(Anchor.TOP_RIGHT, 0, 4),
                GroupHudPosition.normalize(wrongSign, BOX_W, BOX_H, SCREEN_W, SCREEN_H));
        // An offset beyond the screen comes back to the nearest valid one.
        assertEquals(new Placement(Anchor.BOTTOM_LEFT, 0, 0),
                GroupHudPosition.normalize(new Placement(Anchor.BOTTOM_LEFT, -300, 300), BOX_W, BOX_H, SCREEN_W, SCREEN_H));
        // A valid placement is unchanged.
        assertEquals(GroupHudPosition.DEFAULT,
                GroupHudPosition.normalize(GroupHudPosition.DEFAULT, BOX_W, BOX_H, SCREEN_W, SCREEN_H));
    }

    @Test
    public void edgesWithinTheThresholdSnapToTheMargin() {
        assertEquals(4, drag(0, 100).offsetX);
        assertEquals(4, drag(3, 100).offsetX);
        assertEquals(4, drag(4, 100).offsetX);
        assertEquals(5, drag(5, 100).offsetX);
        // Top edge.
        assertEquals(4, drag(100, 2).offsetY);
        assertEquals(5, drag(100, 5).offsetY);
        // Right edge: a gap of 0..4 becomes 4, 5 stays.
        assertEquals(-4, drag(SCREEN_W - BOX_W, 100).offsetX);
        assertEquals(-4, drag(SCREEN_W - BOX_W - 2, 100).offsetX);
        assertEquals(-5, drag(SCREEN_W - BOX_W - 5, 100).offsetX);
        // Bottom edge.
        assertEquals(-4, drag(100, SCREEN_H - BOX_H).offsetY);
        assertEquals(-6, drag(100, SCREEN_H - BOX_H - 6).offsetY);
    }

    @Test
    public void snappingNeverJumpsAcrossLargeDistances() {
        for (int x = 5; x < SCREEN_W - BOX_W - 5; x++) {
            Point point = GroupHudPosition.origin(drag(x, 100), BOX_W, BOX_H, SCREEN_W, SCREEN_H);
            assertEquals(x, point.x);
        }
        // Off screen drops are clamped first, then snapped to the margin.
        assertEquals(new Placement(Anchor.TOP_LEFT, 4, 4), drag(-500, -500));
        assertEquals(new Placement(Anchor.BOTTOM_RIGHT, -4, -4), drag(5000, 5000));
    }

    @Test
    public void resetRestoresTheDefaultFromAnyPlacement() {
        Placement moved = drag(30, 150);
        assertNotEquals(GroupHudPosition.DEFAULT, moved);
        assertEquals(new Point(SCREEN_W - 4 - BOX_W, 4),
                GroupHudPosition.origin(GroupHudPosition.DEFAULT, BOX_W, BOX_H, SCREEN_W, SCREEN_H));
    }
}
