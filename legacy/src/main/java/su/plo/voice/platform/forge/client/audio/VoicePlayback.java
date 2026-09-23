package su.plo.voice.platform.forge.client.audio;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.audio.source.SourceInfo;

/**
 * Voice playback for one accepted server config (upstream AlOutputDevice).
 * Like upstream it opens its own OpenAL device and makes the context current only on the playback thread
 * (ALC_EXT_thread_local_context), so Minecraft's sound system context and listener are never touched.
 */
@SideOnly(Side.CLIENT)
public final class VoicePlayback implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    private static final int ALC_DEVICE_SPECIFIER = 0x1005;
    private static final int ALC_CONNECTED = 0x313; // ALC_EXT_disconnect
    private static final long REOPEN_INTERVAL_MS = 5_000L;
    private static final long CONNECTED_CHECK_INTERVAL_MS = 1_000L;
    private static final long LOOP_INTERVAL_MS = 5L;

    private final ClientConfig config;
    private final ClientVoiceSources sources;
    private final Thread thread;
    private volatile boolean closed;
    /** Eye position, look and up vectors of the camera; published by the client thread every frame. */
    private volatile double[] listener;

    // Playback thread only.
    private final FloatBuffer orientation = BufferUtils.createFloatBuffer(6);
    private Alc alc;
    private long device;
    private long context;
    private boolean hasDisconnectExt;
    private long nextOpenAttempt;
    private long nextConnectedCheck;
    private boolean openFailureLogged;

    public VoicePlayback(ClientConfig config, ClientVoiceSources sources) {
        this.config = config;
        this.sources = sources;
        this.thread = new Thread(this::run, "plasmo-voice-playback");
        thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Non-blocking: the playback thread releases sources, decoders and the device on its way out. */
    @Override
    public void close() {
        closed = true;
        sources.close();
        thread.interrupt();
    }

    /** Client thread, every frame: snapshots camera and source player positions for the playback thread. */
    public void updatePositions(float partialTicks) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityLivingBase camera = minecraft.renderViewEntity;
        World world = minecraft.theWorld;
        if (camera == null || world == null) {
            listener = null;
            return;
        }

        double[] eye = eyePosition(camera, partialTicks);
        double yaw = Math.toRadians(camera.prevRotationYaw + (camera.rotationYaw - camera.prevRotationYaw) * partialTicks);
        double pitch = Math.toRadians(camera.prevRotationPitch + (camera.rotationPitch - camera.prevRotationPitch) * partialTicks);
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw), sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        // Upstream AlListener: forward (0,0,1) and up (0,1,0) rotated by -yaw around Y, then pitch around X.
        listener = new double[] {
                eye[0], eye[1], eye[2],
                -sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch,
                -sinYaw * sinPitch, cosPitch, cosYaw * sinPitch
        };

        for (VoiceSource source : sources.all()) {
            SourceInfo info = source.info;
            if (!(info instanceof PlayerSourceInfo)) continue;
            EntityPlayer player = world.func_152378_a(((PlayerSourceInfo) info).getPlayerInfo().getPlayerId());
            source.position = player == null ? null : eyePosition(player, partialTicks);
        }
    }

    private static double[] eyePosition(Entity entity, float partialTicks) {
        // The local player's posY is already at eye level (yOffset 1.62); remote players stand at their feet.
        double eyeHeight = entity instanceof EntityPlayer ? 1.62D : entity.getEyeHeight();
        return new double[] {
                entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks,
                entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks - entity.yOffset + eyeHeight,
                entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks
        };
    }

    private void run() {
        try {
            while (!closed) {
                long now = System.currentTimeMillis();
                if (ensureDevice(now)) {
                    double[] current = listener;
                    updateListener(current);
                    for (VoiceSource source : sources.all()) source.pump(config, current, now);
                } else {
                    // Nothing can play; keep the jitter buffers from holding stale frames.
                    for (VoiceSource source : sources.all()) source.buffer.clear();
                }
                Thread.sleep(LOOP_INTERVAL_MS);
            }
        } catch (InterruptedException ignored) {
            // closed
        } catch (RuntimeException e) {
            LOGGER.error("Voice playback stopped unexpectedly", e);
        } finally {
            closeDevice();
            for (VoiceSource source : sources.all()) source.release();
        }
    }

    private boolean ensureDevice(long now) {
        if (device != 0L) {
            if (hasDisconnectExt && now >= nextConnectedCheck) {
                nextConnectedCheck = now + CONNECTED_CHECK_INTERVAL_MS;
                if (alc.getInteger(device, ALC_CONNECTED) == 0) {
                    LOGGER.warn("Voice output device disconnected; reopening");
                    closeDevice();
                    return false;
                }
            }
            return true;
        }
        // OpenAL natives are loaded by the game's sound system.
        if (now < nextOpenAttempt || !AL.isCreated()) return false;
        nextOpenAttempt = now + REOPEN_INTERVAL_MS;
        try {
            openDevice();
            openFailureLogged = false;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!openFailureLogged) LOGGER.warn("Voice output unavailable; playback is idle: {}", describe(e));
            openFailureLogged = true;
            closeDevice();
            return false;
        }
    }

    private void openDevice() throws ReflectiveOperationException {
        if (alc == null) alc = new Alc();
        device = alc.openDevice();
        if (device == 0L) throw new IllegalStateException("no output device");
        if (!alc.isExtensionPresent(device, "ALC_EXT_thread_local_context")) {
            throw new IllegalStateException("ALC_EXT_thread_local_context is not supported");
        }
        context = alc.createContext(device);
        if (context == 0L) throw new IllegalStateException("failed to create an OpenAL context");
        if (!alc.setThreadContext(context)) throw new IllegalStateException("failed to make the context current");
        alc.createThreadCapabilities(device);

        // Gain is computed per source like upstream; OpenAL only pans.
        AL10.alDistanceModel(AL10.AL_NONE);
        AL10.alListenerf(AL10.AL_GAIN, 1F);
        hasDisconnectExt = alc.isExtensionPresent(device, "ALC_EXT_disconnect");
        nextConnectedCheck = 0L;
        LOGGER.info("Voice output opened: {}", alc.getString(device, ALC_DEVICE_SPECIFIER));
    }

    private void updateListener(double[] current) {
        if (current == null) return;
        AL10.alListener3f(AL10.AL_POSITION, (float) current[0], (float) current[1], (float) current[2]);
        orientation.clear();
        for (int i = 3; i < 9; i++) orientation.put((float) current[i]);
        orientation.flip();
        AL10.alListener(AL10.AL_ORIENTATION, orientation);
    }

    private void closeDevice() {
        if (device == 0L) return;
        for (VoiceSource source : sources.all()) {
            try {
                source.closeStream();
            } catch (RuntimeException e) {
                LOGGER.debug("Failed to close a voice stream", e);
            }
        }
        try {
            if (context != 0L) {
                alc.setThreadContext(0L);
                alc.clearThreadCapabilities();
                alc.destroyContext(context);
            }
            alc.closeDevice(device);
            LOGGER.info("Voice output closed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to close the voice output device: {}", describe(e));
        } finally {
            context = 0L;
            device = 0L;
        }
    }

    private static String describe(Throwable e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return cause.toString();
    }

    /**
     * LWJGL 3 ALC entry points by name: the LWJGL 2 API compiled against here has no per-thread contexts,
     * and lwjgl3ify does not rewrite string class names, so these resolve to the real LWJGL 3 classes.
     */
    private static final class Alc {
        private final Method openDevice;
        private final Method closeDevice;
        private final Method createContext;
        private final Method destroyContext;
        private final Method isExtensionPresent;
        private final Method getInteger;
        private final Method getString;
        private final Method setThreadContext;
        private final Method createAlcCapabilities;
        private final Method createAlCapabilities;
        private final Method setCurrentThread;

        Alc() throws ReflectiveOperationException {
            Class<?> alc10 = Class.forName("org.lwjgl.openal.ALC10");
            Class<?> alc = Class.forName("org.lwjgl.openal.ALC");
            Class<?> al = Class.forName("org.lwjgl.openal.AL");
            Class<?> alcCapabilities = Class.forName("org.lwjgl.openal.ALCCapabilities");
            Class<?> alCapabilities = Class.forName("org.lwjgl.openal.ALCapabilities");
            openDevice = alc10.getMethod("alcOpenDevice", CharSequence.class);
            closeDevice = alc10.getMethod("alcCloseDevice", long.class);
            createContext = alc10.getMethod("alcCreateContext", long.class, IntBuffer.class);
            destroyContext = alc10.getMethod("alcDestroyContext", long.class);
            isExtensionPresent = alc10.getMethod("alcIsExtensionPresent", long.class, CharSequence.class);
            getInteger = alc10.getMethod("alcGetInteger", long.class, int.class);
            getString = alc10.getMethod("alcGetString", long.class, int.class);
            setThreadContext = Class.forName("org.lwjgl.openal.EXTThreadLocalContext")
                    .getMethod("alcSetThreadContext", long.class);
            createAlcCapabilities = alc.getMethod("createCapabilities", long.class);
            createAlCapabilities = al.getMethod("createCapabilities", alcCapabilities);
            setCurrentThread = al.getMethod("setCurrentThread", alCapabilities);
        }

        long openDevice() throws ReflectiveOperationException {
            return (Long) openDevice.invoke(null, (CharSequence) null);
        }

        void closeDevice(long device) throws ReflectiveOperationException {
            closeDevice.invoke(null, device);
        }

        long createContext(long device) throws ReflectiveOperationException {
            return (Long) createContext.invoke(null, device, null);
        }

        void destroyContext(long context) throws ReflectiveOperationException {
            destroyContext.invoke(null, context);
        }

        boolean isExtensionPresent(long device, String name) throws ReflectiveOperationException {
            return (Boolean) isExtensionPresent.invoke(null, device, name);
        }

        int getInteger(long device, int parameter) {
            try {
                return (Integer) getInteger.invoke(null, device, parameter);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        String getString(long device, int parameter) throws ReflectiveOperationException {
            return (String) getString.invoke(null, device, parameter);
        }

        boolean setThreadContext(long context) throws ReflectiveOperationException {
            return (Boolean) setThreadContext.invoke(null, context);
        }

        /** Function pointers for this thread only; the process-wide capabilities stay Minecraft's. */
        void createThreadCapabilities(long device) throws ReflectiveOperationException {
            Object alcCapabilities = createAlcCapabilities.invoke(null, device);
            setCurrentThread.invoke(null, createAlCapabilities.invoke(null, alcCapabilities));
        }

        void clearThreadCapabilities() throws ReflectiveOperationException {
            setCurrentThread.invoke(null, (Object) null);
        }
    }
}
