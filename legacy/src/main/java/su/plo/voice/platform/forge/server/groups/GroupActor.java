package su.plo.voice.platform.forge.server.groups;

/**
 * Whoever performs a group action: a player running a command or using the Groups GUI, or the console. Detached from
 * Minecraft types so the shared action layer can be tested without a server.
 */
public interface GroupActor {
    /** @return the player, or null for the console and command blocks */
    GroupProfile profile();

    /** @param node a full node, e.g. "pv.addon.groups.join" */
    boolean hasPermission(String node);
}
