package su.plo.voice.platform.forge.server.groups;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Routing snapshot of one speaker's Groups source (upstream GroupsManager.initSource broadcast source): the source id
 * and the other online members of the speaker's group. Built by the server thread, read by the UDP worker.
 */
public final class GroupRoute {
    private final UUID speaker;
    private final UUID sourceId;
    private final byte sourceState;
    private final Set<UUID> recipients;

    public GroupRoute(UUID speaker, UUID sourceId, byte sourceState, Set<UUID> recipients) {
        this.speaker = speaker;
        this.sourceId = sourceId;
        this.sourceState = sourceState;
        Set<UUID> copy = new HashSet<>(recipients);
        copy.remove(speaker);
        this.recipients = Collections.unmodifiableSet(copy);
    }

    public UUID getSpeaker() {
        return speaker;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public byte getSourceState() {
        return sourceState;
    }

    /** Never contains the speaker. */
    public Set<UUID> getRecipients() {
        return recipients;
    }
}
