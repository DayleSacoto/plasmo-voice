package su.plo.voice.platform.forge.server.groups;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The permission rules every group entry point shares (the /groups commands, the Groups GUI actions and the GUI
 * snapshot capability bits), so a rule is written exactly once.
 */
public final class GroupRules {
    /** Online count descending, then name, then id: deterministic. */
    public static final Comparator<Group> BROWSE_ORDER = new Comparator<Group>() {
        @Override
        public int compare(Group a, Group b) {
            int byOnline = Integer.compare(b.onlineCount(), a.onlineCount());
            if (byOnline != 0) return byOnline;
            int byName = a.getName().compareToIgnoreCase(b.getName());
            return byName != 0 ? byName : a.getId().compareTo(b.getId());
        }
    };

    private GroupRules() {
    }

    /** Upstream hasAddonPermission: the wildcard or the node. */
    public static boolean has(GroupActor actor, String sub) {
        return actor.hasPermission(GroupPermissions.addon("*")) || actor.hasPermission(GroupPermissions.addon(sub));
    }

    public static boolean hasFlag(GroupActor actor, String flag) {
        return has(actor, "flag." + flag);
    }

    /** set / unset / delete / transfer / kick / ban / unban: the owner node for the owner, otherwise .all or .* */
    public static boolean mayManage(GroupActor actor, String prefix, boolean owner) {
        return (owner && has(actor, prefix + ".owner")) || has(actor, prefix + ".all") || has(actor, prefix + ".*");
    }

    /** invite / info: .* or .member for any member, .owner for the owner */
    public static boolean maySee(GroupActor actor, String prefix, boolean owner) {
        return has(actor, prefix + ".*") || has(actor, prefix + ".member") || (owner && has(actor, prefix + ".owner"));
    }

    /** The groups /groups browse lists for this actor, in browse order. Empty without the browse node. */
    public static List<Group> browseVisible(Collection<Group> groups, GroupActor actor) {
        if (!has(actor, "browse")) return Collections.emptyList();
        boolean all = has(actor, "browse.all") || has(actor, "browse.*");
        List<Group> visible = new ArrayList<>();
        for (Group group : groups) {
            if (all || group.hasPermission(actor::hasPermission)) visible.add(group);
        }
        Collections.sort(visible, BROWSE_ORDER);
        return visible;
    }
}
