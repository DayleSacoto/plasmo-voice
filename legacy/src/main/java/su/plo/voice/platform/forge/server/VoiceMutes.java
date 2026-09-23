package su.plo.voice.platform.forge.server;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.Logger;

/**
 * Upstream VoiceMuteManager storage (JsonMuteStorage): server mutes by player id in config/plasmovoice/voice_mutes.json,
 * same JSON layout as upstream. Server thread only.
 */
public final class VoiceMutes {
    private static final Gson GSON = new Gson();
    private static final Type FILE_TYPE = new TypeToken<Map<String, Mute>>() {
    }.getType();

    private final File file;
    private final Logger logger;
    private final Map<UUID, Mute> mutes = new LinkedHashMap<>();

    public VoiceMutes(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Upstream ServerMuteInfo, with the ids as strings so Gson keeps the upstream field names. */
    public static final class Mute {
        String playerUUID;
        String mutedByPlayerUUID;
        long mutedAtTime;
        long mutedToTime;
        String reason;
        Boolean silent;

        public UUID getPlayerId() {
            return UUID.fromString(playerUUID);
        }

        public UUID getMutedById() {
            return mutedByPlayerUUID == null ? null : UUID.fromString(mutedByPlayerUUID);
        }

        public long getMutedToTime() {
            return mutedToTime;
        }

        public String getReason() {
            return reason;
        }

        public boolean isSilent() {
            return Boolean.TRUE.equals(silent);
        }

        /** Upstream isMuteValid: permanent, or not expired yet. */
        public boolean isValid(long now) {
            return mutedToTime == 0 || mutedToTime > now;
        }
    }

    /** Upstream JsonMuteStorage.init: expired entries are dropped and the file is rewritten. */
    public void load() {
        mutes.clear();
        if (!file.exists()) return;
        try {
            Map<String, Mute> data = GSON.fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), FILE_TYPE);
            if (data == null) return;
            long now = System.currentTimeMillis();
            data.forEach((playerId, mute) -> {
                try {
                    UUID id = UUID.fromString(playerId);
                    if (mute != null && mute.isValid(now)) {
                        mute.playerUUID = id.toString();
                        mutes.put(id, mute);
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping a voice mute with an invalid player id: {}", playerId);
                }
            });
            if (data.size() != mutes.size()) save();
        } catch (IOException | JsonParseException e) {
            logger.error("Failed to load voice mutes from {}", file, e);
        }
    }

    public Mute get(UUID playerId) {
        Mute mute = mutes.get(playerId);
        return mute != null && mute.isValid(System.currentTimeMillis()) ? mute : null;
    }

    public boolean isMuted(UUID playerId) {
        return get(playerId) != null;
    }

    /** @param mutedToTime 0 for a permanent mute. */
    public Mute mute(UUID playerId, UUID mutedById, long mutedToTime, String reason, boolean silent) {
        Mute mute = new Mute();
        mute.playerUUID = playerId.toString();
        mute.mutedByPlayerUUID = mutedById == null ? null : mutedById.toString();
        mute.mutedAtTime = System.currentTimeMillis();
        mute.mutedToTime = mutedToTime;
        mute.reason = reason;
        mute.silent = silent;
        if (!mute.isValid(mute.mutedAtTime)) return null;
        mutes.put(playerId, mute);
        save();
        return mute;
    }

    public Mute unmute(UUID playerId) {
        Mute mute = mutes.remove(playerId);
        if (mute != null) save();
        return mute;
    }

    public Collection<Mute> all() {
        return Collections.unmodifiableCollection(mutes.values());
    }

    /** Upstream VoiceMuteManager.tick: the mutes that ran out. */
    public List<Mute> expired(long now) {
        List<Mute> expired = new ArrayList<>();
        for (Mute mute : mutes.values()) {
            if (!mute.isValid(now)) expired.add(mute);
        }
        return expired;
    }

    private void save() {
        Map<String, Mute> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        mutes.forEach((playerId, mute) -> {
            if (mute.isValid(now)) data.put(playerId.toString(), mute);
        });
        try {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("Failed to save voice mutes to {}", file, e);
        }
    }
}
