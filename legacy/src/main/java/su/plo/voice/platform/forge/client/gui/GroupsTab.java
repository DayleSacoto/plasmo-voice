package su.plo.voice.platform.forge.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.platform.forge.client.groups.ClientGroups;
import su.plo.voice.platform.forge.client.groups.GroupsForms;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.Control;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.GroupRow;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.GroupView;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.MemberRow;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.Reason;
import su.plo.voice.platform.forge.client.groups.GroupsPresenter.Status;
import su.plo.voice.platform.forge.client.hud.PlayerHeads;
import su.plo.voice.platform.forge.client.lang.VoiceLang;
import su.plo.voice.platform.forge.network.groups.GroupsAction;
import su.plo.voice.proto.data.player.VoicePlayerInfo;

/**
 * Voice groups: browse, create, join and, inside a group, members, invitations and the owner's settings. All state
 * comes from {@link ClientGroups}; the tab asks for a fresh snapshot when opened, then the server pushes every change
 * and the tab rebuilds when the version changes. The server validates everything; the controls only mirror its
 * capability bits.
 */
@SideOnly(Side.CLIENT)
final class GroupsTab extends SettingsTab {
    private static final String KEY = "gui.plasmovoice.groups.";
    /** Member head size; the speaking outline is drawn one pixel outside it. */
    private static final int HEAD_SIZE = 16;
    private static final int STATUS_HEIGHT = 18;
    private static final int FIELD_WIDTH = 160;
    private static final int PASSWORD_MAX = 64;
    private static final int PERMISSIONS_MAX = 256;
    private static final long CONFIRM_MS = 5000L;
    private static final int OK_COLOR = 0x55FF55;
    private static final int ERROR_COLOR = 0xFF5555;
    private static final int WAIT_COLOR = 0xFFFF55;
    private static final int MUTED = 0xA0A0A0;

    private enum Mode { MAIN, CREATE, JOIN }

    private final ClientGroups groups = ClientGroups.get();
    private final FontRenderer font = Minecraft.getMinecraft().fontRenderer;
    private final TextFieldWidget createName;
    private final TextFieldWidget createPassword;
    private final TextFieldWidget createPermissions;
    private final TextFieldWidget joinPassword;
    private final TextFieldWidget settingsName;
    private final TextFieldWidget settingsPassword;
    private final TextFieldWidget settingsPermissions;
    private final List<TextFieldWidget> fields;

    private Mode mode = Mode.MAIN;
    private boolean createPersistent;
    private UUID joinGroupId;
    private String joinGroupName = "";
    private UUID invitePick;
    private UUID transferPick;
    private boolean confirmDelete;
    private long confirmUntil;
    private String localError;

    private GroupsPresenter.View view;
    private boolean dirty;
    private int builtVersion = -1;
    private boolean builtBusy;
    private boolean builtTimedOut;
    private boolean builtAvailable;
    private Map<UUID, String> builtPlayers = Collections.emptyMap();
    private Set<UUID> speakingNow = Collections.emptySet();
    private boolean selfSpeakingNow;
    private int bottomEdge;
    private UUID syncedGroup;
    private String syncedName = "";
    private String syncedPermissions = "";

