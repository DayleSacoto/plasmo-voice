package su.plo.voice.platform.forge.client.hud;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;
import su.plo.slib.api.position.Pos3d;
import su.plo.voice.platform.forge.client.ClientState;

/**
 * Upstream DistanceVisualizeStateExtractor + DistanceVisualizeRenderer: a translucent sphere of the voice distance
 * that fades out two seconds after it was requested. Requests and rendering both run on the client thread.
 */
@SideOnly(Side.CLIENT)
public final class DistanceVisualizer {
    /** Upstream DistanceVisualizer color for the player's own distance. */
    public static final int PROXIMITY_COLOR = 0x00a000;
    private static final UUID SELF = new UUID(0L, 0L);
    private static final int SPHERE_STACK = 18;
    private static final int SPHERE_SLICE = 36;
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private final ClientState state;

    public DistanceVisualizer(ClientState state) {
        this.state = state;
    }

    /** Upstream render(radius, color, position): null is around the player and replaces the previous one. */
    public static void show(int radius, int color, Pos3d position) {
        if (!ClientState.getInstance().isVisualizeVoiceDistance()) return;
        if (radius < 2 || radius > Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16) return;
        ENTRIES.put(position == null ? SELF : UUID.randomUUID(), new Entry(radius, color, position, System.currentTimeMillis()));
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (ENTRIES.isEmpty()) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityLivingBase player = minecraft.thePlayer;
        if (player == null || !state.isVisualizeVoiceDistance()) {
            ENTRIES.clear();
            return;
        }
        double renderDistance = minecraft.gameSettings.renderDistanceChunks * 16;
        long now = System.currentTimeMillis();

        GL11.glPushMatrix();
        // GL_CURRENT_BIT: the vertex colors change the current color, and the hand is rendered after this event.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        for (Iterator<Entry> iterator = ENTRIES.values().iterator(); iterator.hasNext(); ) {
            Entry entry = iterator.next();
            int alpha = entry.alpha(now);
            double x;
            double y;
            double z;
            if (entry.position == null) {
                // Upstream uses the player's feet position.
                float partialTicks = event.partialTicks;
                x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
                y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - player.yOffset;
                z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
            } else {
                x = entry.position.getX();
                y = entry.position.getY();
                z = entry.position.getZ();
            }
            if (alpha <= 0 || (entry.position != null && player.getDistance(x, y, z) > renderDistance)) {
                iterator.remove();
                continue;
            }
            drawSphere(x - RenderManager.renderPosX, y - RenderManager.renderPosY, z - RenderManager.renderPosZ,
                    entry.radius, entry.color, alpha);
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** Upstream buildVertices: one triangle strip over the sphere's stacks and slices. */
    private static void drawSphere(double x, double y, double z, float radius, int color, int alpha) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        tessellator.setColorRGBA((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, alpha);
        double stackStep = Math.PI / SPHERE_STACK;
        double sliceStep = Math.PI / SPHERE_SLICE;
        for (int i = 0; i < SPHERE_STACK; i++) {
            double alpha0 = -Math.PI / 2 + i * stackStep;
            double alpha1 = alpha0 + stackStep;
            double r0 = radius * Math.cos(alpha0);
            double r1 = radius * Math.cos(alpha1);
            double y0 = radius * Math.sin(alpha0);
            double y1 = radius * Math.sin(alpha1);
            for (int j = 0; j < SPHERE_SLICE << 1; j++) {
                double beta = j * sliceStep;
                tessellator.addVertex(x + r0 * Math.cos(beta), y + y0, z - r0 * Math.sin(beta));
                tessellator.addVertex(x + r1 * Math.cos(beta), y + y1, z - r1 * Math.sin(beta));
            }
        }
        tessellator.draw();
    }

    private static final class Entry {
        final float radius;
        final int color;
        final Pos3d position;
        final long createdAt;

        Entry(float radius, int color, Pos3d position, long createdAt) {
            this.radius = radius;
            this.color = color;
            this.position = position;
            this.createdAt = createdAt;
        }

        /** Upstream starts at 150 and, after two seconds, loses 10 per tick (50 ms). */
        int alpha(long now) {
            long fading = now - createdAt - 2_000L;
            return fading <= 0 ? 150 : (int) (150 - fading / 5);
        }
    }
}
