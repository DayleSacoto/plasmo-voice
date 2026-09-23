package su.plo.voice.platform.forge.server;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.server.connection.ServerConnection;

/**
 * Upstream default commands. 1.7.10 has no permission nodes: pv.list and pv.reconnect (default TRUE) are open to
 * everyone, the other commands need operator level 2, like upstream's OP default for unregistered permissions.
 */
public final class VoiceCommands {
    private static final Pattern DURATION_PATTERN = Pattern.compile("^([0-9]*)([mhdwsu]|permanent)?$");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^([0-9]*)$");

    private VoiceCommands() {
    }

    public static void register(FMLServerStartingEvent event, PlasmoVoiceMod mod) {
        event.registerServerCommand(new ListCommand(mod));
        event.registerServerCommand(new ReconnectCommand(mod));
        event.registerServerCommand(new ReloadCommand(mod));
        event.registerServerCommand(new MuteCommand(mod));
        event.registerServerCommand(new UnmuteCommand(mod));
        event.registerServerCommand(new MuteListCommand(mod));
    }

    private abstract static class VoiceCommand extends CommandBase {
        final PlasmoVoiceMod mod;
        private final String name;
        private final boolean everyone;

        VoiceCommand(PlasmoVoiceMod mod, String name, boolean everyone) {
            this.mod = mod;
            this.name = name;
            this.everyone = everyone;
        }

        @Override
        public String getCommandName() {
            return name;
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/" + name;
        }

        @Override
        public int getRequiredPermissionLevel() {
            return everyone ? 0 : 2;
        }

        /** Vanilla lets non-operators run only a few commands, so the open ones skip the level check. */
        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return everyone || super.canCommandSenderUseCommand(sender);
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (mod.getServerConfig() == null) return;
            execute(sender, args);
        }

        abstract void execute(ICommandSender sender, String[] args);