    GroupsTab(VoiceSettingsScreen screen, ClientState state) {
        super(screen, state);
        createName = new TextFieldWidget(font, FIELD_WIDTH, text("create.name.hint"), value -> { });
        createPassword = new TextFieldWidget(font, FIELD_WIDTH, text("create.password.hint"), value -> { });
        createPermissions = new TextFieldWidget(font, FIELD_WIDTH, text("create.permissions.hint"), value -> { });
        joinPassword = new TextFieldWidget(font, FIELD_WIDTH, text("join.password.hint"), value -> { });
        settingsName = new TextFieldWidget(font, 118, "", value -> { });
        settingsPassword = new TextFieldWidget(font, 98, text("settings.password.hint"), value -> { });
        settingsPermissions = new TextFieldWidget(font, 118, text("create.permissions.hint"), value -> { });
        createPassword.setMasked(true);
        joinPassword.setMasked(true);
        settingsPassword.setMasked(true);
        createPassword.setMaxLength(PASSWORD_MAX);
        joinPassword.setMaxLength(PASSWORD_MAX);
        settingsPassword.setMaxLength(PASSWORD_MAX);
        createPermissions.setMaxLength(PERMISSIONS_MAX);
        settingsPermissions.setMaxLength(PERMISSIONS_MAX);
        joinPassword.setOnEnter(this::doJoin);
        settingsName.setOnEnter(this::doRename);
        settingsPassword.setOnEnter(this::doSetPassword);
        settingsPermissions.setOnEnter(this::doSetPermissions);
        fields = new ArrayList<>();
        Collections.addAll(fields, createName, createPassword, createPermissions, joinPassword, settingsName,
                settingsPassword, settingsPermissions);
    }

    private static String text(String key, Object... args) {
        return VoiceLang.format(KEY + key, args);
    }

    private static long now() {
        return Minecraft.getSystemTime();
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    void init(int top, int bottom) {
        bottomEdge = bottom;
        Keyboard.enableRepeatEvents(true);
        groups.clearLastResult();
        localError = null;
        super.init(top, bottom);
        refresh();
    }

    @Override
    void removed() {
        super.removed();
        Keyboard.enableRepeatEvents(false);
        closeMode();
        settingsPassword.clear();
        confirmDelete = false;
        for (TextFieldWidget field : fields) field.setFocused(false);
    }

    private void refresh() {
        groups.submit(GroupsAction.simple(groups.nextRequestId(), GroupsAction.Type.REFRESH), now());
    }

    @Override
    void tick() {
        for (TextFieldWidget field : fields) field.tick();
        if (confirmDelete && now() > confirmUntil) {
            confirmDelete = false;
            dirty = true;
        }

        ClientConnectionState connection = state.getConnection();
        Set<UUID> speaking = connection == null || connection.getSources() == null
                ? Collections.<UUID>emptySet() : connection.getSources().speakingOn(ClientGroups.LINE_ID);
        speakingNow = speaking;
        selfSpeakingNow = state.getActiveActivations().contains(ClientGroups.ACTIVATION_ID);

        long now = now();
        if (dirty || groups.version() != builtVersion || groups.busy(now) != builtBusy
                || groups.timedOut(now) != builtTimedOut || isAvailable() != builtAvailable
                || !voicePlayers().equals(builtPlayers)) {
            rebuild();
        }
    }

    /** Cheap check every frame, so a click is reflected before the next game tick. */
    private void refreshIfNeeded() {
        if (dirty || groups.version() != builtVersion) rebuild();
    }

    // ---- inputs ----------------------------------------------------------------------------------------------

    @Override
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (statusText() != null && mouseY >= bottomEdge - STATUS_HEIGHT) return true;
        for (TextFieldWidget field : fields) {
            if (!field.isMouseOver(mouseX, mouseY)) field.setFocused(false);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    boolean keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (mode != Mode.MAIN) {
                closeMode();
                dirty = true;
                return true;
            }
            for (TextFieldWidget field : fields) {
                if (field.isFocused()) {
                    field.setFocused(false);
                    return true;
                }
            }
        }
        return super.keyTyped(typedChar, keyCode);
    }

    // ---- view ------------------------------------------------------------------------------------------------

    private boolean isAvailable() {
        ClientConnectionState connection = state.getConnection();
        ClientConfig config = connection == null ? null : connection.getConfig();
        return config != null && ClientGroups.available(config.getPacket().getActivations());
    }

    private Map<UUID, String> voicePlayers() {
        Map<UUID, String> players = new LinkedHashMap<>();
        ClientConnectionState connection = state.getConnection();
        if (connection == null) return players;
        for (VoicePlayerInfo player : connection.getPlayers()) players.put(player.getPlayerId(), player.getPlayerNick());
        return players;
    }

