package su.plo.voice.platform.forge.server.groups;

import java.util.UUID;

/** Upstream McGameProfile: a player id with the name seen last. Equality is by id only. */
public final class GroupProfile {
    private final UUID id;
    private final String name;

    public GroupProfile(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GroupProfile && ((GroupProfile) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + "(" + id + ")";
    }
}
