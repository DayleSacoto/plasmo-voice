package su.plo.voice.platform.forge.client.audio;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * Upstream SoundOcclusion: walks the blocks between the source and the listener; each block whose collision box
 * the ray crosses adds occlusion, a full opaque block twice as much. Client thread only (world access).
 */
@SideOnly(Side.CLIENT)
public final class SoundOcclusion {
    private static final double OCCLUSION_MULTIPLIER = 0.5D;
    private static final double OCCLUSION_MAX = 0.98D;
    private static final int MAX_STEPS = 200;

    /** Whether the segment crosses the block at x, y, z: null when it does not, else whether the block is solid. */
    interface BlockProbe {
        Boolean hit(int x, int y, int z, double[] from, double[] to);
    }

    private SoundOcclusion() {
    }

    public static double occludedPercent(World world, double[] sound, double[] listener) {
        return occludedPercent((x, y, z, from, to) -> {
            Block block = world.getBlock(x, y, z);
            if (block.getMaterial() == Material.air) return null;
            AxisAlignedBB box = block.getCollisionBoundingBoxFromPool(world, x, y, z);
            if (box == null) return null;
            Vec3 start = Vec3.createVectorHelper(from[0], from[1], from[2]);
            Vec3 end = Vec3.createVectorHelper(to[0], to[1], to[2]);
            return box.calculateIntercept(start, end) == null ? null : block.isOpaqueCube();
        }, sound, listener);
    }

    static double occludedPercent(BlockProbe probe, double[] soundPosition, double[] listener) {
        double occludedPercent = 0D;
        double[] sound = {soundPosition[0] + 0.01D, soundPosition[1] + 0.01D, soundPosition[2] + 0.01D};
        if (isNaN(sound) || isNaN(listener)) return occludedPercent;

        int[] listenerPos = blockPos(listener);
        int[] soundPos = blockPos(sound);
        int i = 0;
        while (i++ < MAX_STEPS) {
            double[] prevSound = sound;
            int[] prevSoundPos = soundPos;
            if (isNaN(sound)) return occludedPercent;
            if (soundPos[0] == listenerPos[0] && soundPos[1] == listenerPos[1] && soundPos[2] == listenerPos[2]) {
                return occludedPercent;
            }

            boolean changeX = listenerPos[0] != soundPos[0];
            boolean changeY = listenerPos[1] != soundPos[1];
            boolean changeZ = listenerPos[2] != soundPos[2];
            int nextX = soundPos[0] + (listenerPos[0] > soundPos[0] ? 1 : 0);
            int nextY = soundPos[1] + (listenerPos[1] > soundPos[1] ? 1 : 0);
            int nextZ = soundPos[2] + (listenerPos[2] > soundPos[2] ? 1 : 0);
            double dx = listener[0] - sound[0];
            double dy = listener[1] - sound[1];
            double dz = listener[2] - sound[2];
            double px = changeX ? (nextX - sound[0]) / dx : Double.POSITIVE_INFINITY;
            double py = changeY ? (nextY - sound[1]) / dy : Double.POSITIVE_INFINITY;
            double pz = changeZ ? (nextZ - sound[2]) / dz : Double.POSITIVE_INFINITY;

            int[] offset = null;
            if (px < py && px < pz) {
                sound = new double[] {nextX, sound[1] + dy * px, sound[2] + dz * px};
                if (listenerPos[0] < soundPos[0]) offset = new int[] {-1, 0, 0};
            } else if (py < pz) {
                sound = new double[] {sound[0] + dx * py, nextY, sound[2] + dz * py};
                if (listenerPos[1] < soundPos[1]) offset = new int[] {0, -1, 0};
            } else {
                sound = new double[] {sound[0] + dx * pz, sound[1] + dy * pz, nextZ};
                if (listenerPos[2] < soundPos[2]) offset = new int[] {0, 0, -1};
            }
            soundPos = blockPos(sound);
            if (offset != null) soundPos = new int[] {soundPos[0] + offset[0], soundPos[1] + offset[1], soundPos[2] + offset[2]};

            if (i <= 1) continue;
            Boolean solid = probe.hit(prevSoundPos[0], prevSoundPos[1], prevSoundPos[2], prevSound, listener);
            if (solid == null) continue;

            double newOcclusion = solid ? OCCLUSION_MULTIPLIER : OCCLUSION_MULTIPLIER / 2D;
            occludedPercent += occludedPercent > 0 ? newOcclusion / 4 : newOcclusion;
            if (occludedPercent > OCCLUSION_MAX) return OCCLUSION_MAX;
        }
        return occludedPercent;
    }

    private static boolean isNaN(double[] position) {
        return Double.isNaN(position[0]) || Double.isNaN(position[1]) || Double.isNaN(position[2]);
    }

    private static int[] blockPos(double[] position) {
        return new int[] {(int) Math.floor(position[0]), (int) Math.floor(position[1]), (int) Math.floor(position[2])};
    }
}