    private UUID selfId() {
        ClientConnectionState connection = state.getConnection();
        Minecraft mc = Minecraft.getMinecraft();
        if (connection == null || mc.thePlayer == null) return null;
        return connection.localPlayerId(mc.thePlayer.getCommandSenderName(), mc.thePlayer.getUniqueID());
    }

    @Override
    void build() {
        long now = now();
        builtVersion = groups.version();
        builtBusy = groups.busy(now);
        builtTimedOut = groups.timedOut(now);
        builtAvailable = isAvailable();
        builtPlayers = voicePlayers();
        dirty = false;
        view = GroupsPresenter.build(builtAvailable, groups.state(), selfId(), builtBusy, speakingNow,
                selfSpeakingNow, builtPlayers);

        if (view.screen != GroupsPresenter.Screen.BROWSE && mode != Mode.MAIN) closeMode();
        if (mode == Mode.JOIN && view.groupRow(joinGroupId) == null) closeMode();
        if (view.screen != GroupsPresenter.Screen.GROUP) syncedGroup = null;

        switch (view.screen) {
            case UNAVAILABLE:
                addCategory("gui.plasmovoice.groups");
                addRow(new GroupsRows.TextRow(font, text("unavailable"), MUTED, true));
                break;
            case LOADING:
                addCategory("gui.plasmovoice.groups");
                addRow(new GroupsRows.TextRow(font, text("loading"), MUTED, true));
                break;
            case BROWSE:
                if (mode == Mode.CREATE) buildCreate();
                else if (mode == Mode.JOIN) buildJoin();
                else buildBrowse();
                break;
            case GROUP:
                buildGroup();
                break;
            default:
                break;
        }
        addRow(new GroupsRows.SpacerRow(STATUS_HEIGHT + 4));
    }

    // ---- not in a group --------------------------------------------------------------------------------------

    private void buildBrowse() {
        addCategory("gui.plasmovoice.groups");
        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        Widget create = button(CONTAINER_WIDTH, text("create_button"), view.create, this::openCreate, tips);
        addRow(new GroupsRows.ButtonsRow(Collections.singletonList(create), tips));

        addCategory(KEY + "available");
        if (view.browseTruncated()) {
            addRow(new GroupsRows.TextRow(font, text("available.truncated", view.groups.size(), view.browseTotal),
                    MUTED, true));
        }
        if (!view.canBrowse) {
            addRow(new GroupsRows.TextRow(font, text("browse_denied"), MUTED, true));
        } else if (view.emptyBrowse()) {
            addRow(new GroupsRows.TextRow(font, text("empty"), MUTED, true));
        }
        for (GroupRow row : view.groups) addRow(groupEntry(row));
    }

    private SettingsTab.Row groupEntry(GroupRow row) {
        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        Widget join = button(52, text("join"), row.join, () -> onJoin(row), tips);
        GroupsRows.Marker marker = (mc, x, centerY) -> {
            if (row.hasPassword) GroupsRows.drawLock(x + 1, centerY);
            return 12;
        };
        return new GroupsRows.EntryRow(marker, () -> row.name, () -> row.online + "/" + row.members, MUTED,
                Collections.singletonList(join), tips, () -> groupTooltip(row));
    }

    private String groupTooltip(GroupRow row) {
        StringBuilder tip = new StringBuilder(row.name);
        tip.append('\n').append(EnumChatFormatting.GRAY).append(text("tip.id", row.id));
        tip.append('\n').append(EnumChatFormatting.GRAY)
                .append(row.ownerName == null ? text("tip.no_owner") : text("tip.owner", row.ownerName));
        tip.append('\n').append(EnumChatFormatting.GRAY).append(text("tip.online", row.online, row.members));
        if (row.hasPassword) tip.append('\n').append(EnumChatFormatting.GOLD).append(text("tip.password"));
        if (row.persistent) tip.append('\n').append(EnumChatFormatting.GRAY).append(text("tip.persistent"));
        return tip.toString();
    }

