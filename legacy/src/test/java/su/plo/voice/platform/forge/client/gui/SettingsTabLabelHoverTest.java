package su.plo.voice.platform.forge.client.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A setting's description shows only over its rendered label text, not over the rest of the row:
 * not before or after the text, not over the label icon, the control, the reset button or the row padding.
 */
public class SettingsTabLabelHoverTest {
    /** Rendered text "HRTF": starts at x 100, 25 px wide, drawn at y 50, font height 9. */
    private static final int X = 100;
    private static final int Y = 50;
    private static final int WIDTH = 25;
    private static final int HEIGHT = 9;
    private static final int TOLERANCE = SettingsTab.LABEL_HOVER_TOLERANCE;

    private static boolean over(int mouseX, int mouseY) {
        return SettingsTab.isOverLabel(mouseX, mouseY, X, Y, WIDTH, HEIGHT);
    }

    @Test
    public void insideLabel() {
        assertTrue(over(X + 10, Y + 4));
    }

    @Test
    public void horizontalInterior() {
        for (int x = X; x < X + WIDTH; x++) assertTrue("x " + x, over(x, Y + 4));
    }

    @Test
    public void leftOfLabel() {
        assertFalse("just before the text", over(X - 1, Y + 4));
        assertFalse("label icon / row start", over(X - 20, Y + 4));
        assertFalse("left of the settings list", over(0, Y + 4));
    }

    @Test
    public void rightOfRenderedText() {
        assertFalse(over(X + WIDTH, Y + 4));
        assertFalse(over(X + WIDTH + 1, Y + 4));
    }

    @Test
    public void emptySpaceBetweenShortLabelAndControl() {
        // The control of a default row starts about 150 px after the label start.
        int controlX = X + 150;
        for (int x = X + WIDTH; x < controlX; x++) assertFalse("x " + x, over(x, Y + 4));
        assertFalse("control itself", over(controlX + 10, Y + 4));
    }

    @Test
    public void aboveText() {
        assertFalse(over(X + 10, Y - TOLERANCE - 1));
        assertFalse("row padding above", over(X + 10, Y - 7));
    }

    @Test
    public void belowText() {
        assertFalse(over(X + 10, Y + HEIGHT + TOLERANCE));
        assertFalse("row padding below", over(X + 10, Y + HEIGHT + 7));
    }

    @Test
    public void zeroWidthLabel() {
        assertFalse(SettingsTab.isOverLabel(X, Y + 4, X, Y, 0, HEIGHT));
        assertFalse(SettingsTab.isOverLabel(X, Y + 4, X, Y, -5, HEIGHT));
    }

    /** Left edge inclusive, right edge exclusive, no horizontal tolerance. */
    @Test
    public void horizontalBoundaries() {
        assertTrue(over(X, Y + 4));
        assertTrue(over(X + WIDTH - 1, Y + 4));
        assertFalse(over(X - 1, Y + 4));
        assertFalse(over(X + WIDTH, Y + 4));
    }

    /** The text rows plus a small tolerance above and below. */
    @Test
    public void verticalBoundaries() {
        assertTrue(over(X + 10, Y));
        assertTrue(over(X + 10, Y + HEIGHT - 1));
        assertTrue(over(X + 10, Y - TOLERANCE));
        assertTrue(over(X + 10, Y + HEIGHT - 1 + TOLERANCE));
        assertFalse(over(X + 10, Y - TOLERANCE - 1));
        assertFalse(over(X + 10, Y + HEIGHT + TOLERANCE));
    }

    @Test
    public void toleranceStaysSmall() {
        assertTrue(TOLERANCE >= 0 && TOLERANCE <= 2);
    }
}
