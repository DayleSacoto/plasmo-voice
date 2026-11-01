package su.plo.voice.platform.forge.client.audio;

import java.util.UUID;

import su.plo.slib.api.entity.player.McGameProfile;
import su.plo.voice.proto.data.audio.source.DirectSourceInfo;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.SourceInfo;

/**
 * The player a source speaks for: the player of a {@link PlayerSourceInfo}, or the sender of a
 * {@link DirectSourceInfo} (upstream group voice arrives as direct sources on the groups line). Null for the rest.
 */
public final class SourcePlayer {
    private SourcePlayer() {
    }

    public static UUID id(SourceInfo info) {
        if (info instanceof PlayerSourceInfo) return ((PlayerSourceInfo) info).getPlayerInfo().getPlayerId();
        McGameProfile sender = sender(info);
        return sender == null ? null : sender.getId();
    }

    /** The nick the server reported for the player, or null. */
    public static String name(SourceInfo info) {
        if (info instanceof PlayerSourceInfo) return ((PlayerSourceInfo) info).getPlayerInfo().getPlayerNick();
        McGameProfile sender = sender(info);
        return sender == null ? null : sender.getName();
    }

    private static McGameProfile sender(SourceInfo info) {
        return info instanceof DirectSourceInfo ? ((DirectSourceInfo) info).getSender() : null;
    }
}
