package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.server.groups.GroupPermissions;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsClientSupport;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

/** The running Forge 1.7.10 server behind /groups. */
public final class ForgeGroupsPlatform implements GroupsPlatform {
    private final PlasmoVoiceMod mod;
    private final Supplier<GroupsSettings> settings;

    public ForgeGroupsPlatform(PlasmoVoiceMod mod, Supplier<GroupsSettings> settings) {
        this.mod = mod;
        this.settings = settings;
    }

    @Override
    public GroupProfile onlinePlayer(String name) {
        EntityPlayerMP player = players().func_152612_a(name);
        return player == null ? null : profileOf(player);
    }

    @Override
    public GroupProfile onlinePlayer(UUID id) {
        EntityPlayerMP player = find(id);
        return player == null ? null : profileOf(player);
    }

    @Override
    public List<GroupProfile> onlinePlayers() {
        List<GroupProfile> result = new ArrayList<>();
        for (Object player : players().playerEntityList) result.add(profileOf((EntityPlayerMP) player));
        return result;
    }

    @Override
    public GroupsSettings settings() {
        return settings.get();
    }

    @Override
    public String translate(UUID receiver, String key, Object... args) {
        return mod.translate(receiver == null ? null : find(receiver), key, args);
    }

    @Override
    public void send(UUID receiver, IChatComponent message) {
        EntityPlayerMP player = find(receiver);
        if (player != null) player.addChatMessage(message);
    }

    @Override
    public boolean hasPermission(UUID player, String node) {
        EntityPlayerMP online = find(player);
        return online != null && GroupPermissions.has(node, isOp(online));
    }

    @Override
    public GroupsCommandSource source(ICommandSender sender) {
        return wrap(mod, sender);
    }

    @Override
    public GroupsClientSupport clientSupport(UUID player) {
        return mod.groupsSupport(player);
    }

    /**
     * Players resolve nodes from the upstream defaults (operators get everything); the console and command blocks
     * pass every check, like the upstream console.
     */
    public static GroupsCommandSource wrap(final PlasmoVoiceMod mod, final ICommandSender sender) {
        return new GroupsCommandSource() {
            @Override
            public GroupProfile profile() {
                return sender instanceof EntityPlayerMP ? profileOf((EntityPlayerMP) sender) : null;
            }

            @Override
            public boolean hasPermission(String node) {
                if (!(sender instanceof EntityPlayerMP)) return true;
                return GroupPermissions.has(node, isOp((EntityPlayerMP) sender));
            }

            @Override
            public String translate(String key, Object... args) {
                return mod.translate(sender, key, args);
            }

            @Override
            public void send(IChatComponent message) {
                sender.addChatMessage(message);
            }
        };
    }

    private static ServerConfigurationManager players() {
        return MinecraftServer.getServer().getConfigurationManager();
    }

    private static boolean isOp(EntityPlayerMP player) {
        return players().func_152596_g(player.getGameProfile());
    }

    private static GroupProfile profileOf(EntityPlayerMP player) {
        return new GroupProfile(player.getUniqueID(), player.getCommandSenderName());
    }

    private static EntityPlayerMP find(UUID id) {
        for (Object player : players().playerEntityList) {
            if (((EntityPlayerMP) player).getUniqueID().equals(id)) return (EntityPlayerMP) player;
        }
        return null;
    }
}