    private void onJoin(GroupRow row) {
        if (!row.join.enabled) return;
        if (row.needsPassword()) {
            mode = Mode.JOIN;
            joinGroupId = row.id;
            joinGroupName = row.name;
            joinPassword.clear();
            localError = null;
            unfocusAll();
            joinPassword.setFocused(true);
            dirty = true;
        } else {
            submit(GroupsForms.join(groups.nextRequestId(), row.id, null));
        }
    }

    private void openCreate() {
        if (!view.create.enabled) return;
        mode = Mode.CREATE;
        createName.clear();
        createPassword.clear();
        createPermissions.clear();
        createPersistent = false;
        localError = null;
        unfocusAll();
        dirty = true;
    }

    private void buildCreate() {
        addCategory(KEY + "create.title");
        createName.setMaxLength(view.nameMax);
        createName.active = view.createName.enabled;
        createPassword.active = view.createPassword.enabled;
        createPermissions.active = view.createPermissions.enabled;
        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        addFieldRow("create.name", "create.name.tooltip", view.createName, createName, tips);
        addFieldRow("create.password", "create.password.tooltip", view.createPassword, createPassword, tips);
        ToggleWidget persistent = new ToggleWidget(ELEMENT_WIDTH, () -> createPersistent, value -> createPersistent = value);
        persistent.active = view.createPersistent.enabled;
        addFieldRow("create.persistent", "create.persistent.tooltip", view.createPersistent, persistent, tips);
        addFieldRow("create.permissions", "create.permissions.tooltip", view.createPermissions, createPermissions, tips);

        Map<Widget, Supplier<String>> buttonTips = new IdentityHashMap<>();
        List<Widget> buttons = new ArrayList<>();
        buttons.add(button(0, text("create.submit"), view.create, this::doCreate, buttonTips));
        buttons.add(new ButtonWidget(0, () -> text("cancel"), this::cancelMode));
        addRow(new GroupsRows.ButtonsRow(buttons, buttonTips));
    }

    private void doCreate() {
        if (!view.create.enabled) return;
        if (view.createName.enabled && !GroupsForms.nameLengthOk(createName.getText(), view.nameMin, view.nameMax)) {
            localError = text("name_length", view.nameMin, view.nameMax);
            return;
        }
        GroupsAction action = GroupsForms.create(groups.nextRequestId(), createName.getText(), createPassword.getText(),
                createPersistent, createPermissions.getText(), view.createName.enabled, view.createPassword.enabled,
                view.createPersistent.enabled, view.createPermissions.enabled);
        createPassword.clear();
        submit(action);
    }

    private void buildJoin() {
        addCategory(KEY + "join.title");
        addRow(new GroupsRows.TextRow(font, text("join.prompt", joinGroupName), 0xFFFFFF, true));
        joinPassword.active = true;
        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        addFieldRow("join.password", null, Control.ON, joinPassword, tips);

        GroupRow row = view.groupRow(joinGroupId);
        Map<Widget, Supplier<String>> buttonTips = new IdentityHashMap<>();
        List<Widget> buttons = new ArrayList<>();
        buttons.add(button(0, text("join.submit"), row == null ? Control.off(Reason.NOT_JOINABLE) : row.join,
                this::doJoin, buttonTips));
        buttons.add(new ButtonWidget(0, () -> text("cancel"), this::cancelMode));
        addRow(new GroupsRows.ButtonsRow(buttons, buttonTips));
    }

    /** The password goes into the action once and is cleared from the field right away. */
    private void doJoin() {
        GroupRow row = view.groupRow(joinGroupId);
        if (mode != Mode.JOIN || row == null || !row.join.enabled) return;
        String password = joinPassword.getText();
        joinPassword.clear();
        submit(GroupsForms.join(groups.nextRequestId(), row.id, password));
    }

    private void cancelMode() {
        closeMode();
        dirty = true;
    }

    private void closeMode() {
        mode = Mode.MAIN;
        joinGroupId = null;
        createPassword.clear();
        joinPassword.clear();
        localError = null;
        unfocusAll();
    }

