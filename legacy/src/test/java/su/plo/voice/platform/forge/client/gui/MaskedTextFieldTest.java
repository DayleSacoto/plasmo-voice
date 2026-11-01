package su.plo.voice.platform.forge.client.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Masked (password) fields draw only asterisks and never put their text on the clipboard. */
public class MaskedTextFieldTest {
    @Test
    public void maskHasOneAsteriskPerCharacterAndHidesTheText() {
        assertEquals("", TextFieldWidget.mask(0));
        assertEquals("******", TextFieldWidget.mask("secret".length()));
        assertFalse(TextFieldWidget.mask(6).contains("s"));
    }

    @Test
    public void copyAndCutAreBlockedOnlyWhenMasked() {
        assertTrue(TextFieldWidget.blocksClipboardWrite(true, true, false));
        assertTrue(TextFieldWidget.blocksClipboardWrite(true, false, true));
        assertFalse(TextFieldWidget.blocksClipboardWrite(true, false, false));
        assertFalse(TextFieldWidget.blocksClipboardWrite(false, true, true));
    }

    @Test
    public void copyAndCutAreRecognisedByControlCharacterOrCtrlKey() {
        // Vanilla GuiTextField copies on char 3 and cuts on char 24, whatever the key code.
        assertTrue(TextFieldWidget.isCopy((char) 3, 0, false));
        assertTrue(TextFieldWidget.isCut((char) 24, 0, false));
        assertTrue(TextFieldWidget.isCopy('c', org.lwjgl.input.Keyboard.KEY_C, true));
        assertTrue(TextFieldWidget.isCut('x', org.lwjgl.input.Keyboard.KEY_X, true));
        assertFalse(TextFieldWidget.isCopy('c', org.lwjgl.input.Keyboard.KEY_C, false));
        assertFalse(TextFieldWidget.isCut('a', org.lwjgl.input.Keyboard.KEY_A, true));
    }
}
