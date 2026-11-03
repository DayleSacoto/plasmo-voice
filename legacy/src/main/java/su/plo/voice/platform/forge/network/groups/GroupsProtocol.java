package su.plo.voice.platform.forge.network.groups;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire format of the Legacy-only Groups GUI channel. Kept separate from {@code plasmo:voice/v2} so the shared upstream
 * protocol module stays untouched. Pure Java: no Minecraft or FML types, so it is unit-testable.
 *
 * <p>Frame: {@code [byte version][byte kind][body]}. Decoding is strict: unknown versions, unknown kinds, oversized
 * strings or lists and trailing bytes are rejected with an {@link IOException}.
 */
public final class GroupsProtocol {
    /** 19 characters; 1.7.10 custom payload channel names are limited to 20. */
    public static final String CHANNEL = "plasmo:voice/groups";
    public static final int VERSION = 1;

    public static final int KIND_ACTION = 1;
    public static final int KIND_STATE = 2;
    public static final int KIND_RESULT = 3;
    /**
     * Serverbound {@link GroupsHello}. Its frame never changes: {@code [byte 1][byte 4][short min][short max]}, and
     * bytes after that are ignored, so any future client can still announce itself to this server.
     */
    public static final int KIND_HELLO = 4;

    public static final int MAX_NAME = 64;
    public static final int MAX_PASSWORD = 64;
    public static final int MAX_PERMISSION = 128;
    public static final int MAX_PERMISSIONS = 32;
    public static final int MAX_MEMBERS = 512;
    public static final int MAX_BROWSE = 100;
    public static final int MAX_MESSAGE = 1024;
    /** Serverbound custom payloads are capped at 32766 bytes in 1.7.10. */
    public static final int MAX_SERVERBOUND = 32766;

    private GroupsProtocol() {
    }

    // ---- encode ----

    public static byte[] encode(GroupsAction action) {
        return write(KIND_ACTION, out -> {
            out.writeInt(action.requestId);
            out.writeByte(action.type.ordinal());
            writeUuid(out, action.groupId);
            writeUuid(out, action.playerId);
            writeString(out, action.name, MAX_NAME);
            writeString(out, action.password, MAX_PASSWORD);
            out.writeBoolean(action.clearPassword);
            out.writeByte(action.persistent == null ? 0 : action.persistent ? 2 : 1);
            writeStrings(out, action.permissions, MAX_PERMISSIONS, MAX_PERMISSION);
        });
    }

    public static byte[] encode(GroupsState state) {
        return write(KIND_STATE, out -> {
            out.writeInt(state.capabilities);
            out.writeShort(state.nameMin);
            out.writeShort(state.nameMax);
            GroupsState.CurrentGroup current = state.current;
            out.writeBoolean(current != null);
            if (current != null) {
                out.writeLong(current.id.getMostSignificantBits());
                out.writeLong(current.id.getLeastSignificantBits());
                writeString(out, current.name, MAX_NAME);
                writeUuid(out, current.ownerId);
                writeString(out, current.ownerName, MAX_NAME);
                out.writeBoolean(current.hasPassword);
                out.writeBoolean(current.persistent);
                writeStrings(out, current.permissions, MAX_PERMISSIONS, MAX_PERMISSION);
                writeMembers(out, current.members);
                writeMembers(out, current.banned);
                out.writeInt(current.capabilities);
            }
            int count = Math.min(state.browse.size(), MAX_BROWSE);
            out.writeShort(count);
            for (int i = 0; i < count; i++) {
                GroupsState.BrowseEntry entry = state.browse.get(i);
                out.writeLong(entry.id.getMostSignificantBits());
                out.writeLong(entry.id.getLeastSignificantBits());
                writeString(out, entry.name, MAX_NAME);
                writeString(out, entry.ownerName, MAX_NAME);
                out.writeShort(Math.min(entry.online, 0xFFFF));
                out.writeShort(Math.min(entry.members, 0xFFFF));
                out.writeByte((entry.hasPassword ? 1 : 0) | (entry.persistent ? 2 : 0) | (entry.joinable ? 4 : 0));
            }
            out.writeInt(state.browseTotal);
        });
    }

    public static byte[] encode(GroupsResult result) {
        return write(KIND_RESULT, out -> {
            out.writeInt(result.requestId);
            out.writeBoolean(result.ok);
            writeString(out, result.key, MAX_MESSAGE);
            writeString(out, result.message, MAX_MESSAGE);
        });
    }

    public static byte[] encode(GroupsHello hello) {
        return write(KIND_HELLO, out -> {
            out.writeShort(hello.minProtocol);
            out.writeShort(hello.maxProtocol);
        });
    }

    // ---- decode ----

    /** Returns a {@link GroupsAction}, {@link GroupsState}, {@link GroupsResult} or {@link GroupsHello}. */
    public static Object decode(byte[] data) throws IOException {
        if (data == null || data.length < 2) throw new IOException("Groups payload too short");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readUnsignedByte();
        if (version == 1 && data[1] == KIND_HELLO) {
            in.readUnsignedByte();
            return new GroupsHello(in.readUnsignedShort(), in.readUnsignedShort());
        }
        if (version != VERSION) throw new IOException("Unsupported groups protocol version " + version);
        int kind = in.readUnsignedByte();
        Object decoded;
        if (kind == KIND_ACTION) decoded = readAction(in);
        else if (kind == KIND_STATE) decoded = readState(in);
        else if (kind == KIND_RESULT) decoded = readResult(in);
        else throw new IOException("Unknown groups payload kind " + kind);
        if (in.available() != 0) throw new IOException("Trailing bytes in groups payload");
        return decoded;
    }

