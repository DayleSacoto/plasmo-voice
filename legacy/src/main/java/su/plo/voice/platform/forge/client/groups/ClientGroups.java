package su.plo.voice.platform.forge.client.groups;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.platform.forge.network.groups.GroupsResult;
import su.plo.voice.platform.forge.network.groups.GroupsState;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.line.VoiceSourceLine;

/**
 * Client copy of the server's Groups state for the current connection. Written and read on the client game thread
 * only. Every {@link GroupsState} replaces the previous one; {@link #version()} changes on every update so screens
 * can rebuild cheaply. Holds no password: join and update passwords go straight into one action and are dropped.
 */
public final class ClientGroups {
    public static final String NAME = "groups";
    public static final UUID ACTIVATION_ID = VoiceActivation.generateId(NAME);
    public static final UUID LINE_ID = VoiceSourceLine.generateId(NAME);
    /** A request without an answer after this long no longer blocks the GUI. */
    public static final long REQUEST_TIMEOUT_MS = 10_000L;

    private static final ClientGroups INSTANCE = new ClientGroups();

    private GroupsState state;
    private int version;
    private int nextRequestId = 1;
    private int pendingRequestId;
    private long pendingSince;
    private GroupsResult lastResult;
    private Consumer<GroupsAction> sender = action -> {};

    public static ClientGroups get() {
        return INSTANCE;
    }

    /** True when the server advertises the groups activation (groups.enabled on the server). */
    public static boolean available(Collection<VoiceActivation> activations) {
        if (activations == null) return false;
        for (VoiceActivation activation : activations) {
            if (ACTIVATION_ID.equals(activation.getId())) return true;
        }
        return false;
    }

    public void setSender(Consumer<GroupsAction> sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    /** A {@link GroupsState} or {@link GroupsResult} from the server; anything else is ignored. */
    public void accept(Object message) {
        if (message instanceof GroupsState) {
            state = (GroupsState) message;
            version++;
        } else if (message instanceof GroupsResult) {
            GroupsResult result = (GroupsResult) message;
            if (result.requestId == pendingRequestId) pendingRequestId = 0;
            lastResult = result;
            version++;
        }
    }

    /** Disconnect or server change: forget everything. */
    public void reset() {
        state = null;
        pendingRequestId = 0;
        lastResult = null;
        version++;
    }

    /** Null until the first snapshot for this connection arrives. */
    public GroupsState state() {
        return state;
    }

    public boolean loaded() {
        return state != null;
    }

    public GroupsState.CurrentGroup current() {
        return state == null ? null : state.current;
    }

    public int version() {
        return version;
    }

    public GroupsResult lastResult() {
        return lastResult;
    }

    public void clearLastResult() {
        if (lastResult != null) {
            lastResult = null;
            version++;
        }
    }

    /** An action is waiting for its result (and has not timed out). */
    public boolean busy(long now) {
        return pendingRequestId != 0 && now - pendingSince < REQUEST_TIMEOUT_MS;
    }

    /** An action got no result within {@link #REQUEST_TIMEOUT_MS}. */
    public boolean timedOut(long now) {
        return pendingRequestId != 0 && now - pendingSince >= REQUEST_TIMEOUT_MS;
    }

    public int nextRequestId() {
        int id = nextRequestId++;
        if (nextRequestId <= 0) nextRequestId = 1;
        return id;
    }

    /**
     * Sends an action built with {@link #nextRequestId()}. REFRESH never marks the GUI busy; other actions do until
     * their result arrives.
     */
    public void submit(GroupsAction action, long now) {
        if (action.type != GroupsAction.Type.REFRESH) {
            pendingRequestId = action.requestId;
            pendingSince = now;
            lastResult = null;
            version++;
        }
        sender.accept(action);
    }
}
