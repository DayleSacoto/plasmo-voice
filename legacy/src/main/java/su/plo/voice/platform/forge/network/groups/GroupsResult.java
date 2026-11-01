package su.plo.voice.platform.forge.network.groups;

import java.util.Objects;

/**
 * Server to client: the outcome of one {@link GroupsAction}. {@code key} is the groups language key relative to
 * {@code pv.addon.groups.} (for example {@code command.join.error.wrong_password}); {@code message} is the same text
 * the command would print, already translated for this player (may contain section-sign colour codes).
 */
public final class GroupsResult {
    public final int requestId;
    public final boolean ok;
    public final String key;
    public final String message;

    public GroupsResult(int requestId, boolean ok, String key, String message) {
        this.requestId = requestId;
        this.ok = ok;
        this.key = Objects.requireNonNull(key);
        this.message = Objects.requireNonNull(message);
    }

    @Override
    public String toString() {
        return "GroupsResult{" + requestId + " ok=" + ok + " " + key + "}";
    }
}
