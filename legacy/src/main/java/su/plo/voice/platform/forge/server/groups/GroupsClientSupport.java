package su.plo.voice.platform.forge.server.groups;

/**
 * Whether an online player's current client can use Groups. A Groups-capable client announces itself before its
 * voice handshake reply, so once the server has heard from the voice client the answer is final; until then it is
 * still {@link #CONNECTING} and nobody is told that their client is outdated.
 */
public enum GroupsClientSupport {
    /** Announced a Groups protocol this server speaks. */
    SUPPORTED,
    /** Finished (or gave up on) the voice handshake without announcing Groups: an older client, or no voice mod. */
    OUTDATED,
    /** The voice handshake has not been answered yet. */
    CONNECTING;

    /**
     * @param announced        the current connection announced a supported Groups protocol
     * @param handshakePending the server still waits for the player's first voice packet
     */
    public static GroupsClientSupport of(boolean announced, boolean handshakePending) {
        if (announced) return SUPPORTED;
        return handshakePending ? CONNECTING : OUTDATED;
    }
}
