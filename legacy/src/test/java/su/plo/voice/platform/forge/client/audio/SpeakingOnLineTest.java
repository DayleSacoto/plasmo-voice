package su.plo.voice.platform.forge.client.audio;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;

import static org.junit.Assert.assertEquals;

/**
 * The group speaking outline (Groups tab and Group HUD) is driven by {@link ClientVoiceSources#speakingOn}: only
 * activated sources on the groups line count, so proximity-only speech never outlines a head.
 */
public class SpeakingOnLineTest {
    private static final UUID GROUPS_LINE = UUID.randomUUID();
    private static final UUID PROXIMITY_LINE = UUID.randomUUID();

    private final ClientVoiceSources sources = new ClientVoiceSources(() -> false, info -> false, id -> { },
            () -> false, () -> 3);

    private UUID source(UUID line, UUID player) {
        UUID sourceId = UUID.randomUUID();
        sources.updateSourceInfo(new PlayerSourceInfo("plasmovoice", sourceId, line, null, (byte) 0,
                new OpusDecoderInfo(), false, true, 0, new VoicePlayerInfo(player, "p", false, false, false)));
        return sourceId;
    }

    @SuppressWarnings("unchecked")
    private void setActivated(UUID sourceId, boolean activated) throws Exception {
        Field field = ClientVoiceSources.class.getDeclaredField("sources");
        field.setAccessible(true);
        ((Map<UUID, VoiceSource>) field.get(sources)).get(sourceId).activated = activated;
    }

    @Test
    public void onlyActivatedGroupLineSourcesCount() throws Exception {
        UUID groupSpeaker = UUID.randomUUID();
        UUID proximitySpeaker = UUID.randomUUID();
        UUID groupSource = source(GROUPS_LINE, groupSpeaker);
        UUID proximitySource = source(PROXIMITY_LINE, proximitySpeaker);

        // proximity-only speech: no group speaker
        setActivated(proximitySource, true);
        assertEquals(Collections.emptySet(), sources.speakingOn(GROUPS_LINE));

        // group speech
        setActivated(groupSource, true);
        assertEquals(Collections.singleton(groupSpeaker), sources.speakingOn(GROUPS_LINE));

        // speaking ended: the outline goes away
        setActivated(groupSource, false);
        assertEquals(Collections.emptySet(), sources.speakingOn(GROUPS_LINE));
    }

    @Test
    public void aPlayerTalkingOnlyInProximityIsNotAGroupSpeakerEvenWithAGroupSource() throws Exception {
        UUID player = UUID.randomUUID();
        source(GROUPS_LINE, player);
        UUID proximitySource = source(PROXIMITY_LINE, player);
        setActivated(proximitySource, true);
        assertEquals(Collections.emptySet(), sources.speakingOn(GROUPS_LINE));
    }
}