        void send(ICommandSender sender, String key, Object... args) {
            mod.sendMessage(sender, mod.translate(sender, key, args));
        }
    }

    private static final class ListCommand extends VoiceCommand {
        ListCommand(PlasmoVoiceMod mod) {
            super(mod, "vlist", true);
        }

        @Override
        void execute(ICommandSender sender, String[] args) {
            List<String> players = new ArrayList<>();
            for (ServerConnection connection : mod.getVoicePlayers().all()) {
                if (connection.isVoiceConnected()) players.add(connection.getPlayer().getCommandSenderName());
            }
            Collections.sort(players);
            int total = MinecraftServer.getServer().getCurrentPlayerCount();
            send(sender, "pv.command.list.message", players.size(), total,
                    players.isEmpty() ? mod.translate(sender, "pv.command.list.empty") : String.join(", ", players));
        }
    }

    private static final class ReconnectCommand extends VoiceCommand {
        ReconnectCommand(PlasmoVoiceMod mod) {
            super(mod, "vrc", true);
        }

        @Override
        void execute(ICommandSender sender, String[] args) {
            if (!(sender instanceof EntityPlayerMP)) {
                send(sender, "pv.error.player_only_command");
                return;
            }
            send(sender, "pv.command.reconnect.message");
            mod.reconnect((EntityPlayerMP) sender);
        }
    }

    private static final class ReloadCommand extends VoiceCommand {
        ReloadCommand(PlasmoVoiceMod mod) {
            super(mod, "vreload", false);
        }

        @Override
        void execute(ICommandSender sender, String[] args) {
            mod.reload();
            send(sender, "pv.command.reload.message");
        }
    }

    private static final class MuteCommand extends VoiceCommand {
        MuteCommand(PlasmoVoiceMod mod) {
            super(mod, "vmute", false);
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/vmute <player> [duration] [reason]";
        }

        @Override
        void execute(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                send(sender, "pv.command.mute.usage");
                return;
            }
            EntityPlayerMP player = MinecraftServer.getServer().getConfigurationManager().func_152612_a(args[0]);
            if (player == null) {
                send(sender, "pv.error.player_not_found");
                return;
            }
            if (mod.getMutes().isMuted(player.getUniqueID())) {
                send(sender, "pv.command.mute.already_muted", player.getCommandSenderName());
                return;
            }

            // Upstream: "10m", "2h", "permanent", a bare number is seconds, "u" is a unix timestamp in seconds.
            int reasonIndex = 1;
            MuteDuration duration = MuteDuration.PERMANENT;
            if (args.length > 1) {
                MuteDuration parsed = MuteDuration.parse(args[1]);
                if (parsed != null) {
                    duration = parsed;
                    reasonIndex = 2;
                }
            }
            String reason = args.length > reasonIndex
                    ? String.join(" ", Arrays.copyOfRange(args, reasonIndex, args.length))
                    : null;

            long now = System.currentTimeMillis();
            long mutedToTime = duration.mutedToTime(now);
            if (mutedToTime != 0 && mutedToTime <= now) {
                mod.sendMessage(sender, "TIMESTAMP duration should be in the future");
                return;
            }
            if (mutedToTime == 0) {
                send(sender, "pv.command.mute.permanently_muted", player.getCommandSenderName(), mod.formatReason(sender, reason));
            } else {
                send(sender, "pv.command.mute.temporarily_muted", player.getCommandSenderName(),
                        duration.translate(mod, sender, now), mod.formatReason(sender, reason));
            }
            UUID mutedBy = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).getUniqueID() : null;
            mod.mute(player, mutedBy, duration, mutedToTime, reason, !mod.getServerConfig().getSettings().isNotifyMuted());
        }

        @Override
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
            if (args.length == 2) {
                if ("permanent".startsWith(args[1])) return Collections.singletonList("permanent");
                if (INTEGER_PATTERN.matcher(args[1]).find()) {
                    List<String> durations = new ArrayList<>();
                    for (String unit : new String[] {"s", "m", "h", "d", "w"}) durations.add(args[1] + unit);
                    return durations;
                }
            }
            return null;
        }
    }

    private static final class UnmuteCommand extends VoiceCommand {
        UnmuteCommand(PlasmoVoiceMod mod) {
            super(mod, "vunmute", false);
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/vunmute <player>";
        }

        @Override
        void execute(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                send(sender, "pv.command.unmute.usage");
                return;
            }
            GameProfile player = mod.findProfile(args[0]);
            if (player == null) {
                send(sender, "pv.error.player_not_found");
                return;
            }
            if (!mod.unmute(player.getId(), !mod.getServerConfig().getSettings().isNotifyUnmuted())) {
                send(sender, "pv.command.unmute.not_muted", player.getName());
                return;
            }
            send(sender, "pv.command.unmute.unmuted", player.getName());
        }

        /** Upstream suggests the muted players. */
        @Override
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
            if (args.length != 1) return null;
            List<String> names = new ArrayList<>();
            for (VoiceMutes.Mute mute : mod.getMutes().all()) {
                GameProfile profile = cachedProfile(mute.getPlayerId());
                names.add(profile == null ? mute.getPlayerId().toString() : profile.getName());
            }
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
    }

    private static final class MuteListCommand extends VoiceCommand {
        MuteListCommand(PlasmoVoiceMod mod) {
            super(mod, "vmutelist", false);
        }

        @Override
        void execute(ICommandSender sender, String[] args) {
            send(sender, "pv.command.mute_list.header");
            if (mod.getMutes().all().isEmpty()) {
                send(sender, "pv.command.mute_list.empty");
                return;
            }
            for (VoiceMutes.Mute mute : mod.getMutes().all()) {
                GameProfile player = cachedProfile(mute.getPlayerId());
                if (player == null) continue;
                GameProfile mutedBy = mute.getMutedById() == null ? null : cachedProfile(mute.getMutedById());
                String expires;
                if (mute.getMutedToTime() > 0) {
                    Date date = new Date(mute.getMutedToTime());
                    String dateText = new SimpleDateFormat(mod.translate(sender, "pv.command.mute_list.expiration_date")).format(date);
                    String timeText = new SimpleDateFormat(mod.translate(sender, "pv.command.mute_list.expiration_time")).format(date);
                    expires = mod.translate(sender, "pv.command.mute_list.expire_at", dateText, timeText);
                } else {
                    expires = mod.translate(sender, "pv.command.mute_list.never_expires");
                }
                String reason = mod.formatReason(sender, mute.getReason());
                if (mutedBy != null) {
                    send(sender, "pv.command.mute_list.entry_muted_by", player.getName(), mutedBy.getName(), expires, reason);
                } else {
                    send(sender, "pv.command.mute_list.entry", player.getName(), expires, reason);
                }
            }
        }
    }

    /** usercache.json only: never a blocking profile lookup on the server thread. */
    static GameProfile cachedProfile(UUID playerId) {
        return MinecraftServer.getServer().func_152358_ax().func_152652_a(playerId);
    }

    /** Upstream MuteDurationUnit with the parsed amount. */
    public static final class MuteDuration {
        static final MuteDuration PERMANENT = new MuteDuration(null, 0);

        private final String unit;
        private final long amount;

        private MuteDuration(String unit, long amount) {
            this.unit = unit;
            this.amount = amount;
        }

        /** Null when the argument is not a duration, so it starts the reason instead. */
        static MuteDuration parse(String argument) {
            Matcher matcher = DURATION_PATTERN.matcher(argument);
            if (!matcher.find()) return null;
            String type = matcher.group(2);
            if ("permanent".equals(type)) return PERMANENT;
            if (matcher.group(1).isEmpty()) return null;
            return new MuteDuration(type == null ? "s" : type, Long.parseLong(matcher.group(1)));
        }

        long mutedToTime(long now) {
            if (unit == null) return 0;
            switch (unit) {
                case "u":
                    return amount * 1_000L;
                case "m":
                    return now + amount * 60_000L;
                case "h":
                    return now + amount * 3_600_000L;
                case "d":
                    return now + amount * 86_400_000L;
                case "w":
                    return now + amount * 604_800_000L;
                default:
                    return now + amount * 1_000L;
            }
        }

        /** Upstream MuteDurationUnit.translate: a timestamp is shown as the seconds left. */
        public String translate(PlasmoVoiceMod mod, ICommandSender receiver, long now) {
            String key;
            switch (unit) {
                case "m":
                    key = "pv.mutes.durations.minutes";
                    break;
                case "h":
                    key = "pv.mutes.durations.hours";
                    break;
                case "d":
                    key = "pv.mutes.durations.days";
                    break;
                case "w":
                    key = "pv.mutes.durations.weeks";
                    break;
                default:
                    key = "pv.mutes.durations.seconds";
            }
            return mod.translate(receiver, key, "u".equals(unit) ? (amount * 1_000L - now) / 1_000L : amount);
        }
    }
}
