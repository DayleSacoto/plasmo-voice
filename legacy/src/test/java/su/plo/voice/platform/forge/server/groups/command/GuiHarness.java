package su.plo.voice.platform.forge.server.groups.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.platform.forge.network.groups.GroupsProtocol;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.network.groups.GroupsState;
import su.plo.voice.platform.forge.server.groups.GroupActor;
import su.plo.voice.platform.forge.server.groups.GroupsGuiService;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

/** The Groups GUI service over the fake server: records every state and result "sent" to a player. */
final class GuiHarness implements GroupsGuiService.Players {
    final FakeServer server = new FakeServer();
    final GroupsManager manager = new GroupsManager(new GroupsNotifier(server));
    final Set<UUID> connected = new LinkedHashSet<>();
    final Map<UUID, List<byte[]>> states = new HashMap<>();
    final Map<UUID, List<GroupsResult>> results = new HashMap<>();
    long now = 1_000_000;
    private int nextRequest = 1;
    final GroupsGuiService service = new GroupsGuiService(manager, server, new GroupsNotifier(server), this,
            () -> now, null);

    /** An online, voice-connected player (the manager is told, like on login). */
    FakeServer.Source player(String name, boolean op) {
        FakeServer.Source source = server.player(name, op);
        connected.add(source.id());
        manager.playerOnline(source.profile);
        return source;
    }

    /** Sends the action as the player; every call is a second apart so the rate limit stays out of the way. */
    GroupsResult act(FakeServer.Source who, GroupsAction.Type type, java.util.function.UnaryOperator<Builder> fill) {
        now += 1000;
        int before = resultsOf(who).size();
        Builder b = fill.apply(new Builder(nextRequest++, type));
        service.handle(who.id(), b.build());
        List<GroupsResult> all = resultsOf(who);
        return all.size() > before ? all.get(all.size() - 1) : null;
    }

    GroupsResult act(FakeServer.Source who, GroupsAction.Type type) {
        return act(who, type, b -> b);
    }

    List<GroupsResult> resultsOf(FakeServer.Source who) {
        return results.computeIfAbsent(who.id(), k -> new ArrayList<>());
    }

    List<byte[]> statesOf(FakeServer.Source who) {
        return states.computeIfAbsent(who.id(), k -> new ArrayList<>());
    }

    GroupsState lastState(FakeServer.Source who) {
        List<byte[]> all = statesOf(who);
        if (all.isEmpty()) return null;
        try {
            return (GroupsState) GroupsProtocol.decode(all.get(all.size() - 1));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static String key(String relative) {
        return relative;
    }

    @Override
    public Collection<UUID> connected() {
        return new ArrayList<>(connected);
    }

    @Override
    public boolean isConnected(UUID player) {
        return connected.contains(player) && server.online.containsKey(player);
    }

    @Override
    public GroupActor actor(UUID player) {
        return server.online.get(player);
    }

    @Override
    public String translate(UUID player, String key, Object... args) {
        return server.translate(player, key, args);
    }

    @Override
    public void sendState(UUID player, byte[] encodedState) {
        states.computeIfAbsent(player, k -> new ArrayList<>()).add(encodedState);
    }

    @Override
    public void sendResult(UUID player, GroupsResult result) {
        results.computeIfAbsent(player, k -> new ArrayList<>()).add(result);
    }

    /** Fluent GroupsAction builder. */
    static final class Builder {
        final int requestId;
        final GroupsAction.Type type;
        UUID groupId;
        UUID playerId;
        String name;
        String password;
        boolean clearPassword;
        Boolean persistent;
        List<String> permissions;

        Builder(int requestId, GroupsAction.Type type) {
            this.requestId = requestId;
            this.type = type;
        }

        Builder group(UUID id) {
            groupId = id;
            return this;
        }

        Builder player(UUID id) {
            playerId = id;
            return this;
        }

        Builder name(String value) {
            name = value;
            return this;
        }

        Builder password(String value) {
            password = value;
            return this;
        }

        Builder clearPassword() {
            clearPassword = true;
            return this;
        }

        Builder persistent(boolean value) {
            persistent = value;
            return this;
        }

        Builder permissions(String... values) {
            permissions = java.util.Arrays.asList(values);
            return this;
        }

        GroupsAction build() {
            return new GroupsAction(requestId, type, groupId, playerId, name, password, clearPassword, persistent,
                    permissions);
        }
    }
}
