package su.plo.voice.platform.forge.server.groups.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.server.groups.GroupPermissions;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsClientSupport;
import su.plo.voice.platform.forge.server.groups.GroupsSettings;

/** In-memory server: online players that record every message they receive. Messages translate to key(args). */
final class FakeServer implements GroupsPlatform {
    GroupsSettings settings = GroupsSettings.defaults();
    final Map<UUID, Source> online = new LinkedHashMap<>();
    final Map<UUID, String> languages = new HashMap<>();

    /** Fake translation: the key with its arguments, so tests assert on keys instead of English text. */
    static String fmt(String key, Object... args) {
        if (args.length == 0) return key;
        StringBuilder builder = new StringBuilder(key).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(args[i]);
        }
        return builder.append(')').toString();
    }

    /** Expected text of a message in "pv.addon.groups.". */
    static String msg(String key, Object... args) {
        return fmt("pv.addon.groups." + key, args);
    }

    /** Expected text of a group notification. */
    static String notice(String groupName, String key, Object... args) {
        return msg("format.group_name", groupName) + " " + msg(key, args);
    }

    Source player(String name, boolean op) {
        Source source = new Source(new GroupProfile(UUID.nameUUIDFromBytes(name.getBytes()), name), op);
        online.put(source.id(), source);
        return source;
    }

    Source console() {
        return new Source(null, true);
    }

    void logout(Source source) {
        online.remove(source.id());
    }

    @Override
    public GroupProfile onlinePlayer(String name) {
        for (Source source : online.values()) {
            if (source.profile.getName().equalsIgnoreCase(name)) return source.profile;
        }
        return null;
    }

    @Override
    public List<GroupProfile> onlinePlayers() {
        List<GroupProfile> result = new ArrayList<>();
        for (Source source : online.values()) result.add(source.profile);
        return result;
    }

    @Override
    public GroupsSettings settings() {
        return settings;
    }

    @Override
    public String translate(UUID receiver, String key, Object... args) {
        String language = languages.get(receiver);
        return (language == null ? "" : language + ":") + fmt(key, args);
    }

    @Override
    public void send(UUID receiver, IChatComponent message) {
        Source source = online.get(receiver);
        if (source != null) source.received.add(message);
    }

    @Override
    public boolean hasPermission(UUID player, String node) {
        Source source = online.get(player);
        return source != null && source.hasPermission(node);
    }

    @Override
    public GroupsCommandSource source(ICommandSender sender) {
        throw new UnsupportedOperationException();
    }

    /** Clients that are not Groups-capable this session; every other player announced Groups support. */
    final Map<UUID, GroupsClientSupport> clients = new HashMap<>();

    @Override
    public GroupsClientSupport clientSupport(UUID player) {
        GroupsClientSupport support = clients.get(player);
        return support == null ? GroupsClientSupport.SUPPORTED : support;
    }

    /** A player (or the console when the profile is null). */
    final class Source implements GroupsCommandSource {
        final GroupProfile profile;
        final boolean op;
        final Set<String> granted = new HashSet<>();
        final Set<String> denied = new HashSet<>();
        final List<IChatComponent> received = new ArrayList<>();

        Source(GroupProfile profile, boolean op) {
            this.profile = profile;
            this.op = op;
        }

        UUID id() {
            return profile.getId();
        }

        String name() {
            return profile.getName();
        }

        Source grant(String node) {
            granted.add(node);
            return this;
        }

        Source deny(String node) {
            denied.add(node);
            return this;
        }

        @Override
        public GroupProfile profile() {
            return profile;
        }

        @Override
        public boolean hasPermission(String node) {
            if (profile == null) return true;
            if (denied.contains(node)) return false;
            return granted.contains(node) || GroupPermissions.has(node, op);
        }

        @Override
        public String translate(String key, Object... args) {
            return FakeServer.this.translate(profile == null ? null : profile.getId(), key, args);
        }

        @Override
        public void send(IChatComponent message) {
            received.add(message);
        }

        List<String> texts() {
            List<String> texts = new ArrayList<>();
            for (IChatComponent component : received) texts.add(component.getUnformattedText());
            return texts;
        }

        void clear() {
            received.clear();
        }

        int count(String text) {
            int count = 0;
            for (String received : texts()) {
                if (received.equals(text)) count++;
            }
            return count;
        }
    }

    // ---- component inspection ----

    static List<IChatComponent> flatten(IChatComponent root) {
        List<IChatComponent> all = new ArrayList<>();
        all.add(root);
        for (Object sibling : root.getSiblings()) all.addAll(flatten((IChatComponent) sibling));
        return all;
    }

    /** The first component in the tree whose own text contains the fragment. */
    static IChatComponent find(IChatComponent root, String fragment) {
        for (IChatComponent component : flatten(root)) {
            if (component instanceof ChatComponentText
                    && ((ChatComponentText) component).getChatComponentText_TextValue().contains(fragment)) {
                return component;
            }
        }
        throw new AssertionError("no component containing '" + fragment + "' in " + root.getUnformattedText());
    }

    static ClickEvent click(IChatComponent root, String fragment) {
        return find(root, fragment).getChatStyle().getChatClickEvent();
    }

    static String hover(IChatComponent root, String fragment) {
        HoverEvent event = find(root, fragment).getChatStyle().getChatHoverEvent();
        return event == null ? null : event.getValue().getUnformattedText();
    }
}
