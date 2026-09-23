package su.plo.voice.platform.forge.client;

import java.util.UUID;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;

/**
 * Upstream PlayerVolumeAction: while the action key is held on a player, the mouse wheel changes that
 * player's volume and the player icon shows the percentage. Client thread only.
 */
@SideOnly(Side.CLIENT)
public final class PlayerVolumeAction {
    private static final long SHOW_MS = 1_000L;

    private final ClientState state;
    private UUID focusedPlayer;
    private long lastScroll;

    PlayerVolumeAction(ClientState state) {
        this.state = state;
    }

    public boolean isShown(UUID playerId) {
        return playerId.equals(focusedPlayer) && lastScroll != 0L && System.currentTimeMillis() - lastScroll < SHOW_MS;
    }

    /** Action key down: focus the voice player in sight. */
    void onPress() {
        ClientConnectionState connection = state.getConnection();
        if (connection == null || !connection.isConnected()) return;
        EntityPlayer player = playerBySight(Minecraft.getMinecraft());
        if (player != null && connection.getPlayer(player.getUniqueID()) != null) focusedPlayer = player.getUniqueID();
    }

    /** Every frame; releasing the action key drops the focus and saves a changed volume. */
    void update(boolean pressed) {
        if (pressed) return;
        if (lastScroll != 0L) state.save();
        focusedPlayer = null;
        lastScroll = 0L;
    }

    /** Cancelling the wheel event also keeps the hotbar slot, like upstream. */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.dwheel == 0 || focusedPlayer == null || Minecraft.getMinecraft().currentScreen != null) return;
        lastScroll = System.currentTimeMillis();
        String key = ClientState.playerVolumeKey(focusedPlayer);
        double value = state.getSourceVolume(key) + (event.dwheel > 0 ? 0.05D : -0.05D);
        state.setSourceVolume(key, Math.round(value * 200D / 5D) * 5D / 200D);
        event.setCanceled(true);
    }

    /** Upstream getPlayerBySight: one-block steps along the look vector until a solid block. */
    private static EntityPlayer playerBySight(Minecraft mc) {
        EntityPlayer self = mc.thePlayer;
        if (self == null || mc.theWorld == null) return null;
        // The local player's posY is at eye level.
        double x = self.posX;
        double y = self.posY;
        double z = self.posZ;
        Vec3 look = self.getLook(1F);
        for (int i = 0; i < mc.gameSettings.renderDistanceChunks * 16; i++) {
            x += look.xCoord;
            y += look.yCoord;
            z += look.zCoord;
            Block block = mc.theWorld.getBlock(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z));
            if (block.getMaterial() != Material.air && block.isOpaqueCube()) break;

            // A 1x2x1 box whose top is at the ray point.
            for (Object entity : mc.theWorld.playerEntities) {
                EntityPlayer player = (EntityPlayer) entity;
                double feet = player.boundingBox.minY;
                if (Math.abs(player.posX - x) <= 0.5D && Math.abs(player.posZ - z) <= 0.5D && feet >= y - 2D && feet <= y
                        && !player.isInvisibleToPlayer(self) && player != self) {
                    return player;
                }
            }
        }
        return null;
    }
}
