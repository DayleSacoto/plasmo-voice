package su.plo.voice.platform.forge.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.SourceInfo;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Remote voice sources of one server configuration (upstream VoiceClientSourceManager).
 * Source info arrives on the client thread, audio on the UDP worker; {@link VoicePlayback} plays and releases them.
 */
@SideOnly(Side.CLIENT)
public final class ClientVoiceSources {
    private static final long SOURCE_INFO_REQUEST_INTERVAL_MS = 1_000L;

    private final BooleanSupplier voiceDisabled;
    private final Predicate<SourceInfo> muted;
    private final Consumer<UUID> sourceInfoRequester;
    private final Map<UUID, VoiceSource> sources = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequestById = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ClientVoiceSources(BooleanSupplier voiceDisabled, Predicate<SourceInfo> muted, Consumer<UUID> sourceInfoRequester) {
        this.voiceDisabled = voiceDisabled;
        this.muted = muted;
        this.sourceInfoRequester = sourceInfoRequester;
    }

    public void updateSourceInfo(SourceInfo info) {
        if (closed) return;
        if (VoiceDebug.CLIENT.enabled()) {
            VoiceDebug.CLIENT.log(Category.SOURCE, "source info {}: source={}, player={}, state={}, line={}, stereo={}",
                    sources.containsKey(info.getId()) ? "updated" : "created", info.getId(),
                    info instanceof PlayerSourceInfo ? ((PlayerSourceInfo) info).getPlayerInfo().getPlayerNick() : "-",
                    info.getState(), info.getLineId(), info.isStereo());
        }
        sources.computeIfAbsent(info.getId(), id -> new VoiceSource(info, System.currentTimeMillis())).info = info;
    }

    public void onAudio(SourceAudioPacket packet) {
        if (closed || voiceDisabled.getAsBoolean()) return;
        VoiceSource source = sources.get(packet.getSourceId());
        // Upstream asks the server again when the source is unknown or its state changed.
        if (source == null || source.info.getState() != packet.getSourceState()) requestSourceInfo(packet.getSourceId());
        // Upstream: a muted line or player drops the audio, so the source is never activated.
        if (source != null && !muted.test(source.info)) source.offer(packet, System.currentTimeMillis());
    }

    public void onAudioEnd(SourceAudioEndPacket packet) {
        if (closed || voiceDisabled.getAsBoolean()) return;
        VoiceSource source = sources.get(packet.getSourceId());
        if (source != null && !muted.test(source.info)) source.end(packet, System.currentTimeMillis());
    }

    /** Stops accepting audio; the playback thread releases decoders and OpenAL sources. */
    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Upstream canHear sources: activated and within their distance; client thread (overlay). */
    public List<SourceInfo> audible() {
        List<SourceInfo> audible = new ArrayList<>();
        for (VoiceSource source : sources.values()) {
            if (source.canHear) audible.add(source.info);
        }
        return audible;
    }

    /** Upstream getPlayerSources filtered by isActivated and isIconVisible; client thread (player icons). */
    public List<SourceInfo> activated(UUID playerId) {
        List<SourceInfo> activated = new ArrayList<>();
        for (VoiceSource source : sources.values()) {
            SourceInfo info = source.info;
            if (source.activated && info.isIconVisible() && info instanceof PlayerSourceInfo
                    && ((PlayerSourceInfo) info).getPlayerInfo().getPlayerId().equals(playerId)) activated.add(info);
        }
        return activated;
    }

    /** Playback thread: upstream closes an idle source and forgets it; its next frame asks for the info again. */
    void remove(VoiceSource source) {
        sources.remove(source.info.getId(), source);
    }

    Collection<VoiceSource> all() {
        return sources.values();
    }

    private void requestSourceInfo(UUID sourceId) {
        long now = System.currentTimeMillis();
        Long last = lastRequestById.get(sourceId);
        if (last != null && now - last <= SOURCE_INFO_REQUEST_INTERVAL_MS) return;
        lastRequestById.put(sourceId, now);
        if (VoiceDebug.CLIENT.enabled()) {
            VoiceDebug.CLIENT.log(Category.SOURCE, "source info requested: source={}, known={}", sourceId, sources.containsKey(sourceId));
        }
        sourceInfoRequester.accept(sourceId);
    }
}
