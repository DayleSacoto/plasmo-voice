package su.plo.voice.platform.forge.client.audio;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import javax.sound.sampled.LineUnavailableException;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCdevice;
import su.plo.voice.platform.forge.audio.codec.AudioEncoder;
import su.plo.voice.platform.forge.audio.codec.OpusCodec;
import su.plo.voice.platform.forge.client.ClientState;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.UdpClient;
import su.plo.voice.platform.forge.debug.DebugInterval;
import su.plo.voice.platform.forge.debug.VoiceDebug;
import su.plo.voice.platform.forge.debug.VoiceDebug.Category;
import su.plo.voice.platform.forge.encryption.AesEncryption;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

/**
 * Microphone capture for one accepted server config (upstream VoiceAudioCapture + AlInputDevice, with the
 * JavaxInputDevice fallback). Every OpenAL and Java Sound call happens on the capture thread; the game thread only
 * starts and closes it.
 */
@SideOnly(Side.CLIENT)
public final class VoiceCapture implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("Plasmo Voice");
    private static final int ALC_CONNECTED = 0x313; // ALC_EXT_disconnect
    private static final long REOPEN_INTERVAL_MS = 5_000L;
    private static final VoiceDebug DEBUG = VoiceDebug.CLIENT;

    private final ClientConfig config;
    private final ClientState state;
    private final UdpClient udpClient;
    private final IntSupplier distance;
    private final Consumer<PlayerAudioEndPacket> endSender;
    /** Upstream isServerMuted: the local player's VoicePlayerInfo says the server muted it. */
    private final BooleanSupplier serverMuted;
    private final int sampleRate;
    private final int frameSize;
    private final Thread thread;
    private volatile boolean closed;

    // Capture thread only.
    private final CapturePipeline pipeline;
    private final CaptureActivation activation;
    private String openedDevice;
    private final IntBuffer intBuffer = BufferUtils.createIntBuffer(1);
    private ALCdevice device;
    private JavaxInput javaxInput;
    private boolean hasDisconnectExt;
    private boolean started;
    private int captureChannels;
    private boolean monoCaptureBroken;
    private boolean wasDisabled;
    private ByteBuffer buffer;
    private AudioEncoder encoder;
    private long sequenceNumber;
    private long nextOpenAttempt;
    private boolean openFailureLogged;
    // Capture thread diagnostics, counted only while debug logging is enabled; never the audio itself.
    private final DebugInterval summaries = new DebugInterval(5_000L);
    private Backend openedBackend;
    private long framesRead;
    private long framesActive;
    private long framesEncoded;
    private long packetsSubmitted;
    private long frameFailures;
    private long activationFrames;
    /** Loudest raw and processed frame levels since the last summary; computed only while debug logging is enabled. */
    private double rawLevel = -127D;

    public VoiceCapture(ClientConfig config, ClientState state, UdpClient udpClient, IntSupplier distance,
                        Consumer<PlayerAudioEndPacket> endSender, BooleanSupplier serverMuted) {
        this.config = config;
        this.state = state;
        this.udpClient = udpClient;
        this.distance = distance;
        this.endSender = endSender;
        this.serverMuted = serverMuted;
        this.sampleRate = config.getPacket().getCaptureInfo().getSampleRate();
        this.frameSize = sampleRate / 1000 * 20;
        this.pipeline = new CapturePipeline(state, new NoiseSuppression(state), this::sendFrame, this::sendEnd);
        this.activation = pipeline.activation;
        this.thread = new Thread(this::run, "plasmo-voice-capture");
        thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Non-blocking: the capture thread releases the device and encoder on its way out. */
    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }

    private void run() {
        DEBUG.log(Category.THREAD, "capture thread started: thread={}", VoiceDebug.thread());
        try {
            while (!closed) {
                if (DEBUG.enabled() && summaries.due(System.currentTimeMillis())) summary();
                // Upstream waits without touching the activation; listeners time the stream out.
                if (!ensureDevice()) {
                    Thread.sleep(1_000L);
                    continue;
                }
                short[] samples = read();
                if (samples == null) {
                    Thread.sleep(5L);
                    continue;
                }
                if (DEBUG.enabled()) {
                    framesRead++;
                    rawLevel = Math.max(rawLevel, CaptureActivation.highestAudioLevel(samples));
                }
                boolean wasActive = activation.isActive();
                CaptureActivation.Result result = pipeline.process(samples, rawChannels(), serverMuted.getAsBoolean(),
                        System.currentTimeMillis());
                if (DEBUG.enabled()) activationDiagnostics(wasActive, result);
            }
        } catch (InterruptedException ignored) {
            // closed
        } catch (RuntimeException e) {
            LOGGER.error("Voice capture stopped unexpectedly", e);
        } catch (Error e) {
            DEBUG.error(Category.THREAD, "capture thread failed", e);
            throw e;
        } finally {
            state.setActivationActive(false);
            closeDevice();
            pipeline.close();
            if (encoder != null) encoder.close();
            DEBUG.log(Category.THREAD, "capture thread stopped: closed={}, framesRead={}, framesEncoded={}, packetsSubmitted={}",
                    closed, framesRead, framesEncoded, packetsSubmitted);
        }
    }

    /** Capture thread: activation start/end transitions, logged once each. */
    private void activationDiagnostics(boolean wasActive, CaptureActivation.Result result) {
        if (result == CaptureActivation.Result.ACTIVATED) {
            framesActive++;
            activationFrames++;
        }
        boolean active = activation.isActive();
        if (!wasActive && active) {
            activationFrames = result == CaptureActivation.Result.ACTIVATED ? 1 : 0;
            DEBUG.log(Category.AUDIO, "activation started: type={}, toggled={}, pushToTalk={}, threshold={}dB, distance={}, sequence={}",
                    state.getActivationType(), state.isActivationToggled(), state.isPushToTalkPressed(),
                    state.getActivationThreshold(), distance.getAsInt(), sequenceNumber);
        } else if (wasActive && !active) {
            DEBUG.log(Category.AUDIO, "activation ended: result={}, frames={}, sequence={}", result, activationFrames, sequenceNumber);
        }
    }

    /** Capture thread, every 5 seconds while debug logging is enabled. */
    private void summary() {
        DEBUG.log(Category.AUDIO, "capture summary: device={}, backend={}, channels={}, stereoRequested={}, framesRead={}, "
                        + "framesActive={}, framesEncoded={}, packetsSubmitted={}, frameFailures={}, activation={}, active={}, "
                        + "threshold={}dB, peakRawLevel={}dB, peakProcessedLevel={}dB, distance={}, microphoneVolume={}, "
                        + "noiseSuppression={}, muted={}, serverMuted={}, voiceDisabled={}, inputDisabled={}, microphoneTest={}",
                device != null || javaxInput != null ? describe(openedDevice) : "none", openedBackend, captureChannels,
                state.isStereoCapture(), framesRead, framesActive, framesEncoded, packetsSubmitted, frameFailures,
                state.getActivationType(), activation.isActive(), state.getActivationThreshold(),
                String.format("%.1f", rawLevel), String.format("%.1f", pipeline.processedLevel), distance.getAsInt(),
                state.getMicrophoneVolume(), state.isNoiseSuppression(), state.isMicrophoneMuted(), serverMuted.getAsBoolean(),
                state.isVoiceDisabled(), state.isInputDeviceDisabled(), state.getMicrophoneTest().isActive());
        rawLevel = -127D;
        pipeline.processedLevel = -127D;
    }

    private boolean ensureDevice() {
        if (device != null || javaxInput != null) {
            if (!state.getInputDevice().equals(openedDevice) || state.isInputDeviceDisabled()
                    || (captureChannels == 2) != (state.isStereoCapture() || monoCaptureBroken)) {
                LOGGER.info("Microphone settings changed; reopening");
                closeDevice();
                nextOpenAttempt = 0L;
                openFailureLogged = false;
                return false;
            }
            if (javaxInput != null ? !javaxInput.isOpen() : hasDisconnectExt && getInteger(ALC_CONNECTED) == 0) {
                LOGGER.warn("Microphone disconnected; waiting for a device");
                closeDevice();
                return false;
            }
            if (device != null && !started) {
                ALC11.alcCaptureStart(device);
                started = true;
            }
            return true;
        }
        long now = System.currentTimeMillis();
        boolean disabled = state.isInputDeviceDisabled();
        // A new choice in the settings is tried right away instead of after the reopen interval.
        if ((wasDisabled && !disabled) || !state.getInputDevice().equals(openedDevice)) {
            nextOpenAttempt = 0L;
            openFailureLogged = false;
        }
        wasDisabled = disabled;
        if (disabled) state.setInputDeviceFailed(false);
        if (now < nextOpenAttempt || !canOpen(state.isUseJavaxInput(), AL.isCreated()) || disabled) return false;
        nextOpenAttempt = now + REOPEN_INTERVAL_MS;

        openedDevice = state.getInputDevice();
        boolean stereo = state.isStereoCapture();
        Backend backend = openBackend(state.isUseJavaxInput(), () -> openOpenAl(stereo), () -> openJavax(stereo));
        if (backend == null) {
            if (!openFailureLogged) {
                LOGGER.warn("Microphone {} is not available; voice capture is idle", describe(openedDevice));
                JavaxInput.logSupportedLines(LOGGER);
            }
            openFailureLogged = true;
            state.setInputDeviceFailed(true);
            return false;
        }
        openFailureLogged = false;
        openedBackend = backend;
        state.setInputDeviceFailed(false);
        state.getMicrophoneTest().setInputOpen(true);
        DEBUG.log(Category.DEVICE, "microphone ready: requested={}, backend={}, useJavaxInput={}, stereoRequested={}, channels={}, "
                        + "monoCaptureWorkaround={}, sampleRate={}, frameSize={}",
                describe(openedDevice), backend, state.isUseJavaxInput(), stereo, captureChannels, monoCaptureBroken,
                sampleRate, frameSize);
        if (backend == Backend.OPENAL) {
            LOGGER.info("Microphone opened: {} (OpenAL, {} Hz, {} channel capture)",
                    ALC10.alcGetString(device, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER), sampleRate, captureChannels);
        } else {
            LOGGER.info("Microphone opened: {} (Java Sound, {} Hz, {} channel capture)",
                    javaxInput.getName(), sampleRate, captureChannels);
        }
        return true;
    }

    enum Backend { OPENAL, JAVAX }

    /** Upstream VoiceDeviceManager.openInputDevice: use_javax_input skips OpenAL, else Java Sound only after OpenAL fails. */
    static Backend openBackend(boolean useJavaxInput, BooleanSupplier openAl, BooleanSupplier openJavax) {
        if (!useJavaxInput && openAl.getAsBoolean()) return Backend.OPENAL;
        return openJavax.getAsBoolean() ? Backend.JAVAX : null;
    }

    /** OpenAL natives are loaded by the game's sound system; Java Sound only capture does not need them. */
    static boolean canOpen(boolean useJavaxInput, boolean alCreated) {
        return useJavaxInput || alCreated;
    }

    /**
     * Upstream VoiceDeviceManager.getDeviceName: a stored name OpenAL no longer lists means the default capture
     * device. Unlike upstream the stored name is kept, so the device is picked again once it is back.
     */
    static String openAlDevice(String configured, List<String> available) {
        return configured.isEmpty() || available.contains(configured) ? configured : "";
    }

    private boolean openOpenAl(boolean stereo) {
        // Upstream stereo_capture: capture two channels and downmix; also the OpenAL Soft 1.25.0-1.25.1 workaround.
        monoCaptureBroken = isMonoCaptureBroken();
        captureChannels = stereo || monoCaptureBroken ? 2 : 1;
        int format = captureChannels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        String name = openAlDevice(openedDevice, Lwjgl3Alc.inputDevices());
        if (!name.equals(openedDevice) && !openFailureLogged) {
            LOGGER.warn("OpenAL microphone {} is not available; using the default capture device", openedDevice);
        }
        ALCdevice opened = ALC11.alcCaptureOpenDevice(name.isEmpty() ? null : name, sampleRate, format, frameSize);
        if (opened == null || handle(opened) == 0L) {
            if (!openFailureLogged) LOGGER.warn("OpenAL microphone {} is not available; trying Java Sound", describe(name));
            return false;
        }
        device = opened;
        hasDisconnectExt = ALC10.alcIsExtensionPresent(device, "ALC_EXT_disconnect");
        buffer = BufferUtils.createByteBuffer(frameSize * captureChannels * 2);
        return true;
    }

    /** Upstream opens Java Sound in the same format; a stored OpenAL device name falls back to the default mixer. */
    private boolean openJavax(boolean stereo) {
        monoCaptureBroken = false;
        captureChannels = stereo ? 2 : 1;
        try {
            javaxInput = JavaxInput.open(openedDevice, sampleRate, captureChannels);
            return true;
        } catch (LineUnavailableException | RuntimeException e) {
            if (!openFailureLogged) {
                LOGGER.warn("Java Sound microphone is not available: {}", e.toString());
                DEBUG.error(Category.DEVICE, "Java Sound open failed: requested={}, channels={}", e,
                        describe(openedDevice), captureChannels);
            }
            return false;
        }
    }

    private static String describe(String device) {
        return device.isEmpty() ? "(system default)" : device;
    }

    private short[] read() {
        short[] captured;
        if (javaxInput != null) {
            // Upstream reads frameSize shorts whatever the channel count; a whole 20 ms frame is read here.
            captured = javaxInput.read(frameSize * captureChannels);
            if (captured == null) return null;
        } else {
            if (getInteger(ALC11.ALC_CAPTURE_SAMPLES) < frameSize) return null;
            buffer.clear();
            ALC11.alcCaptureSamples(device, buffer, frameSize);
            captured = new short[frameSize * captureChannels];
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(captured);
        }
        // Upstream AlInputDevice.read: the mono capture workaround is downmixed by the device itself.
        return monoCaptureBroken ? toMono(captured) : captured;
    }

    /** Channels of the frames read(): the stereo_capture channel count; the workaround is already mono. */
    private int rawChannels() {
        return monoCaptureBroken ? 1 : captureChannels;
    }

    /** Upstream StereoToMonoFilter (AudioUtil.convertToMonoShorts). */
    static short[] toMono(short[] stereo) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) mono[i] = (short) ((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
        return mono;
    }

    private void sendFrame(short[] samples) {
        try {
            if (encoder == null) {
                encoder = OpusCodec.createEncoder(config.getPacket().getCaptureInfo().getEncoderInfo(), sampleRate,
                        false, config.getPacket().getCaptureInfo().getMtuSize());
            }
            byte[] data = encoder.encode(samples);
            if (DEBUG.enabled()) framesEncoded++;
            AesEncryption encryption = config.getEncryption();
            if (encryption != null) data = encryption.encrypt(data);
            udpClient.send(new PlayerAudioPacket(++sequenceNumber, data, VoiceActivation.PROXIMITY_ID,
                    (short) distance.getAsInt(), false));
            if (DEBUG.enabled()) packetsSubmitted++;
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.debug("Dropped a microphone frame", e);
            if (DEBUG.enabled() && ++frameFailures == 1) DEBUG.error(Category.CODEC, "microphone frame dropped (first failure)", e);
        }
    }

    private void sendEnd() {
        if (encoder != null) encoder.reset();
        endSender.accept(new PlayerAudioEndPacket(++sequenceNumber, VoiceActivation.PROXIMITY_ID, (short) distance.getAsInt()));
    }

    private int getInteger(int parameter) {
        intBuffer.clear();
        ALC10.alcGetInteger(device, parameter, intBuffer);
        return intBuffer.get(0);
    }

    private void closeDevice() {
        if (javaxInput != null) {
            javaxInput.close();
            javaxInput = null;
        } else if (device != null) {
            if (started) ALC11.alcCaptureStop(device);
            ALC11.alcCaptureCloseDevice(device);
            device = null;
            started = false;
        } else {
            return;
        }
        state.getMicrophoneTest().setInputOpen(false);
        LOGGER.info("Microphone closed");
    }

    /**
     * Upstream fails on a zero device pointer. LWJGL 2 returns null then, but lwjgl3ify wraps the zero pointer,
     * so the handle is read from the field both ALCdevice versions declare.
     */
    private static long handle(ALCdevice device) {
        try {
            Field field = ALCdevice.class.getDeclaredField("device");
            field.setAccessible(true);
            return field.getLong(device);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported OpenAL binding", e);
        }
    }

    /** Upstream AlUtil: OpenAL Soft 1.25.0-1.25.1 breaks mono capture, so capture stereo and downmix. */
    private static boolean isMonoCaptureBroken() {
        return isBrokenAlSoftVersion(AL10.alGetString(AL10.AL_VERSION));
    }

    static boolean isBrokenAlSoftVersion(String version) {
        if (version == null) return false;
        String[] parts = version.split(" ");
        String[] numbers = parts[parts.length - 1].split("\\.");
        if (numbers.length < 3) return false;
        try {
            int major = Integer.parseInt(numbers[0]);
            int minor = Integer.parseInt(numbers[1]);
            int patch = Integer.parseInt(numbers[2]);
            return major == 1 && minor == 25 && patch < 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