    private static GroupsAction readAction(DataInputStream in) throws IOException {
        int requestId = in.readInt();
        int type = in.readUnsignedByte();
        GroupsAction.Type[] types = GroupsAction.Type.values();
        if (type >= types.length) throw new IOException("Unknown groups action " + type);
        UUID groupId = readUuid(in);
        UUID playerId = readUuid(in);
        String name = readString(in, MAX_NAME);
        String password = readString(in, MAX_PASSWORD);
        boolean clearPassword = in.readBoolean();
        int persistent = in.readUnsignedByte();
        if (persistent > 2) throw new IOException("Invalid persistent flag");
        List<String> permissions = readStrings(in, MAX_PERMISSIONS, MAX_PERMISSION);
        return new GroupsAction(requestId, types[type], groupId, playerId, name, password, clearPassword,
                persistent == 0 ? null : persistent == 2, permissions);
    }

    private static GroupsState readState(DataInputStream in) throws IOException {
        int capabilities = in.readInt();
        int nameMin = in.readUnsignedShort();
        int nameMax = in.readUnsignedShort();
        GroupsState.CurrentGroup current = null;
        if (in.readBoolean()) {
            UUID id = new UUID(in.readLong(), in.readLong());
            String name = require(readString(in, MAX_NAME));
            UUID ownerId = readUuid(in);
            String ownerName = readString(in, MAX_NAME);
            boolean hasPassword = in.readBoolean();
            boolean persistent = in.readBoolean();
            List<String> permissions = readStrings(in, MAX_PERMISSIONS, MAX_PERMISSION);
            List<GroupsState.Member> members = readMembers(in);
            List<GroupsState.Member> banned = readMembers(in);
            int groupCapabilities = in.readInt();
            current = new GroupsState.CurrentGroup(id, name, ownerId, ownerName, hasPassword, persistent,
                    permissions == null ? new ArrayList<>() : permissions, members, banned, groupCapabilities);
        }
        int count = in.readUnsignedShort();
        if (count > MAX_BROWSE) throw new IOException("Too many groups: " + count);
        List<GroupsState.BrowseEntry> browse = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = new UUID(in.readLong(), in.readLong());
            String name = require(readString(in, MAX_NAME));
            String ownerName = readString(in, MAX_NAME);
            int online = in.readUnsignedShort();
            int members = in.readUnsignedShort();
            int flags = in.readUnsignedByte();
            browse.add(new GroupsState.BrowseEntry(id, name, ownerName, online, members, (flags & 1) != 0,
                    (flags & 2) != 0, (flags & 4) != 0));
        }
        int browseTotal = in.readInt();
        return new GroupsState(capabilities, nameMin, nameMax, current, browse, Math.max(browseTotal, count));
    }

    private static GroupsResult readResult(DataInputStream in) throws IOException {
        int requestId = in.readInt();
        boolean ok = in.readBoolean();
        String key = require(readString(in, MAX_MESSAGE));
        String message = require(readString(in, MAX_MESSAGE));
        return new GroupsResult(requestId, ok, key, message);
    }

    // ---- primitives ----

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(int kind, Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(kind == KIND_HELLO ? 1 : VERSION);
            out.writeByte(kind);
            body.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeBoolean(id != null);
        if (id != null) {
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
        }
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
    }

    /** Nullable string; longer values are truncated (encode) or rejected (decode). */
    private static void writeString(DataOutputStream out, String value, int max) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value.length() > max ? value.substring(0, max) : value);
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        if (!in.readBoolean()) return null;
        String value = in.readUTF();
        if (value.length() > max) throw new IOException("String too long: " + value.length());
        return value;
    }

    private static void writeStrings(DataOutputStream out, List<String> values, int maxCount, int max)
            throws IOException {
        out.writeBoolean(values != null);
        if (values == null) return;
        int count = Math.min(values.size(), maxCount);
        out.writeByte(count);
        for (int i = 0; i < count; i++) out.writeUTF(truncate(values.get(i), max));
    }

    private static List<String> readStrings(DataInputStream in, int maxCount, int max) throws IOException {
        if (!in.readBoolean()) return null;
        int count = in.readUnsignedByte();
        if (count > maxCount) throw new IOException("Too many strings: " + count);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String value = in.readUTF();
            if (value.length() > max) throw new IOException("String too long: " + value.length());
            values.add(value);
        }
        return values;
    }

    private static void writeMembers(DataOutputStream out, List<GroupsState.Member> members) throws IOException {
        int count = Math.min(members.size(), MAX_MEMBERS);
        out.writeShort(count);
        for (int i = 0; i < count; i++) {
            GroupsState.Member member = members.get(i);
            out.writeLong(member.id.getMostSignificantBits());
            out.writeLong(member.id.getLeastSignificantBits());
            out.writeUTF(truncate(member.name, MAX_NAME));
            out.writeBoolean(member.online);
        }
    }

    private static List<GroupsState.Member> readMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > MAX_MEMBERS) throw new IOException("Too many members: " + count);
        List<GroupsState.Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = new UUID(in.readLong(), in.readLong());
            String name = in.readUTF();
            if (name.length() > MAX_NAME) throw new IOException("Name too long");
            members.add(new GroupsState.Member(id, name, in.readBoolean()));
        }
        return members;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String require(String value) throws IOException {
        if (value == null) throw new EOFException("Missing required string");
        return value;
    }
}
