package su.plo.voice.platform.forge.server.groups;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;

/**
 * Upstream groups.json (GroupsManager.Data) with the same field names plus the group permissions upstream lost on
 * restart. Output is deterministic and written atomically; a file that cannot be parsed is kept aside, a file that
 * cannot be read is never overwritten. Server thread only.
 */
public final class GroupsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File file;
    private final Logger logger;
    /** Serialized form of the last data loaded or saved; identical saves are skipped. */
    private String lastContent;
    private boolean readFailed;

    public GroupsStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** One persistent group. */
    public static final class Entry {
        private final UUID id;
        private final String name;
        private final String password;
        private final boolean persistent;
        private final Set<UUID> members;
        private final List<GroupProfile> banned;
        private final GroupProfile owner;
        private final Set<String> permissions;
        private final Map<UUID, String> memberNames;

        public Entry(UUID id, String name, String password, boolean persistent, Set<UUID> members,
                     List<GroupProfile> banned, GroupProfile owner, Set<String> permissions) {
            this(id, name, password, persistent, members, banned, owner, permissions, Collections.<UUID, String>emptyMap());
        }

        /** @param memberNames last known names of members, so an offline member can be listed by name */
        public Entry(UUID id, String name, String password, boolean persistent, Set<UUID> members,
                     List<GroupProfile> banned, GroupProfile owner, Set<String> permissions,
                     Map<UUID, String> memberNames) {
            this.id = id;
            this.name = name;
            this.password = password;
            this.persistent = persistent;
            this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
            this.banned = Collections.unmodifiableList(new ArrayList<>(banned));
            this.owner = owner;
            this.permissions = Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
            this.memberNames = Collections.unmodifiableMap(new LinkedHashMap<>(memberNames));
        }

        public UUID getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getPassword() {
            return password;
        }

        public boolean isPersistent() {
            return persistent;
        }

        public Set<UUID> getMembers() {
            return members;
        }

        public List<GroupProfile> getBanned() {
            return banned;
        }

        public GroupProfile getOwner() {
            return owner;
        }

        public Set<String> getPermissions() {
            return permissions;
        }

        public Map<UUID, String> getMemberNames() {
            return memberNames;
        }
    }

    /** The whole file: groups and the player to group mapping. */
    public static final class Data {
        private final List<Entry> groups;
        private final Map<UUID, UUID> groupByPlayer;

        public Data(List<Entry> groups, Map<UUID, UUID> groupByPlayer) {
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.groupByPlayer = Collections.unmodifiableMap(new LinkedHashMap<>(groupByPlayer));
        }

        public static Data empty() {
            return new Data(Collections.<Entry>emptyList(), Collections.<UUID, UUID>emptyMap());
        }

        public List<Entry> getGroups() {
            return groups;
        }

        public Map<UUID, UUID> getGroupByPlayer() {
            return groupByPlayer;
        }
    }

    // Gson shapes with the upstream field names.
    private static final class ProfileDto {
        String uuid;
        String name;
    }

    private static final class GroupDto {
        String id;
        String name;
        String password;
        boolean persistent;
        List<String> playersIds;
        List<ProfileDto> bannedPlayers;
        ProfileDto owner;
        List<String> permissions;
        /** Optional (absent in files from before the Groups GUI): member id to last known name. */
        Map<String, String> playerNames;
    }

    private static final class FileDto {
        List<GroupDto> groups;
        Map<String, String> groupByPlayer;
    }

    public Data load() {
        lastContent = null;
        readFailed = false;
        if (!file.exists()) return Data.empty();

        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            readFailed = true;
            logger.error("Failed to read groups from {}; saving is disabled to keep the file", file, e);
            return Data.empty();
        }

        JsonObject root;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) throw new JsonParseException("root is not an object");
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            moveAside(e);
            return Data.empty();
        }

        Data data;
        try {
            data = parse(root);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            moveAside(e);
            return Data.empty();
        }
        lastContent = serialize(data);
        return data;
    }

    /** Writes the data unless it equals what was last loaded or saved. */
    public void save(Data data) {
        if (readFailed) {
            logger.warn("Not saving groups to {}: the existing file could not be read", file);
            return;
        }
        String content = serialize(data);
        if (content.equals(lastContent)) return;

        File tmp = new File(file.getPath() + ".tmp");
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            lastContent = content;
        } catch (IOException e) {
            logger.warn("Failed to save groups to {}", file, e);
            tmp.delete();
        }
    }

    private void moveAside(Exception cause) {
        File broken = new File(file.getParentFile(),
                file.getName() + ".broken-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        try {
            Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.warn("Groups file {} is malformed ({}); kept as {} and starting empty", file, cause.getMessage(), broken.getName());
        } catch (IOException e) {
            logger.warn("Groups file {} is malformed ({}) and could not be moved aside", file, cause.getMessage(), e);
        }
    }

    // ---- parsing ----

    private Data parse(JsonObject root) {
        List<Entry> groups = new ArrayList<>();
        JsonElement groupsElement = root.get("groups");
        if (groupsElement != null && !groupsElement.isJsonNull()) {
            if (!groupsElement.isJsonArray()) throw new JsonParseException("groups is not an array");
            JsonArray array = groupsElement.getAsJsonArray();
            for (JsonElement element : array) {
                try {
                    Entry entry = parseGroup(GSON.fromJson(element, GroupDto.class));
                    if (entry != null) groups.add(entry);
                } catch (JsonParseException | IllegalArgumentException e) {
                    logger.warn("Skipping an invalid group in {}: {}", file, e.getMessage());
                }
            }
        }

        Map<UUID, UUID> byPlayer = new LinkedHashMap<>();
        JsonElement byPlayerElement = root.get("groupByPlayer");
        if (byPlayerElement != null && !byPlayerElement.isJsonNull()) {
            if (!byPlayerElement.isJsonObject()) throw new JsonParseException("groupByPlayer is not an object");
            for (Map.Entry<String, JsonElement> mapping : byPlayerElement.getAsJsonObject().entrySet()) {
                try {
                    byPlayer.put(UUID.fromString(mapping.getKey()), UUID.fromString(mapping.getValue().getAsString()));
                } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                    logger.warn("Skipping an invalid groupByPlayer entry in {}: {}", file, mapping.getKey());
                }
            }
        }
        return new Data(groups, byPlayer);
    }

    private Entry parseGroup(GroupDto dto) {
        if (dto == null || dto.id == null || dto.name == null) {
            logger.warn("Skipping a group without id or name in {}", file);
            return null;
        }
        UUID id = UUID.fromString(dto.id);

        Set<UUID> members = new LinkedHashSet<>();
        if (dto.playersIds != null) {
            for (String member : dto.playersIds) {
                try {
                    members.add(UUID.fromString(member));
                } catch (IllegalArgumentException | NullPointerException e) {
                    logger.warn("Skipping an invalid member id of group {}: {}", id, member);
                }
            }
        }
        List<GroupProfile> banned = new ArrayList<>();
        if (dto.bannedPlayers != null) {
            for (ProfileDto profile : dto.bannedPlayers) {
                GroupProfile parsed = parseProfile(profile, id);
                if (parsed != null) banned.add(parsed);
            }
        }
        Set<String> permissions = new LinkedHashSet<>();
        if (dto.permissions != null) {
            for (String permission : dto.permissions) {
                if (permission != null) permissions.add(permission);
            }
        }
        Map<UUID, String> memberNames = new LinkedHashMap<>();
        if (dto.playerNames != null) {
            for (Map.Entry<String, String> known : dto.playerNames.entrySet()) {
                try {
                    if (known.getValue() != null && !known.getValue().isEmpty()) {
                        memberNames.put(UUID.fromString(known.getKey()), known.getValue());
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping an invalid member name key of group {}: {}", id, known.getKey());
                }
            }
        }
        return new Entry(id, dto.name, dto.password, dto.persistent, members, banned, parseProfile(dto.owner, id), permissions,
                memberNames);
    }

    private GroupProfile parseProfile(ProfileDto dto, UUID groupId) {
        if (dto == null) return null;
        try {
            return new GroupProfile(UUID.fromString(dto.uuid), dto.name == null ? "" : dto.name);
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warn("Skipping an invalid player profile of group {}: {}", groupId, dto.uuid);
            return null;
        }
    }

    // ---- serialization ----

    private static ProfileDto profileDto(GroupProfile profile) {
        if (profile == null) return null;
        ProfileDto dto = new ProfileDto();
        dto.uuid = profile.getId().toString();
        dto.name = profile.getName();
        return dto;
    }

    private static String serialize(Data data) {
        Map<String, Entry> sorted = new TreeMap<>();
        for (Entry entry : data.getGroups()) sorted.put(entry.getId().toString(), entry);

        FileDto file = new FileDto();
        file.groups = new ArrayList<>();
        for (Entry entry : sorted.values()) {
            GroupDto dto = new GroupDto();
            dto.id = entry.getId().toString();
            dto.name = entry.getName();
            dto.password = entry.getPassword();
            dto.persistent = entry.isPersistent();
            Set<String> members = new TreeSet<>();
            for (UUID member : entry.getMembers()) members.add(member.toString());
            dto.playersIds = new ArrayList<>(members);
            dto.bannedPlayers = new ArrayList<>();
            for (GroupProfile banned : entry.getBanned()) dto.bannedPlayers.add(profileDto(banned));
            dto.owner = profileDto(entry.getOwner());
            dto.permissions = new ArrayList<>(new TreeSet<>(entry.getPermissions()));
            if (!entry.getMemberNames().isEmpty()) {
                Map<String, String> names = new TreeMap<>();
                for (Map.Entry<UUID, String> known : entry.getMemberNames().entrySet()) {
                    names.put(known.getKey().toString(), known.getValue());
                }
                dto.playerNames = names;
            }
            file.groups.add(dto);
        }
        Map<String, String> byPlayer = new TreeMap<>();
        for (Map.Entry<UUID, UUID> mapping : data.getGroupByPlayer().entrySet()) {
            byPlayer.put(mapping.getKey().toString(), mapping.getValue().toString());
        }
        file.groupByPlayer = byPlayer;
        return GSON.toJson(file);
    }
}
