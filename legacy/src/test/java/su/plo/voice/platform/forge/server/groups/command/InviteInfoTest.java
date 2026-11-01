package su.plo.voice.platform.forge.server.groups.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.click;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.hover;
import static su.plo.voice.platform.forge.server.groups.command.FakeServer.msg;

import java.util.Arrays;
import java.util.Collections;

import net.minecraft.event.ClickEvent;
import net.minecraft.util.IChatComponent;
import org.junit.Test;
import su.plo.voice.platform.forge.server.groups.Group;

public class InviteInfoTest extends CommandTestBase {
    private final FakeServer.Source alice = server.player("Alice", false);
    private final FakeServer.Source bob = server.player("Bob", false);
    private final FakeServer.Source carol = server.player("Carol", false);

    // ---- invite ----

    @Test
    public void inviteUsageSelfAndUnknownPlayer() {
        created(alice, "Alpha");
        clear(alice);

        run(alice, "invite");
        run(alice, "invite alice");
        run(alice, "invite Nobody");

        assertEquals(Arrays.asList(msg("command.invite.error.usage"), msg("command.invite.error.invite_self"),
                msg("error.player_not_found")), alice.texts());
    }

    @Test
    public void inviteNeedsAGroupAndAPlayer() {
        run(alice, "invite Bob");
        FakeServer.Source console = server.console();
        run(console, "invite Bob");
        assertEquals(Collections.singletonList(msg("error.not_in_group")), alice.texts());
        assertEquals(Collections.singletonList("pv.error.player_only_command"), console.texts());
    }

    @Test
    public void inviteRejectsMembersAndBannedPlayers() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        manager.ban(group, server.onlinePlayer("Carol"));
        clear(alice, bob, carol);

        run(alice, "invite Bob");
        run(alice, "invite Carol");

