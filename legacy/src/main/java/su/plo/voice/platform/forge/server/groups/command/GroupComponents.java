package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import su.plo.voice.platform.forge.server.groups.Group;
import su.plo.voice.platform.forge.server.groups.GroupProfile;
import su.plo.voice.platform.forge.server.groups.GroupsManager;

/** Upstream Group.asTextComponents / buttons as 1.7.10 chat components. */
final class GroupComponents {
    static final String PREFIX = GroupsNotifier.PREFIX;
    /** Stands in for a component argument of a translated message; the component is spliced in at its place. */
    static final String MARKER = "\u0001";
    static final int PER_PAGE = 3;

    private GroupComponents() {
    }

    static IChatComponent text(String text) {
        return new ChatComponentText(text);
    }

    static IChatComponent clickable(IChatComponent component, ClickEvent.Action action, String command, IChatComponent hover) {
        ChatStyle style = new ChatStyle().setChatClickEvent(new ClickEvent(action, command));
        if (hover != null) style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
        component.setChatStyle(style);
        return component;
    }

    static IChatComponent divider(Translator tr) {
        return text(tr.translate(PREFIX + "divider"));
    }

    static IChatComponent inline(Group group, Translator tr) {
        return text(tr.translate(PREFIX + "format.group_name", group.getName()));
    }

    /** Upstream Group.joinButton: a group with a password asks the player to type it. */
    static IChatComponent joinButton(Group group, Translator tr) {
        String command = "/groups join " + group.getId();
        IChatComponent hover = text(command);
        return group.getPassword() == null
                ? clickable(text(tr.translate(PREFIX + "button.join")), ClickEvent.Action.RUN_COMMAND, command, hover)
                : clickable(text(tr.translate(PREFIX + "button.join")), ClickEvent.Action.SUGGEST_COMMAND, command + " ", hover);
    }

    /** Upstream Group.joinButtonWithPassword: the click embeds the current password, the hover never shows it. */
    static IChatComponent joinButtonWithPassword(Group group, Translator tr) {
        String command = "/groups join " + group.getId();
        String password = group.getPassword();
        IChatComponent label = text(tr.translate(PREFIX + "button.join"));
        return password == null
                ? clickable(label, ClickEvent.Action.RUN_COMMAND, command, text(command))
                : clickable(label, ClickEvent.Action.RUN_COMMAND, command + " " + password, text(command + " *******"));
    }

    static IChatComponent leaveButton(Translator tr) {
        IChatComponent line = text("");
        line.appendSibling(clickable(text(tr.translate(PREFIX + "button.leave")), ClickEvent.Action.RUN_COMMAND,
                "/groups leave", text("/groups leave")));
        line.appendSibling(text("  "));
        line.appendSibling(clickable(text(tr.translate(PREFIX + "button.invite")), ClickEvent.Action.SUGGEST_COMMAND,
                "/groups invite ", text("/groups invite")));
        return line;
    }

    /** The invitation line: format.invite with the group component spliced into its second argument. */
    static IChatComponent invitation(String inviter, Group group, Translator tr) {
        String message = tr.translate(PREFIX + "format.invite", inviter, MARKER);
        int at = message.indexOf(MARKER);
        if (at < 0) return text(message);
        IChatComponent line = text("");
        line.appendSibling(text(message.substring(0, at)));
        line.appendSibling(inline(group, tr));
        line.appendSibling(text(message.substring(at + MARKER.length())));
        return line;
    }

    /**
     * @param viewer the profile of whoever looks at the group, or null for the console
     */
    static List<IChatComponent> describe(Group group, GroupProfile viewer, GroupsManager manager,
                                         GroupsPlatform platform, Translator tr) {
        Map<UUID, String> names = new HashMap<>();
        for (GroupProfile profile : platform.onlinePlayers()) names.put(profile.getId(), profile.getName());

        IChatComponent title = text("");
        if (group.getPassword() != null) {
            IChatComponent hover = text(tr.translate(PREFIX + "tooltip.password_protected"));
            title.appendSibling(show(text(tr.translate(PREFIX + "icons.password_protected")), hover));
            title.appendSibling(show(text(" "), hover));
        }
        IChatComponent name = inline(group, tr);
        name.setChatStyle(new ChatStyle()
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        text(tr.translate(PREFIX + "tooltip.group_uuid", group.getId().toString()))))
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, group.getId().toString())));
        title.appendSibling(name);

        List<String> online = new ArrayList<>();
        for (UUID member : group.onlineMembers()) {
            String memberName = names.get(member);
            if (memberName != null) online.add(memberName);
        }
        Collections.sort(online, String.CASE_INSENSITIVE_ORDER);

        GroupProfile owner = group.getOwner();
        boolean ownerOnline = owner != null && names.containsKey(owner.getId());
        IChatComponent players = text(ownerOnline
                ? tr.translate(PREFIX + "format.players_and_owner", group.onlineCount(), owner.getName())
                : tr.translate(PREFIX + "format.only_players", group.onlineCount()));
        show(players, text(String.join(", ", online)));

        boolean inGroup = viewer != null && manager.groupOf(viewer.getId()) == group;
        List<IChatComponent> lines = new ArrayList<>();
        lines.add(title);
        lines.add(players);
        lines.add(inGroup ? leaveButton(tr) : joinButton(group, tr));
        return lines;
    }

    static IChatComponent pageInfo(int page, int pages, Translator tr) {
        IChatComponent line = text("");
        if (page > 1) {
            String command = "/groups browse " + (page - 1);
            line.appendSibling(clickable(text(tr.translate(PREFIX + "button.prev") + " "),
                    ClickEvent.Action.RUN_COMMAND, command, text(command)));
        }
        line.appendSibling(text(tr.translate(PREFIX + "format.page", page, pages)));
        if (page < pages) {
            String command = "/groups browse " + (page + 1);
            line.appendSibling(clickable(text(" " + tr.translate(PREFIX + "button.next")),
                    ClickEvent.Action.RUN_COMMAND, command, text(command)));
        }
        return line;
    }

    private static IChatComponent show(IChatComponent component, IChatComponent hover) {
        component.setChatStyle(new ChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
        return component;
    }
}
