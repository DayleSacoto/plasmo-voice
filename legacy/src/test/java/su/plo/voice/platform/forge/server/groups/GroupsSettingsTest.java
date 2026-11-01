package su.plo.voice.platform.forge.server.groups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GroupsSettingsTest {
    @Test
    public void defaultsMatchUpstream() {
        GroupsSettings s = GroupsSettings.defaults();
        assertTrue(s.isEnabled());
        assertEquals(10, s.getActivationWeight());
        assertEquals(10, s.getSourceLineWeight());
        assertEquals("%player%'s group", s.getDefaultGroupNameFormat());
        assertEquals(3, s.getMinimumNameLength());
        assertEquals(32, s.getMaximumNameLength());
    }
}
