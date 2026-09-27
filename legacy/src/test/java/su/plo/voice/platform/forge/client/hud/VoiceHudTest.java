package su.plo.voice.platform.forge.client.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Head UVs are in 64 pixel wide skin units; the height follows the bound skin texture. */
public class VoiceHudTest {
    @Test
    public void skinHeightFollowsTheTexture() {
        assertEquals(32F, VoiceHud.skinTextureHeight(64, 32), 0F); // vanilla 1.7.10 skin
        assertEquals(64F, VoiceHud.skinTextureHeight(64, 64), 0F); // modern skin (GTNH SimpleSkinBackport)
        assertEquals(64F, VoiceHud.skinTextureHeight(128, 128), 0F); // HD modern skin
        assertEquals(32F, VoiceHud.skinTextureHeight(0, 0), 0F); // nothing uploaded yet: legacy layout
    }
}
