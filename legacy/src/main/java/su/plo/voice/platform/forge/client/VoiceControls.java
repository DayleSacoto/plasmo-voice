package su.plo.voice.platform.forge.client;

import java.io.File;
import java.util.List;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import su.plo.voice.platform.forge.client.audio.CaptureActivation;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.gui.VoiceNotAvailableScreen;
import su.plo.voice.platform.forge.client.gui.VoiceSettingsScreen;
import su.plo.voice.platform.forge.client.hud.PlayerIcons;
import su.plo.voice.platform.forge.client.hud.VoiceHud;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;

/** The vanilla settings key plus the upstream voice hotkeys (HotkeyActions and VoiceClientActivation). */
@SideOnly(Side.CLIENT)
public final class VoiceControls {
    private static final KeyBinding SETTINGS_KEY =
            new KeyBinding("key.plasmovoice.settings", Keyboard.KEY_V, "Plasmo Voice");

    private final ClientState state;
    private final PlayerVolumeAction volumeAction;

    private VoiceControls(ClientState state, PlayerVolumeAction volumeAction) {
        this.state = state;
        this.volumeAction = volumeAction;
    }

    public static void register() {
        ClientState state = ClientState.getInstance();
        File settings = new File(Loader.instance().getConfigDir(), "plasmovoice/client.cfg");
        state.setSettingsFile(settings);
        ClientSettingsFile.load(settings, state);

        ClientRegistry.registerKeyBinding(SETTINGS_KEY);
        PlayerVolumeAction volumeAction = new PlayerVolumeAction(state);
        FMLCommonHandler.instance().bus().register(new VoiceControls(state, volumeAction));
        MinecraftForge.EVENT_BUS.register(volumeAction);
        MinecraftForge.EVENT_BUS.register(new VoiceHud(state));
        MinecraftForge.EVENT_BUS.register(new PlayerIcons(state, volumeAction));
    }

    /** Screens receive keys directly, so they close themselves on the settings key like upstream. */
    public static boolean isSettingsKey(int keyCode) {
        return keyCode != Keyboard.KEY_NONE && keyCode == SETTINGS_KEY.getKeyCode();
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!SETTINGS_KEY.isPressed()) return;
        Minecraft.getMinecraft().displayGuiScreen(state.isVoiceAvailable()
                ? new VoiceSettingsScreen(state)
                : new VoiceNotAvailableScreen(state));
    }

    /** Every frame, so push-to-talk follows the key without waiting for a game tick. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        boolean capturing = screen instanceof VoiceSettingsScreen && ((VoiceSettingsScreen) screen).isCapturingHotkey();
        VoiceHotkeys hotkeys = state.getHotkeys();
        hotkeys.update(VoiceControls::isDown, Display.isActive(), screen != null, capturing, this::onPress);
        // Upstream handlePTT ignores the key while typing in chat or on a sign.
        boolean typing = screen instanceof GuiChat || screen instanceof GuiEditSign;
        state.setPushToTalkPressed(hotkeys.isPressed(VoiceHotkeys.PROXIMITY_PTT) && !typing && !capturing);
        volumeAction.update(hotkeys.isPressed(VoiceHotkeys.ACTION));
    }

    private void onPress(String name) {
        switch (name) {
            case VoiceHotkeys.MUTE_MICROPHONE:
                state.setMicrophoneMuted(!state.isMicrophoneMuted());
                state.save();
                break;
            case VoiceHotkeys.DISABLE_VOICE:
                state.setVoiceDisabled(!state.isVoiceDisabled());
                state.save();
                break;
            case VoiceHotkeys.ACTION:
                volumeAction.onPress();
                break;
            case VoiceHotkeys.PROXIMITY_TOGGLE:
                toggleActivation();
                break;
            case VoiceHotkeys.PROXIMITY_DISTANCE_INCREASE:
                stepDistance(1);
                break;
            case VoiceHotkeys.PROXIMITY_DISTANCE_DECREASE:
                stepDistance(-1);
                break;
            default:
                break;
        }
    }

    /** Upstream VoiceClientActivation.onToggle: only voice activation can be toggled. */
    private void toggleActivation() {
        if (state.getActivationType() == CaptureActivation.Type.PUSH_TO_TALK) return;
        state.setActivationToggled(!state.isActivationToggled());
        state.save();
        actionBar(I18n.format("message.plasmovoice.activation.toggle", state.translate(proximityTranslation()),
                I18n.format(state.isActivationToggled() ? "message.plasmovoice.off" : "message.plasmovoice.on")));
    }

    /** Upstream onDistanceIncrease / onDistanceDecrease: cycles through the server distances. */
    private void stepDistance(int step) {
        ClientConfig config = state.getConnection() == null ? null : state.getConnection().getConfig();
        VoiceActivation proximity = config == null ? null : config.activation(VoiceActivation.PROXIMITY_ID);
        if (proximity == null) return;
        List<Integer> distances = proximity.getDistances();
        if (distances.isEmpty() || distances.get(0) == -1) return;

        int current = ClientConfig.allowedDistance(proximity,
                state.getActivationDistance(config.getPacket().getServerId(), proximity.getId()));
        int index = Math.floorMod(distances.indexOf(current) + step, distances.size());
        state.changeActivationDistance(proximity.getId(), distances.get(index));
        state.save();
        actionBar(I18n.format("message.plasmovoice.distance_changed", state.translate(proximity.getTranslation()), distances.get(index)));
    }

    private String proximityTranslation() {
        ClientConfig config = state.getConnection() == null ? null : state.getConnection().getConfig();
        VoiceActivation proximity = config == null ? null : config.activation(VoiceActivation.PROXIMITY_ID);
        return proximity == null ? "pv.activation.proximity" : proximity.getTranslation();
    }

    /** 1.7.10 has no action bar; the record-playing line above the hotbar is its equivalent. */
    private static void actionBar(String text) {
        Minecraft.getMinecraft().ingameGUI.func_110326_a(text, false);
    }

    private static boolean isDown(int code) {
        return code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code);
    }
}
