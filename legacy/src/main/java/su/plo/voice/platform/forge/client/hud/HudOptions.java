package su.plo.voice.platform.forge.client.hud;

/** Upstream overlay config enums (api IconPosition, OverlayPosition, OverlayStyle, OverlaySourceState). */
public final class HudOptions {
    private HudOptions() {
    }

    /** Activation icon anchor; null x centers, negative values count from the right or bottom edge. */
    public enum IconPosition {
        TOP_LEFT(16, 16, "top_left"),
        TOP_CENTER(null, 16, "top_center"),
        TOP_RIGHT(-16, 16, "top_right"),
        BOTTOM_LEFT(16, -16, "bottom_left"),
        BOTTOM_CENTER(null, -36, "bottom_center"),
        BOTTOM_RIGHT(-16, -16, "bottom_right");

        final Integer x;
        final int y;
        public final String translation;

        IconPosition(Integer x, int y, String name) {
            this.x = x;
            this.y = y;
            this.translation = "gui.plasmovoice.overlay.hud_position." + name;
        }

        /** Upstream HudIconRenderer.calcIconX for a 16px icon. */
        public int iconX(int screenWidth) {
            if (x == null) return screenWidth / 2 - 8;
            return x < 0 ? screenWidth + x - 16 : x;
        }

        public int iconY(int screenHeight) {
            return y < 0 ? screenHeight + y - 16 : y;
        }
    }

    public enum OverlayPosition {
        TOP_LEFT(4, 4, "top_left"),
        TOP_RIGHT(-4, 4, "top_right"),
        BOTTOM_LEFT(4, -4, "bottom_left"),
        BOTTOM_RIGHT(-4, -4, "bottom_right");

        final int x;
        final int y;
        public final String translation;

        OverlayPosition(int x, int y, String name) {
            this.x = x;
            this.y = y;
            this.translation = "gui.plasmovoice.overlay.hud_position." + name;
        }

        public boolean isRight() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean isBottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }

        /** Upstream OverlayRenderer.calcPositionX/Y. */
        public int startX(int screenWidth) {
            return x < 0 ? screenWidth + x : x;
        }

        public int startY(int screenHeight) {
            return y < 0 ? screenHeight + y : y;
        }
    }

    public enum OverlayStyle {
        NAME_SKIN("name_skin"),
        SKIN("skin"),
        NAME("name");

        public final String translation;

        OverlayStyle(String name) {
            this.translation = "gui.plasmovoice.overlay.style." + name;
        }

        public boolean hasName() {
            return this != SKIN;
        }

        public boolean hasSkin() {
            return this != NAME;
        }
    }

    /** Per source line; lines without a player list only use OFF and ON, which is what this backport's server sends. */
    public enum OverlaySourceState {
        OFF,
        ON,
        WHEN_TALKING,
        ALWAYS,
        NEVER
    }
}
