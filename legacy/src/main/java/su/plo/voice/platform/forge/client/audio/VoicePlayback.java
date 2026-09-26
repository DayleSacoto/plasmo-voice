package su.plo.voice.platform.forge.client.audio;

import java.nio.FloatBuffer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.debug.DebugInterval;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
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
    private static final int ALC_ALL_DEVICES_SPECIFIER = 0x1013;
    private static final int ALC_CONNECTED = 0x313; // ALC_EXT_disconnect
    private static final long REOPEN_INTERVAL_MS = 5_000L;
    private static final long CONNECTED_CHECK_INTERVAL_MS = 1_000L;
    private static final long LOOP_INTERVAL_MS = 5L;
    private static final VoiceDebug DEBUG = VoiceDebug.CLIENT;

    private final ClientConfig config;
    private final ClientState state;
    private final ClientVoiceSources sources;
    private final Thread thread;
    private volatile boolean closed;
    /** Eye position, look and up vectors of the camera; published by the client thread every frame. */
    private volatile double[] listener;

    // Playback thread only.
    private final FloatBuffer orientation = BufferUtils.createFloatBuffer(6);
    private Lwjgl3Alc alc;
    private long device;
    private String openedDevice;
    private long context;
    private boolean hasDisconnectExt;
    private long nextOpenAttempt;
    private long nextConnectedCheck;
    private boolean openFailureLogged;
    private boolean openedHrtf;
    private StreamSource loopback;
    private final DebugInterval summaries = new DebugInterval(5_000L);

    public VoicePlayback(ClientConfig config, ClientState state, ClientVoiceSources sources) {
        this.config = config;
        this.state = state;
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
        // Upstream getListener: the camera entity (advanced.camera_sound_listener, pv.allow_freecam), else the player.
        EntityLivingBase camera = state.isCameraSoundListener() ? minecraft.renderViewEntity : minecraft.thePlayer;
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

        long now = System.currentTimeMillis();
        for (VoiceSource source : sources.all()) {
            SourceInfo info = source.info;
            if (!(info instanceof PlayerSourceInfo)) continue;
            EntityPlayer player = world.func_152378_a(((PlayerSourceInfo) info).getPlayerInfo().getPlayerId());
            source.position = player == null ? null : eyePosition(player, partialTicks);
            if (player == null) continue;
            Vec3 look = player.getLook(partialTicks);
            source.look = new double[] {look.xCoord, look.yCoord, look.zCoord};
            updateOcclusion(minecraft, world, source, now);
        }
    }

    /** Upstream calculateOcclusion: from the source to the local player's eyes, only while the source plays. */
    private void updateOcclusion(Minecraft minecraft, World world, VoiceSource source, long now) {
        if (!state.isSoundOcclusion() || !source.activated || minecraft.thePlayer == null) {
            source.occlusion = 0D;
            return;
        }
        if (now - source.occlusionAt < VoiceSource.OCCLUSION_INTERVAL_MS) return;
        source.occlusionAt = now;
        double[] position = source.position;
        if (position != null) source.occlusion = SoundOcclusion.occludedPercent(world, position, eyePosition(minecraft.thePlayer, 1F));
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
        DEBUG.log(Category.THREAD, "playback thread started: thread={}", VoiceDebug.thread());
        try {
            while (!closed) {
                long now = System.currentTimeMillis();
                if (DEBUG.enabled() && summaries.due(now)) {
                    DEBUG.log(Category.SOURCE, "playback summary: output={}, sources={}, hrtf={}",
                            device == 0L ? "none" : openedDevice.isEmpty() ? "(system default)" : openedDevice,
                            sources.all().size(), openedHrtf);
                    for (VoiceSource source : sources.all()) source.summary(now, listener != null);
                }
                if (ensureDevice(now)) {
                    double[] current = listener;
                    updateListener(current);
                    for (VoiceSource source : sources.all()) {
                        if (source.pump(config, state, current, state.volume(config, source.info), now)) {
                            source.release();
                            sources.remove(source);
                            if (DEBUG.enabled()) DEBUG.log(Category.SOURCE, "idle source closed: source={}", source.info.getId());
                        }
                    }
                    pumpLoopback(now);
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
        } catch (Error e) {
            DEBUG.error(Category.THREAD, "playback thread failed", e);
            throw e;
        } finally {
            closeDevice();
            for (VoiceSource source : sources.all()) source.release();
            DEBUG.log(Category.THREAD, "playback thread stopped: closed={}", closed);
        }
    }

    private boolean ensureDevice(long now) {
        if (device != 0L) {
            if (state.isHrtf() != openedHrtf) {
                // Upstream reloads the output device when the HRTF setting changes.
                LOGGER.info("HRTF setting changed; reopening the voice output");
                closeDevice();
                nextOpenAttempt = 0L;
                return false;
            }
            if (!state.getOutputDevice().equals(openedDevice)) {
                LOGGER.info("Voice output device changed; reopening");
                closeDevice();
                nextOpenAttempt = 0L;
                return false;
            }
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
        // A new choice in the settings is tried right away instead of after the reopen interval.
        if (!state.getOutputDevice().equals(openedDevice)) {
            nextOpenAttempt = 0L;
            openFailureLogged = false;
        }
        // OpenAL natives are loaded by the game's sound system.
        if (now < nextOpenAttempt || !AL.isCreated()) return false;
        nextOpenAttempt = now + REOPEN_INTERVAL_MS;
        try {
            openDevice();
            openFailureLogged = false;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!openFailureLogged) {
                LOGGER.warn("Voice output unavailable; playback is idle: {}", Lwjgl3Alc.describe(e));
                DEBUG.error(Category.DEVICE, "voice output open failed: requested={}", e,
                        openedDevice == null || openedDevice.isEmpty() ? "(system default)" : openedDevice);
            }
            openFailureLogged = true;
            closeDevice();
            return false;
        }
    }

    private void openDevice() throws ReflectiveOperationException {
        if (alc == null) alc = Lwjgl3Alc.get();
        openedDevice = state.getOutputDevice();
        device = alc.openDevice(openedDevice);
        if (device == 0L) throw new IllegalStateException("no output device");
        if (!alc.isExtensionPresent(device, "ALC_EXT_thread_local_context")) {
            throw new IllegalStateException("ALC_EXT_thread_local_context is not supported");
        }
        context = alc.createContext(device);
        if (context == 0L) throw new IllegalStateException("failed to create an OpenAL context");
        if (!alc.setThreadContext(context)) throw new IllegalStateException("failed to make the context current");
        alc.createThreadCapabilities(device);
        openedHrtf = state.isHrtf();
        if (openedHrtf && alc.isExtensionPresent(device, "ALC_SOFT_HRTF")) enableHrtf();

        // Gain is computed per source like upstream; OpenAL only pans.
        AL10.alDistanceModel(AL10.AL_NONE);
        AL10.alListenerf(AL10.AL_GAIN, 1F);
        hasDisconnectExt = alc.isExtensionPresent(device, "ALC_EXT_disconnect");
        nextConnectedCheck = 0L;
        LOGGER.info("Voice output opened: {}", alc.getString(device, ALC_ALL_DEVICES_SPECIFIER));
        if (DEBUG.enabled()) {
            DEBUG.log(Category.DEVICE, "voice output ready: requested={}, hrtfRequested={}, hrtfEnabled={}, disconnectExt={}",
                    openedDevice.isEmpty() ? "(system default)" : openedDevice, openedHrtf,
                    openedHrtf && alc.getInteger(device, Lwjgl3Alc.ALC_HRTF_SOFT) > 0, hasDisconnectExt);
        }
    }

    private void updateListener(double[] current) {
        if (current == null) return;
        AL10.alListener3f(AL10.AL_POSITION, (float) current[0], (float) current[1], (float) current[2]);
        orientation.clear();
        for (int i = 3; i < 9; i++) orientation.put((float) current[i]);
        orientation.flip();
        AL10.alListener(AL10.AL_ORIENTATION, orientation);
    }

    /** Upstream LoopbackSource of the microphone test: the processed microphone, centered, at the voice volume. */
    private void pumpLoopback(long now) {
        MicrophoneTest test = state.getMicrophoneTest();
        short[] frame;
        while ((frame = test.poll()) != null) {
            if (loopback == null) {
                int sampleRate = config.getPacket().getCaptureInfo().getSampleRate();
                loopback = new StreamSource(false, sampleRate, sampleRate / 1000 * 20, state.getAlPlaybackBuffers(), now);
                loopback.setPosition(true, 0F, 0F, 0F);
            }
            loopback.setGain((float) VoiceSource.sliderGain(state.getVolume(), state.isExponentialVolumeSlider()));
            loopback.write(frame, now);
        }
        if (loopback == null) return;
        loopback.update(now);
        if (!test.isActive() && now - loopback.lastBufferTime() > VoiceSource.STREAM_IDLE_CLOSE_MS) closeLoopback();
    }

    private void closeLoopback() {
        if (loopback != null) loopback.close();
        loopback = null;
    }

    /** Upstream AlOutputDevice.enableHrtf: only when the device offers an HRTF. */
    private void enableHrtf() throws ReflectiveOperationException {
        int specifiers = alc.getInteger(device, Lwjgl3Alc.ALC_NUM_HRTF_SPECIFIERS_SOFT);
        DEBUG.log(Category.DEVICE, "HRTF requested: available specifiers={}", specifiers);
        if (specifiers <= 0) return;
        if (!alc.resetDeviceHrtf(device, true)) LOGGER.warn("Failed to reset the voice output device for HRTF");
        if (alc.getInteger(device, Lwjgl3Alc.ALC_HRTF_SOFT) > 0) {
            LOGGER.info("HRTF enabled, using {}", alc.getString(device, Lwjgl3Alc.ALC_HRTF_SPECIFIER_SOFT));
        } else {
            LOGGER.warn("Failed to enable HRTF");
        }
    }

    private void closeDevice() {
        if (device == 0L) return;
        try {
            closeLoopback();
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to close the microphone test stream", e);
        }
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
            LOGGER.warn("Failed to close the voice output device: {}", Lwjgl3Alc.describe(e));
        } finally {
            context = 0L;
            device = 0L;
        }
    }
}
