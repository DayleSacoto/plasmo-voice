package su.plo.voice.platform.forge.server.groups.command;

import java.util.Collections;
import java.util.List;

import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

/** Real manager and command over the fake server; group notifications go through the real notifier. */
abstract class CommandTestBase {
    final FakeServer server = new FakeServer();
    final GroupsManager manager = new GroupsManager(new GroupsNotifier(server));
    final GroupsCommand command = new GroupsCommand(manager, server);

    void run(FakeServer.Source source, String line) {
        command.execute(source, line.isEmpty() ? new String[0] : line.split(" "));
    }

    List<String> suggest(FakeServer.Source source, String... args) {
        return command.suggest(source, args);
    }

    /** A group created by the player through the command. */
    Group created(FakeServer.Source owner, String name) {
        run(owner, "create name:" + name);
        return manager.groupOf(owner.id());
    }

    /** Puts an online player into the group through the command, wiping the messages this produced. */
    void joined(FakeServer.Source player, Group group) {
        run(player, "join " + group.getId() + (group.getPassword() == null ? "" : " " + group.getPassword()));
        for (FakeServer.Source source : server.online.values()) source.clear();
    }

    static void clear(FakeServer.Source... sources) {
        for (FakeServer.Source source : sources) source.clear();
    }

    static List<String> list(String... items) {
        return java.util.Arrays.asList(items);
    }

    static List<String> none() {
        return Collections.emptyList();
    }

    static IChatComponent last(FakeServer.Source source) {
        return source.received.get(source.received.size() - 1);
    }
}
