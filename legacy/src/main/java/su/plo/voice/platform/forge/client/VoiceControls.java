package su.plo.voice.platform.forge.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import su.plo.voice.platform.forge.client.gui.VoiceNotAvailableScreen;
import su.plo.voice.platform.forge.client.gui.VoiceSettingsScreen;

@SideOnly(Side.CLIENT)
public final class VoiceControls {
    private static final KeyBinding SETTINGS_KEY =
            new KeyBinding("key.plasmovoice.settings", Keyboard.KEY_V, "Plasmo Voice");

    private VoiceControls() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(SETTINGS_KEY);
        FMLCommonHandler.instance().bus().register(new VoiceControls());
    }

    /** Screens receive keys directly, so they close themselves on the settings key like upstream. */
    public static boolean isSettingsKey(int keyCode) {
        return keyCode != Keyboard.KEY_NONE && keyCode == SETTINGS_KEY.getKeyCode();
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!SETTINGS_KEY.isPressed()) return;
        ClientState state = ClientState.getInstance();
        Minecraft.getMinecraft().displayGuiScreen(state.isVoiceAvailable()
                ? new VoiceSettingsScreen(state)
                : new VoiceNotAvailableScreen(state));
    }
}