    private void unfocusAll() {
        for (TextFieldWidget field : fields) field.setFocused(false);
    }

    // ---- in a group ------------------------------------------------------------------------------------------

    private void buildGroup() {
        GroupView group = view.group;
        syncSettings(group);

        addCategory(KEY + "current");
        List<String> info = new ArrayList<>();
        info.add(EnumChatFormatting.WHITE + group.name);
        info.add(group.ownerName == null ? text("tip.no_owner") : text("tip.owner", group.ownerName));
        List<String> flags = new ArrayList<>();
        if (group.hasPassword) flags.add(text("tip.password"));
        if (group.persistent) flags.add(text("tip.persistent"));
        if (!flags.isEmpty()) info.add(String.join(", ", flags));
        if (!group.permissions.isEmpty()) info.add(text("current.permissions", GroupsForms.formatPermissions(group.permissions)));
        addRow(new GroupsRows.TextRow(font, String.join("\n", info), MUTED, true));

        Map<Widget, Supplier<String>> leaveTips = new IdentityHashMap<>();
        Widget leave = button(CONTAINER_WIDTH, text("leave"), group.leave,
                () -> submit(GroupsAction.simple(groups.nextRequestId(), GroupsAction.Type.LEAVE)), leaveTips);
        addRow(new GroupsRows.ButtonsRow(Collections.singletonList(leave), leaveTips));

        int online = 0;
        for (MemberRow member : group.members) if (member.online) online++;
        addCategory(KEY + "members", online, group.members.size());
        for (MemberRow member : group.members) addRow(memberEntry(member));

        buildInvite(group);
        if (group.showSettings()) buildSettings(group);
        buildBanned(group);
        buildManage(group);
    }

    private SettingsTab.Row memberEntry(MemberRow member) {
        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        List<Widget> widgets = new ArrayList<>();
        if (showAction(member.kick)) {
            widgets.add(button(46, text("kick"), member.kick, () -> submit(
                    GroupsAction.player(groups.nextRequestId(), GroupsAction.Type.KICK, member.id)), tips));
        }
        if (showAction(member.ban)) {
            widgets.add(button(46, text("ban"), member.ban, () -> submit(
                    GroupsAction.player(groups.nextRequestId(), GroupsAction.Type.BAN, member.id)), tips));
        }
        GroupsRows.Marker marker = (mc, x, centerY) -> {
            boolean speaking = GroupsPresenter.isSpeaking(member.online, member.self, member.id, speakingNow,
                    selfSpeakingNow);
            PlayerHeads.draw(mc, member.id, x + PlayerHeads.OUTLINE, centerY - HEAD_SIZE / 2, HEAD_SIZE,
                    PlayerHeads.Style.of(member.online, speaking));
            return HEAD_SIZE + 2 * PlayerHeads.OUTLINE + 4;
        };
        Supplier<String> label = () -> (member.online ? "" : EnumChatFormatting.GRAY.toString())
                + (member.self ? text("member.you", member.name) : member.name);
        Supplier<String> detail = () -> {
            List<String> parts = new ArrayList<>();
            if (member.owner) parts.add(EnumChatFormatting.GOLD + text("member.owner"));
            if (!member.online) parts.add(EnumChatFormatting.GRAY + text("member.offline"));
            return String.join(EnumChatFormatting.GRAY + ", ", parts);
        };
        return new GroupsRows.EntryRow(marker, label, detail, MUTED, widgets, tips, () -> memberTooltip(member));
    }

    /** Kick and ban are shown unless the player lacks the permission or the row is the player's own. */
    private static boolean showAction(Control control) {
        return control.enabled || (control.reason != Reason.NO_PERMISSION && control.reason != Reason.SELF);
    }

