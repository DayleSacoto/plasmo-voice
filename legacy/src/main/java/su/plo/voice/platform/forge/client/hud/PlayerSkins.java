package su.plo.voice.platform.forge.client.hud;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.UUID;

import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * Skins for Groups heads, like the ServerUtilities TAB heads but kept for the whole session: the live skin of a
 * loaded player first (remembered by nickname), else the remembered one, else the vanilla nickname skin download
 * ({@code skins/<name>} via {@link AbstractClientPlayer#getDownloadImageSkin}, so skin mods that patch it apply),
 * Steve meanwhile. Only texture locations and lookup state are kept, the images stay in the TextureManager.
 * Client thread only.
 */
@SideOnly(Side.CLIENT)
public final class PlayerSkins {
    private static final SkinCache<ResourceLocation> CACHE =
            new SkinCache<>(new VanillaLookup(), System::currentTimeMillis);

    private PlayerSkins() {
    }

    /**
     * @param nickname the member's real player name; null keeps the old behaviour (live skin or Steve, no cache)
     */
    public static ResourceLocation skin(Minecraft mc, UUID playerId, String nickname) {
        if (SkinCache.isPlaceholder(playerId, nickname)) nickname = null;
        AbstractClientPlayer player = loaded(mc, playerId, nickname);
        if (nickname == null) return player != null ? player.getLocationSkin() : AbstractClientPlayer.locationStevePng;
        // func_152123_o: the skin manager delivered a real skin; until then the entity only reports Steve.
        ResourceLocation live = player != null && player.func_152123_o() ? player.getLocationSkin() : null;
        return CACHE.resolve(nickname, live, AbstractClientPlayer.locationStevePng);
    }

    /** Disconnect or a new server connection. */
    public static void clear() {
        CACHE.clear();
    }

    private static AbstractClientPlayer loaded(Minecraft mc, UUID playerId, String nickname) {
        if (mc.theWorld == null) return null;
        EntityPlayer player = playerId == null ? null : mc.theWorld.func_152378_a(playerId);
        if (player == null && nickname != null) player = mc.theWorld.getPlayerEntityByName(nickname);
        return player instanceof AbstractClientPlayer ? (AbstractClientPlayer) player : null;
    }

    /** The vanilla legacy skin download; its progress is read from the downloader's own fields. */
    private static final class VanillaLookup implements SkinCache.Lookup<ResourceLocation> {
        private static final Field UPLOADED = field("textureUploaded", "field_110559_g");
        private static final Field IMAGE = field("bufferedImage", "field_110560_d");
        private static final Field THREAD = field("imageThread", "field_110561_e");

        @Override
        public ResourceLocation start(String nickname) {
            ResourceLocation location = AbstractClientPlayer.getLocationSkin(nickname);
            // A failed download from an earlier session is still registered: forget it so this one really loads.
            if (Minecraft.getMinecraft().getTextureManager().getTexture(location) != null
                    && status(location) == SkinCache.Status.FAILED) {
                discard(location);
            }
            AbstractClientPlayer.getDownloadImageSkin(location, nickname);
            return location;
        }

        @Override
        public SkinCache.Status status(ResourceLocation skin) {
            ITextureObject texture = Minecraft.getMinecraft().getTextureManager().getTexture(skin);
            if (texture == null) return SkinCache.Status.FAILED;
            if (!(texture instanceof ThreadDownloadImageData) || UPLOADED == null || IMAGE == null || THREAD == null) {
                return SkinCache.Status.UNKNOWN;
            }
            try {
                if (UPLOADED.getBoolean(texture) || IMAGE.get(texture) instanceof BufferedImage) {
                    return SkinCache.Status.LOADED;
                }
                Thread thread = (Thread) THREAD.get(texture);
                return thread == null || thread.isAlive() ? SkinCache.Status.LOADING : SkinCache.Status.FAILED;
            } catch (IllegalAccessException | RuntimeException e) {
                // Reflection refused at runtime: draw what is registered, never retry, never fail the frame.
                return SkinCache.Status.UNKNOWN;
            }
        }

        @Override
        public void discard(ResourceLocation skin) {
            Minecraft.getMinecraft().getTextureManager().deleteTexture(skin);
        }

        private static Field field(String... names) {
            try {
                return ReflectionHelper.findField(ThreadDownloadImageData.class, names);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
