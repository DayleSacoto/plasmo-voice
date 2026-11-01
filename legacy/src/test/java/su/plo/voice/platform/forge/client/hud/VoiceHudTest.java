package su.plo.voice.platform.forge.client.hud;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Head UVs are in 64 pixel wide skin units; the height follows the bound skin texture. */
public class VoiceHudTest {
    private static VoiceActivation activation(String name, int weight) {
        return new VoiceActivation(name, "pv.activation." + name, "plasmovoice:textures/icons/" + name + ".png",
                Collections.<Integer>emptyList(), 0, VoiceActivation.PROXIMITY_NAME.equals(name), false, true, null, weight);
    }

    @Test
    public void activationIconIsTheHeaviestActiveActivation() {
        VoiceActivation proximity = activation("proximity", 0);
        VoiceActivation groups = activation("groups", 10);
        VoiceActivation radio = activation("radio", 10);
        List<VoiceActivation> all = Arrays.asList(proximity, groups, radio);

        assertNull(VoiceHud.highestActive(all, Collections.<UUID>emptySet()));
        assertSame(proximity, VoiceHud.highestActive(all, Collections.singleton(proximity.getId())));
        // Talking in a group while proximity sends too: the groups icon.
        assertSame(groups, VoiceHud.highestActive(all, new HashSet<>(Arrays.asList(proximity.getId(), groups.getId()))));
        assertSame(groups, VoiceHud.highestActive(all, Collections.singleton(groups.getId())));
        // Equal weights follow the activation order: by name.
        assertSame(groups, VoiceHud.highestActive(all, new HashSet<>(Arrays.asList(radio.getId(), groups.getId()))));
        // An id the config does not know shows nothing.
        assertNull(VoiceHud.highestActive(all, Collections.singleton(UUID.randomUUID())));
    }

    @Test
    public void skinHeightFollowsTheTexture() {
        assertEquals(32F, VoiceHud.skinTextureHeight(64, 32), 0F); // vanilla 1.7.10 skin
        assertEquals(64F, VoiceHud.skinTextureHeight(64, 64), 0F); // modern skin (GTNH SimpleSkinBackport)
        assertEquals(64F, VoiceHud.skinTextureHeight(128, 128), 0F); // HD modern skin
        assertEquals(32F, VoiceHud.skinTextureHeight(0, 0), 0F); // nothing uploaded yet: legacy layout
    }
}
