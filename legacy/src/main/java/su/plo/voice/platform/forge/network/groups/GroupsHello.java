package su.plo.voice.platform.forge.network.groups;

/**
 * Serverbound Groups capability announcement: the range of Groups protocols the client speaks. A Groups-capable
 * client sends it right before every voice PlayerInfoPacket, on the same connection, so the server has always seen
 * it before it handles that voice handshake. Clients from before Groups never send it.
 */
public final class GroupsHello {
    public final int minProtocol;
    public final int maxProtocol;

    public GroupsHello(int minProtocol, int maxProtocol) {
        this.minProtocol = minProtocol;
        this.maxProtocol = maxProtocol;
    }

    @Override
    public String toString() {
        return "GroupsHello{" + minProtocol + ".." + maxProtocol + "}";
    }
}