    private String memberTooltip(MemberRow member) {
        StringBuilder tip = new StringBuilder(member.name);
        tip.append('\n').append(EnumChatFormatting.GRAY).append(text("tip.id", member.id));
        tip.append('\n').append(member.online ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                .append(text(member.online ? "member.online" : "member.offline"));
        if (GroupsPresenter.isSpeaking(member.online, member.self, member.id, speakingNow, selfSpeakingNow)) {
            tip.append('\n').append(EnumChatFormatting.GREEN).append(text("member.speaking"));
        }
        return tip.toString();
    }

    private void buildInvite(GroupView group) {
        if (!group.invite.enabled && group.invite.reason == Reason.NO_PERMISSION) return;
        addCategory(KEY + "invite");
        if (!containsCandidate(group.inviteCandidates, invitePick)) invitePick = null;
        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        DropDownWidget picker = picker(110, group.inviteCandidates, () -> invitePick, id -> invitePick = id, "invite.none");
        picker.active = group.invite.enabled;
        if (!group.invite.enabled) tips.put(picker, reasonTip(group.invite.reason));
        Widget invite = button(60, text("invite.submit"), group.invite, () -> {
            if (invitePick == null) {
                localError = text("pick_first");
                return;
            }
            UUID target = invitePick;
            invitePick = null;
            submit(GroupsAction.player(groups.nextRequestId(), GroupsAction.Type.INVITE, target));
        }, tips);
        addRow(new GroupsRows.EntryRow(null, () -> text("invite.player"), null, 0, widgets(picker, invite), tips,
                () -> text("invite.tooltip")));
    }

    private DropDownWidget picker(int width, List<GroupsPresenter.Candidate> candidates, Supplier<UUID> picked,
                                  java.util.function.Consumer<UUID> pick, String emptyKey) {
        List<String> names = new ArrayList<>();
        for (GroupsPresenter.Candidate candidate : candidates) names.add(candidate.name);
        return new DropDownWidget(width, () -> {
            UUID id = picked.get();
            for (GroupsPresenter.Candidate candidate : candidates) {
                if (candidate.id.equals(id)) return candidate.name;
            }
            return candidates.isEmpty() ? text(emptyKey) : text("pick");
        }, names, index -> {
            if (index >= 0 && index < candidates.size()) pick.accept(candidates.get(index).id);
        });
    }

    private static boolean containsCandidate(List<GroupsPresenter.Candidate> candidates, UUID id) {
        if (id == null) return false;
        for (GroupsPresenter.Candidate candidate : candidates) {
            if (candidate.id.equals(id)) return true;
        }
        return false;
    }

    private void buildSettings(GroupView group) {
        addCategory(KEY + "settings");
        settingsName.setMaxLength(view.nameMax);
        settingsName.active = group.rename.enabled;
        settingsPassword.active = group.setPassword.enabled;
        settingsPermissions.active = group.setPermissions.enabled;

        Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
        Widget saveName = button(50, text("save"), group.rename, this::doRename, tips);
        addSettingsRow("settings.name", null, group.rename, tips, settingsName, saveName);
        if (!group.rename.enabled) tips.put(settingsName, reasonTip(group.rename.reason));

        Widget set = button(44, text("set"), group.setPassword, this::doSetPassword, tips);
        Widget clear = button(44, text("clear"), group.clearPassword, () -> submit(
                GroupsForms.clearPassword(groups.nextRequestId())), tips);
        if (!group.setPassword.enabled) tips.put(settingsPassword, reasonTip(group.setPassword.reason));
        addSettingsRow("settings.password", "settings.password.tooltip", group.setPassword, tips, settingsPassword, set, clear);

        ToggleWidget persistent = new ToggleWidget(ELEMENT_WIDTH, () -> group.persistent,
                value -> submit(GroupsForms.setPersistent(groups.nextRequestId(), value)));
        persistent.active = group.setPersistent.enabled;
        if (!persistent.active) tips.put(persistent, reasonTip(group.setPersistent.reason));
        addSettingsRow("settings.persistent", "create.persistent.tooltip", group.setPersistent, tips, persistent);

        Widget savePermissions = button(50, text("save"), group.setPermissions, this::doSetPermissions, tips);
        if (!group.setPermissions.enabled) tips.put(settingsPermissions, reasonTip(group.setPermissions.reason));
        addSettingsRow("settings.permissions", "create.permissions.tooltip", group.setPermissions, tips,
                settingsPermissions, savePermissions);
    }

    private void addSettingsRow(String labelKey, String tooltipKey, Control control, Map<Widget, Supplier<String>> tips,
                                Widget... widgets) {
        addRow(new GroupsRows.EntryRow(null, () -> text(labelKey), null, 0, widgets(widgets), tips,
                tooltipKey == null ? null : () -> text(tooltipKey)));
    }

    private void doRename() {
        GroupView group = view.group;
        if (group == null || !group.rename.enabled) return;
        if (GroupsForms.blankToNull(settingsName.getText()) == null) return;
        if (!GroupsForms.nameLengthOk(settingsName.getText(), view.nameMin, view.nameMax)) {
            localError = text("name_length", view.nameMin, view.nameMax);
            return;
        }
        submit(GroupsForms.rename(groups.nextRequestId(), settingsName.getText()));
    }

    private void doSetPassword() {
        GroupView group = view.group;
        if (group == null || !group.setPassword.enabled) return;
        String password = settingsPassword.getText();
        if (password.isEmpty()) return;
        settingsPassword.clear();
        submit(GroupsForms.setPassword(groups.nextRequestId(), password));
    }

    private void doSetPermissions() {
        GroupView group = view.group;
        if (group == null || !group.setPermissions.enabled) return;
        submit(GroupsForms.setPermissions(groups.nextRequestId(), settingsPermissions.getText()));
    }

    /** Keeps the settings boxes in step with the server: new group resets them, an untouched box follows changes. */
    private void syncSettings(GroupView group) {
        String permissions = GroupsForms.formatPermissions(group.permissions);
        if (!group.id.equals(syncedGroup)) {
            settingsName.setText(group.name);
            settingsPermissions.setText(permissions);
            settingsPassword.clear();
            invitePick = null;
            transferPick = null;
            confirmDelete = false;
            syncedGroup = group.id;
        } else {
            if (settingsName.getText().equals(syncedName)) settingsName.setText(group.name);
            if (settingsPermissions.getText().equals(syncedPermissions)) settingsPermissions.setText(permissions);
        }
        syncedName = group.name;
        syncedPermissions = permissions;
    }

    private void buildBanned(GroupView group) {
        if (!group.showUnban()) return;
        addCategory(KEY + "banned");
        for (GroupsPresenter.BannedRow banned : group.banned) {
            Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
            Widget unban = button(56, text("unban"), banned.unban, () -> submit(
                    GroupsAction.player(groups.nextRequestId(), GroupsAction.Type.UNBAN, banned.id)), tips);
            addRow(new GroupsRows.EntryRow(null, () -> banned.name, null, 0, Collections.singletonList(unban), tips,
                    () -> banned.name + "\n" + EnumChatFormatting.GRAY + text("tip.id", banned.id)));
        }
    }

    private void buildManage(GroupView group) {
        if (!group.showTransfer() && !group.showDelete()) return;
        addCategory(KEY + "manage");
        if (group.showTransfer()) {
            if (!containsCandidate(group.transferCandidates, transferPick)) transferPick = null;
            Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
            DropDownWidget picker = picker(110, group.transferCandidates, () -> transferPick,
                    id -> transferPick = id, "invite.none");
            picker.active = group.transfer.enabled;
            if (!group.transfer.enabled) tips.put(picker, reasonTip(group.transfer.reason));
            Widget transfer = button(60, text("transfer.submit"), group.transfer, () -> {
                if (transferPick == null) {
                    localError = text("pick_first");
                    return;
                }
                UUID target = transferPick;
                transferPick = null;
                submit(GroupsAction.player(groups.nextRequestId(), GroupsAction.Type.TRANSFER, target));
            }, tips);
            addRow(new GroupsRows.EntryRow(null, () -> text("transfer"), null, 0, widgets(picker, transfer), tips,
                    () -> text("transfer.tooltip")));
        }
        if (group.showDelete()) {
            Map<Widget, Supplier<String>> tips = new IdentityHashMap<>();
            ButtonWidget delete = new ButtonWidget(CONTAINER_WIDTH,
                    () -> confirmDelete ? EnumChatFormatting.RED + text("delete.confirm") : text("delete"), () -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    confirmUntil = now() + CONFIRM_MS;
                } else {
                    confirmDelete = false;
                    submit(GroupsAction.simple(groups.nextRequestId(), GroupsAction.Type.DELETE));
                }
            });
            delete.active = group.delete.enabled;
            if (!group.delete.enabled) tips.put(delete, reasonTip(group.delete.reason));
            addRow(new GroupsRows.ButtonsRow(Collections.<Widget>singletonList(delete), tips));
        }
    }

    // ---- shared pieces ---------------------------------------------------------------------------------------

    private void addFieldRow(String labelKey, String tooltipKey, Control control, Widget widget,
                             Map<Widget, Supplier<String>> tips) {
        if (!control.enabled) tips.put(widget, reasonTip(control.reason));
        addRow(new GroupsRows.EntryRow(null, () -> text(labelKey), null, 0, Collections.singletonList(widget), tips,
                tooltipKey == null ? null : () -> text(tooltipKey)));
    }

    private static List<Widget> widgets(Widget... widgets) {
        List<Widget> list = new ArrayList<>();
        Collections.addAll(list, widgets);
        return list;
    }

    /** A button for a presenter {@link Control}: disabled with the reason as its tooltip. Width 0 = set by the row. */
    private static Widget button(int width, String label, Control control, Runnable action,
                                 Map<Widget, Supplier<String>> tips) {
        ButtonWidget button = new ButtonWidget(width, () -> label, action);
        button.active = control.enabled;
        if (!control.enabled) tips.put(button, reasonTip(control.reason));
        return button;
    }

    private static Supplier<String> reasonTip(Reason reason) {
        return () -> VoiceLang.format(KEY + "reason." + reason.name().toLowerCase(Locale.ROOT));
    }

    private void submit(GroupsAction action) {
        localError = null;
        groups.submit(action, now());
        dirty = true;
    }

    // ---- status line -----------------------------------------------------------------------------------------

    private String statusText() {
        long now = now();
        Status status = GroupsPresenter.status(groups.lastResult(), groups.busy(now), groups.timedOut(now));
        if (status == Status.WAITING) return text("status.waiting");
        if (status == Status.TIMED_OUT) return text("status.timeout");
        if (localError != null) return localError;
        if (status == Status.NONE) return null;
        String message = GroupsPresenter.stripFormatting(groups.lastResult().message);
        return message.isEmpty() ? null : message;
    }

    private int statusColor() {
        long now = now();
        Status status = GroupsPresenter.status(groups.lastResult(), groups.busy(now), groups.timedOut(now));
        if (status == Status.WAITING) return WAIT_COLOR;
        if (status == Status.TIMED_OUT || localError != null || status == Status.ERROR) return ERROR_COLOR;
        return OK_COLOR;
    }

    @Override
    void render(Minecraft mc, int mouseX, int mouseY) {
        refreshIfNeeded();
        super.render(mc, mouseX, mouseY);
        String status = statusText();
        if (status == null) return;
        int width = screen.width;
        int top = bottomEdge - STATUS_HEIGHT;
        Gui.drawRect(0, top, width, bottomEdge, 0xC0000000);
        Gui.drawRect(0, top, width, top + 1, 0xFF404040);
        String shown = Widget.fit(font, status, width - 28);
        font.drawStringWithShadow(shown, width / 2 - font.getStringWidth(shown) / 2, top + (STATUS_HEIGHT - 8) / 2 + 1,
                statusColor());
        if (!shown.equals(status) && mouseY >= top && mouseY < bottomEdge) setTooltip(wrap(font, status));
    }
}
