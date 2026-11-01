package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.Group;

public class SuggestAndNotifyTest extends CommandTestBase {
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);
    private final FakeServer.Source carol = server.player("Carol", false);
    private final FakeServer.Source op = server.player("Root", true);

    // ---- subcommand names ----

    @Test
    public void outsideAGroupOnlyBrowseCreateAndJoinAreOffered() {
        assertEquals(list("browse", "create", "join"), suggest(alice));
        assertEquals(list("browse", "create", "join"), suggest(alice, ""));
        assertEquals(list("join"), suggest(alice, "jo"));
        assertEquals(none(), suggest(alice, "x"));
    }

    @Test
    public void membersGetLeaveInviteAndInfo() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        assertEquals(list("browse", "create", "join", "leave", "invite", "info"), suggest(bob, ""));
    }

    @Test
    public void ownersGetTheManagementCommands() {
        created(alice, "Alpha");
        assertEquals(list("browse", "create", "join", "leave", "invite", "info", "set", "unset", "delete",
                "transfer", "kick", "ban", "unban"), suggest(alice, ""));
    }

    @Test
    public void deniedCommandsAreNotOffered() {
        alice.deny("pv.addon.groups.create").deny("pv.addon.groups.browse");
        assertEquals(list("join"), suggest(alice, ""));
    }

    @Test
    public void unknownSubcommandsSuggestNothing() {
        assertEquals(none(), suggest(alice, "frobnicate", ""));
    }

    // ---- arguments ----

    @Test
    public void createOffersTheFlagsTheSourceMayUse() {
        assertEquals(list("name:", "password:"), suggest(alice, "create", ""));
        assertEquals(list("name:", "password:", "permissions:", "persistent:"), suggest(op, "create", ""));
        assertEquals(list("password:", "permissions:", "persistent:"), suggest(op, "create", "p"));
        assertEquals(list("persistent:"), suggest(op, "create", "pers"));
        assertEquals(list("name:"), suggest(alice, "create", "n"));
    }

    @Test
    public void createHintsTheValueOfTheCurrentFlag() {
        assertEquals(list(msg("arg.name")), suggest(alice, "create", "name:", ""));
        assertEquals(list(msg("arg.password")), suggest(alice, "create", "password:", ""));
        assertEquals(list(msg("arg.permissions")), suggest(op, "create", "permissions:", ""));
        assertEquals(list("true", "false"), suggest(op, "create", "persistent:", ""));
        assertEquals(none(), suggest(alice, "create", "persistent:", ""));
    }

    @Test
    public void setOffersFlagsAndValueHints() {
        created(alice, "Alpha");
        assertEquals(list("name", "password"), suggest(alice, "set", ""));
        assertEquals(list("password"), suggest(alice, "set", "pa"));
        assertEquals(list(msg("arg.name")), suggest(alice, "set", "name", ""));
        assertEquals(list(msg("arg.password")), suggest(alice, "set", "password", ""));
        assertEquals(none(), suggest(alice, "set", "persistent", ""));
    }

    @Test
    public void unsetOffersItsFlags() {
        assertEquals(list("password", "permissions"), suggest(op, "unset", ""));
        assertEquals(list("password"), suggest(alice, "unset", ""));
    }

    @Test
    public void joinHintsTheUuidAndOnlyProtectedGroupsAskForAPassword() {
        run(alice, "create name:Locked password:pw");
        Group locked = manager.groupOf(alice.id());
        Group open = created(op, "Open");

        assertEquals(list(msg("arg.groups_uuid")), suggest(bob, "join", ""));
        assertEquals(list(msg("arg.password")), suggest(bob, "join", locked.getId().toString(), ""));
        assertEquals(none(), suggest(bob, "join", open.getId().toString(), ""));
        assertEquals(none(), suggest(bob, "join", "garbage", ""));
    }

    @Test
    public void browseHintsThePage() {
        assertEquals(list(msg("arg.page")), suggest(alice, "browse", ""));
    }

    @Test
    public void playerArgumentsFilterByPrefixAndRole() {
        Group group = created(alice, "Alpha");
        joined(bob, group);

        assertEquals(list("Carol", "Root"), suggest(alice, "invite", ""));
        assertEquals(list("Carol"), suggest(alice, "invite", "ca"));
        assertEquals(list("Bob"), suggest(alice, "kick", ""));
        assertEquals(list("Bob", "Carol", "Root"), suggest(alice, "ban", ""));
        assertEquals(list("Bob", "Carol", "Root"), suggest(alice, "transfer", ""));
        assertEquals(none(), suggest(alice, "unban", ""));
        run(alice, "ban Carol");
        assertEquals(list("Carol"), suggest(alice, "unban", ""));
        assertEquals(list("Bob", "Root"), suggest(alice, "ban", ""));
    }

    // ---- notifier ----

    @Test
    public void groupNotificationsAreTranslatedPerMember() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        server.languages.put(alice.id(), "ru");
        server.languages.put(bob.id(), "de");

        new GroupsNotifier(server).notifyGroup(group, "notifications.new_owner", "Bob");

        assertEquals(Collections.singletonList("ru:" + msg("format.group_name", "Alpha") + " ru:" + msg("notifications.new_owner", "Bob")),
                alice.texts());
        assertEquals(Collections.singletonList("de:" + msg("format.group_name", "Alpha") + " de:" + msg("notifications.new_owner", "Bob")),
                bob.texts());
        assertTrue(carol.received.isEmpty());
    }

    @Test
    public void groupNotificationsSkipOfflineMembers() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        server.logout(bob);

        new GroupsNotifier(server).notifyGroup(group, "notifications.group_deleted");

        assertEquals(1, alice.received.size());
        assertTrue(bob.received.isEmpty());
    }

    @Test
    public void playerNotificationsGoToOnePlayer() {
        new GroupsNotifier(server).notifyPlayer(carol.id(), "notifications.kicked");
        assertEquals(Arrays.asList(msg("notifications.kicked")), carol.texts());
        assertTrue(alice.received.isEmpty());
    }
}
