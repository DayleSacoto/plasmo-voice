package su.plo.voice.platform.forge.client.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The search hint follows GTNH NEI: shown only for an empty box that is not being edited. */
public class TextFieldWidgetTest {
    @Test
    public void hintOnlyWhileEmptyAndUnfocused() {
        assertTrue("empty, unfocused", TextFieldWidget.showsSuggestion(false, ""));
        assertFalse("empty, focused", TextFieldWidget.showsSuggestion(true, ""));
        assertFalse("typing", TextFieldWidget.showsSuggestion(true, "Sac"));
        assertFalse("cleared while focused", TextFieldWidget.showsSuggestion(true, ""));
        assertTrue("focus lost while empty", TextFieldWidget.showsSuggestion(false, ""));
        assertFalse("unfocused with a query", TextFieldWidget.showsSuggestion(false, "Sac"));
    }
}
