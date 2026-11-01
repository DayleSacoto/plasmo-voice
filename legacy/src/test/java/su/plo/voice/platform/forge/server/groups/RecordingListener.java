package su.plo.voice.platform.forge.server.groups;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test listener recording every callback. */
final class RecordingListener implements GroupsListener {
    final List<String> groupNotices = new ArrayList<>();
    final List<String> playerNotices = new ArrayList<>();
    final List<GroupRoute> removedSources = new ArrayList<>();
    int routesChanged;
    int changed;

    @Override
    public void notifyGroup(Group group, String key, Object... args) {
        groupNotices.add(key + (args.length == 0 ? "" : ":" + args[0]));
    }

    @Override
    public void notifyPlayer(UUID player, String key, Object... args) {
        playerNotices.add(player + "/" + key);
    }

    @Override
    public void routesChanged(Group group) {
        routesChanged++;
    }

    @Override
    public void sourceRemoved(GroupRoute route) {
        removedSources.add(route);
    }

    @Override
    public void changed() {
        changed++;
    }

    boolean noticed(String key) {
        for (String n : groupNotices) {
            if (n.equals(key) || n.startsWith(key + ":")) return true;
        }
        return false;
    }
}
