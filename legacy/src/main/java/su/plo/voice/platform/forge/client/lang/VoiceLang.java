package su.plo.voice.platform.forge.client.lang;

import java.io.File;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Settings screen texts in the language selected in Minecraft, with the same call shape as I18n.format.
 * The snapshot is built on first use and rebuilt when the language changes or resources reload (F3+T),
 * so edits in config/plasmovoice/lang/ apply without restarting the game.
 */
@SideOnly(Side.CLIENT)
public final class VoiceLang {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");

    private static volatile File folder;
    private static volatile VoiceTranslations snapshot;

    private VoiceLang() {
    }

    /** Called once on client init: creates missing default files and listens for resource reloads. */
    public static void register(File langFolder) {
        folder = langFolder;
        VoiceTranslations.provision(langFolder, VoiceTranslations.CLASSPATH, LOGGER);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getResourceManager() instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) mc.getResourceManager())
                    .registerReloadListener((IResourceManager manager) -> snapshot = null);
        }
    }

    public static String format(String key, Object... args) {
        return current().format(key, args);
    }

    private static VoiceTranslations current() {
        String language = VoiceTranslations.normalize(selectedLanguage());
        VoiceTranslations current = snapshot;
        if (current == null || !current.language().equals(language)) {
            current = VoiceTranslations.load(folder, language, VoiceTranslations.CLASSPATH, LOGGER);
            snapshot = current;
        }
        return current;
    }

    private static String selectedLanguage() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.gameSettings == null ? null : mc.gameSettings.language;
    }
}