        assertEquals(Arrays.asList(msg("command.invite.error.already_joined"), msg("command.invite.error.banned")),
                alice.texts());
        assertTrue(bob.received.isEmpty());
        assertTrue(carol.received.isEmpty());
    }

    @Test
    public void inviteChecksTheMemberAndOwnerPermissions() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        bob.deny("pv.addon.groups.invite.member");
        run(bob, "invite Carol");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.invite.member")), bob.texts());

        alice.deny("pv.addon.groups.invite.owner").deny("pv.addon.groups.invite.member");
        clear(alice);
        run(alice, "invite Carol");
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.invite.owner")), alice.texts());
        assertTrue(carol.received.isEmpty());

        alice.grant("pv.addon.groups.invite.*");
        run(alice, "invite Carol");
        assertFalse(carol.received.isEmpty());
    }

    @Test
    public void inviteSendsTheInvitationInTheInviteesLanguage() {
        Group group = created(alice, "Alpha");
        server.languages.put(carol.id(), "ru");
        clear(alice);

        run(alice, "invite carol");

        assertEquals(Collections.singletonList(msg("command.invite.success", "Carol")), alice.texts());
        assertEquals(4, carol.received.size());
        assertEquals("ru:" + msg("divider"), carol.texts().get(0));
        assertEquals("ru:" + msg("format.invite", "Alice", "").replace(")", "") + "ru:" + msg("format.group_name", "Alpha") + ")",
                carol.texts().get(1));
        assertEquals("ru:" + msg("button.join"), carol.texts().get(2));
        assertEquals("ru:" + msg("divider"), carol.texts().get(3));
        assertFalse(group.getMembers().contains(carol.id()));
    }

    @Test
    public void inviteButtonClickEmbedsTheCurrentPasswordButTheHoverNeverShowsIt() {
        run(alice, "create name:Alpha password:secret");
        Group group = manager.groupOf(alice.id());
        clear(alice);

        run(alice, "invite Bob");
        IChatComponent button = bob.received.get(2);
        ClickEvent click = click(button, msg("button.join"));
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
        assertEquals("/groups join " + group.getId() + " secret", click.getValue());
        assertEquals("/groups join " + group.getId() + " *******", hover(button, msg("button.join")));

        // the password changes after the group was created: the next invitation carries the new one
        run(alice, "set password zzpw9");
        bob.clear();
        run(alice, "invite Bob");
        assertEquals("/groups join " + group.getId() + " zzpw9", click(bob.received.get(2), msg("button.join")).getValue());

        // and nothing else leaks the password
        for (FakeServer.Source source : Arrays.asList(alice, bob)) {
            for (String text : source.texts()) {
                assertFalse(text, text.contains("secret"));
                assertFalse(text, text.contains("zzpw9"));
            }
        }
    }

    @Test
    public void inviteToAnOpenGroupClicksAPlainJoin() {
        Group group = created(alice, "Alpha");
        run(alice, "invite Bob");
        ClickEvent click = click(bob.received.get(2), msg("button.join"));
        assertEquals("/groups join " + group.getId(), click.getValue());
        assertEquals("/groups join " + group.getId(), hover(bob.received.get(2), msg("button.join")));
    }

    // ---- info ----

    @Test
    public void infoShowsTheGroupBetweenDividers() {
        Group group = created(alice, "Alpha");
        joined(bob, group);

        run(bob, "info");

        assertEquals(Arrays.asList(msg("divider"), msg("format.group_name", "Alpha"),
                msg("format.players_and_owner", 2, "Alice"), msg("button.leave") + "  " + msg("button.invite"),
                msg("divider")), bob.texts());
    }

    @Test
    public void infoOutsideAGroupOrAsConsole() {
        run(bob, "info");
        FakeServer.Source console = server.console();
        run(console, "info");
        assertEquals(Collections.singletonList(msg("error.not_in_group")), bob.texts());
        assertEquals(Collections.singletonList("pv.error.player_only_command"), console.texts());
    }

    @Test
    public void infoPermissionErrorsNameTheFullNode() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        bob.deny("pv.addon.groups.info.member");
        alice.deny("pv.addon.groups.info.owner").deny("pv.addon.groups.info.member");
        clear(alice);

        run(bob, "info");
        run(alice, "info");

        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.info.member")), bob.texts());
        assertEquals(Collections.singletonList(msg("error.no_permission", "pv.addon.groups.info.owner")), alice.texts());
    }

    @Test
    public void groupTextHasTooltipsAndClickTargets() {
        run(alice, "create name:Alpha password:secret");
        Group group = manager.groupOf(alice.id());
        joined(bob, group);

        run(bob, "info");

        IChatComponent title = bob.received.get(1);
        assertEquals(msg("icons.password_protected") + " " + msg("format.group_name", "Alpha"), title.getUnformattedText());
        assertEquals(msg("tooltip.password_protected"), hover(title, msg("icons.password_protected")));
        ClickEvent name = click(title, msg("format.group_name", "Alpha"));
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, name.getAction());
        assertEquals(group.getId().toString(), name.getValue());
        assertEquals(msg("tooltip.group_uuid", group.getId()), hover(title, msg("format.group_name", "Alpha")));

        IChatComponent players = bob.received.get(2);
        assertEquals("Alice, Bob", hover(players, msg("format.players_and_owner", 2, "Alice")));

        IChatComponent buttons = bob.received.get(3);
        ClickEvent leave = click(buttons, msg("button.leave"));
        assertEquals(ClickEvent.Action.RUN_COMMAND, leave.getAction());
        assertEquals("/groups leave", leave.getValue());
        ClickEvent invite = click(buttons, msg("button.invite"));
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, invite.getAction());
        assertEquals("/groups invite ", invite.getValue());
    }

    @Test
    public void ownerOnlyShowsWhenOnline() {
        Group group = created(alice, "Alpha");
        joined(bob, group);
        server.logout(alice);

        run(bob, "info");

        assertEquals(msg("format.only_players", 2), bob.texts().get(2));
    }
}
