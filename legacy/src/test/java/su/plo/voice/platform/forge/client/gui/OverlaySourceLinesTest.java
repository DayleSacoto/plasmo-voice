package su.plo.voice.platform.forge.client.gui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import su.plo.voice.platform.forge.client.groups.ClientGroups;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Overlay Sources lists every source line except the groups one, which the Group HUD covers. */
public class OverlaySourceLinesTest {
    private static VoiceSourceLine line(String name, int weight) {
        return new VoiceSourceLine(name, "pv.activation." + name, "plasmovoice:textures/icons/speaker.png", 1.0, weight,
                null);
    }

    @Test
    public void theGroupsLineHasNoOverlaySourceToggle() {
        VoiceSourceLine groups = line(ClientGroups.NAME, 10);
        assertEquals(ClientGroups.LINE_ID, groups.getId());
        List<VoiceSourceLine> lines = OverlayTab.overlaySourceLines(Arrays.asList(groups, line("proximity", 1)));
        assertEquals(1, lines.size());
        assertEquals("proximity", lines.get(0).getName());
    }

    @Test
    public void otherLinesStayAndKeepTheirWeightOrder() {
        List<VoiceSourceLine> lines = OverlayTab.overlaySourceLines(Arrays.asList(line("addon", 5),
                line(ClientGroups.NAME, 3), line("proximity", 1)));
        assertEquals(2, lines.size());
        assertEquals("proximity", lines.get(0).getName());
        assertEquals("addon", lines.get(1).getName());
    }

    @Test
    public void noLinesMeansNoToggles() {
        assertTrue(OverlayTab.overlaySourceLines(Collections.<VoiceSourceLine>emptyList()).isEmpty());
        assertTrue(OverlayTab.overlaySourceLines(Collections.singletonList(line(ClientGroups.NAME, 1))).isEmpty());
    }
}
