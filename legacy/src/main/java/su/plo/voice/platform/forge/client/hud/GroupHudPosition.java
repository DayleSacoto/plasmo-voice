package su.plo.voice.platform.forge.client.hud;

/**
 * Where the Group HUD sits, as pure math on scaled GUI pixels (no Minecraft types, so it is unit tested).
 *
 * <p>The stored model is an {@link Anchor} plus an offset: the box corner that matches the anchor is placed at
 * {@code screenCorner + (offsetX, offsetY)}. TOP_RIGHT with (-4, 4) puts the box's right edge 4px from the right
 * screen edge and its top edge 4px from the top; BOTTOM_LEFT with (4, -4) puts the left edge 4px in and the bottom
 * edge 4px up. Offsets are therefore positive towards the screen centre on the left/top and negative on the
 * right/bottom, and they stay valid when the window or the GUI scale changes. Scaled pixels are the only unit used.
 *
 * <p>Stored values are never rewritten at render time: {@link #origin} clamps so the box is fully on screen.
 */
public final class GroupHudPosition {
    public enum Anchor {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public boolean isRight() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean isBottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }

    /** Distance the default and snapped positions keep from a screen edge, like the other HUD elements. */
    public static final int MARGIN = 4;
    /** A dragged box whose gap to a screen edge is at most this snaps to {@link #MARGIN}. */
    public static final int SNAP_DISTANCE = 4;
    /** Largest stored offset magnitude; anything beyond is garbage from a hand edited file. */
    public static final int MAX_OFFSET = 4096;

    public static final Anchor DEFAULT_ANCHOR = Anchor.TOP_RIGHT;
    public static final int DEFAULT_OFFSET_X = -MARGIN;
    public static final int DEFAULT_OFFSET_Y = MARGIN;
    public static final Placement DEFAULT = new Placement(DEFAULT_ANCHOR, DEFAULT_OFFSET_X, DEFAULT_OFFSET_Y);

    private GroupHudPosition() {
    }

    /** Anchor and offsets, immutable. Construct through {@link #sanitize} when the values are untrusted. */
    public static final class Placement {
        public final Anchor anchor;
        public final int offsetX;
        public final int offsetY;

        public Placement(Anchor anchor, int offsetX, int offsetY) {
            this.anchor = anchor;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Placement)) return false;
            Placement placement = (Placement) other;
            return anchor == placement.anchor && offsetX == placement.offsetX && offsetY == placement.offsetY;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * anchor.hashCode() + offsetX) + offsetY;
        }

        @Override
        public String toString() {
            return anchor + "(" + offsetX + ", " + offsetY + ")";
        }
    }

    /** Top left corner of the box on screen, scaled pixels. */
    public static final class Point {
        public final int x;
        public final int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Point && ((Point) other).x == x && ((Point) other).y == y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }

    /** A missing anchor becomes the default one and offsets are limited to +-{@link #MAX_OFFSET}. */
    public static Placement sanitize(Anchor anchor, int offsetX, int offsetY) {
        return new Placement(anchor == null ? DEFAULT_ANCHOR : anchor, clampOffset(offsetX), clampOffset(offsetY));
    }

    public static int clampOffset(int offset) {
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
    }

    /**
     * The top left corner of the box for a stored placement, clamped so the whole box is on screen (a box larger
     * than the screen is pinned to the top left). The placement itself is not changed.
     */
    public static Point origin(Anchor anchor, int offsetX, int offsetY, int boxWidth, int boxHeight,
                               int screenWidth, int screenHeight) {
        Anchor safe = anchor == null ? DEFAULT_ANCHOR : anchor;
        long x = safe.isRight() ? (long) screenWidth + clampOffset(offsetX) - boxWidth : clampOffset(offsetX);
        long y = safe.isBottom() ? (long) screenHeight + clampOffset(offsetY) - boxHeight : clampOffset(offsetY);
        return new Point(clamp(x, 0, Math.max(0, screenWidth - boxWidth)),
                clamp(y, 0, Math.max(0, screenHeight - boxHeight)));
    }

    public static Point origin(Placement placement, int boxWidth, int boxHeight, int screenWidth, int screenHeight) {
        return origin(placement.anchor, placement.offsetX, placement.offsetY, boxWidth, boxHeight, screenWidth,
                screenHeight);
    }

    /**
     * The placement for a box dropped with its top left corner at (boxX, boxY): snapped to the margin when it is
     * within {@link #SNAP_DISTANCE} of an edge, kept on screen, anchored to the screen quarter that holds the box
     * centre (a centre exactly on a screen midline counts as left / top).
     */
    public static Placement fromDrag(int boxX, int boxY, int boxWidth, int boxHeight, int screenWidth, int screenHeight) {
        int x = clamp(snap(clamp(boxX, 0, Math.max(0, screenWidth - boxWidth)), boxWidth, screenWidth), 0, Math.max(0, screenWidth - boxWidth));
        int y = clamp(snap(clamp(boxY, 0, Math.max(0, screenHeight - boxHeight)), boxHeight, screenHeight), 0, Math.max(0, screenHeight - boxHeight));
        boolean right = 2L * x + boxWidth > screenWidth;
        boolean bottom = 2L * y + boxHeight > screenHeight;
        Anchor anchor = bottom ? (right ? Anchor.BOTTOM_RIGHT : Anchor.BOTTOM_LEFT)
                : (right ? Anchor.TOP_RIGHT : Anchor.TOP_LEFT);
        int offsetX = right ? x + boxWidth - screenWidth : x;
        int offsetY = bottom ? y + boxHeight - screenHeight : y;
        return new Placement(anchor, offsetX, offsetY);
    }

    /**
     * The same anchor with offsets rewritten to where the box really renders, i.e. the placement an on-screen box
     * would have had; used to recover from stale stored values.
     */
    public static Placement normalize(Placement placement, int boxWidth, int boxHeight, int screenWidth, int screenHeight) {
        Point point = origin(placement, boxWidth, boxHeight, screenWidth, screenHeight);
        int offsetX = placement.anchor.isRight() ? point.x + boxWidth - screenWidth : point.x;
        int offsetY = placement.anchor.isBottom() ? point.y + boxHeight - screenHeight : point.y;
        return new Placement(placement.anchor, offsetX, offsetY);
    }

    /** Snaps one axis: the gap to the near edge, else to the far edge, becomes {@link #MARGIN} when it is small. */
    private static int snap(int position, int size, int screen) {
        if (position <= SNAP_DISTANCE) return MARGIN;
        int farGap = screen - (position + size);
        if (farGap <= SNAP_DISTANCE) return screen - size - MARGIN;
        return position;
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
