package su.plo.voice.platform.forge.server.groups;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

/** Upstream groups Config: immutable settings with the upstream defaults. */
public final class GroupsSettings {
    private final boolean enabled;
    private final int activationWeight;
    private final int sourceLineWeight;
    private final String defaultGroupNameFormat;
    private final int minimumNameLength;
    private final int maximumNameLength;

    public GroupsSettings(boolean enabled, int activationWeight, int sourceLineWeight, String defaultGroupNameFormat,
                          int minimumNameLength, int maximumNameLength) {
        this.enabled = enabled;
        this.activationWeight = activationWeight;
        this.sourceLineWeight = sourceLineWeight;
        this.defaultGroupNameFormat = defaultGroupNameFormat;
        this.minimumNameLength = minimumNameLength;
        this.maximumNameLength = maximumNameLength;
    }

    public static GroupsSettings defaults() {
        return new GroupsSettings(true, 10, 10, "%player%'s group", 3, 32);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getActivationWeight() {
        return activationWeight;
    }

    public int getSourceLineWeight() {
        return sourceLineWeight;
    }

    public String getDefaultGroupNameFormat() {
        return defaultGroupNameFormat;
    }

    public int getMinimumNameLength() {
        return minimumNameLength;
    }

    public int getMaximumNameLength() {
        return maximumNameLength;
    }
}
