package su.plo.voice.platform.forge.client;

import net.minecraft.event.ClickEvent;
import net.minecraft.util.IChatComponent;
import org.junit.Test;

import static org.junit.Assert.*;

public class UpdateNotificationTest {
    private static final String URL = "https://github.com/DayleSacoto/plasmo-voice/releases/tag/2.1.17-forge-1.7.10-r3";

    /** The release page is a click-to-open link (vanilla asks before opening it); nothing opens by itself. */
    @Test
    public void releasePageIsAClickableLink() {
        IChatComponent message = UpdateNotification.message("[Plasmo Voice] New Forge 1.7.10 version available: "
                + "2.1.17-r3 (current: 2.1.17-r2)", URL);
        assertEquals("[Plasmo Voice] New Forge 1.7.10 version available: 2.1.17-r3 (current: 2.1.17-r2) " + URL,
                message.getUnformattedText());
        IChatComponent link = (IChatComponent) message.getSiblings().get(0);
        ClickEvent click = link.getChatStyle().getChatClickEvent();
        assertEquals(ClickEvent.Action.OPEN_URL, click.getAction());
        assertEquals(URL, click.getValue());
        assertNull(message.getChatStyle().getChatClickEvent());
    }
}
