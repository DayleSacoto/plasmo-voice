package su.plo.voice.platform.forge.client.audio;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.GeneralSecurityException;
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

    private final ClientConfig config;
    private final ClientState state;
    private final UdpClient udpClient;
    private final IntSupplier distance;
    private final Consumer<PlayerAudioEndPacket> endSender;
    private final int sampleRate;
    private final int frameSize;
    private final Thread thread;
    private volatile boolean closed;

    // Capture thread only.
    private final CaptureActivation activation = new CaptureActivation();
    private final MicrophoneGain gain = new MicrophoneGain();
    private final NoiseSuppression noiseSuppression;
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

    public VoiceCapture(ClientConfig config, ClientState state, UdpClient udpClient, IntSupplier distance,
                        Consumer<PlayerAudioEndPacket> endSender) {
        this.config = config;
        this.state = state;
        this.udpClient = udpClient;
        this.distance = distance;
        this.endSender = endSender;
        this.sampleRate = config.getPacket().getCaptureInfo().getSampleRate();
        this.frameSize = sampleRate / 1000 * 20;
        this.noiseSuppression = new NoiseSuppression(state);
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
        try {
            while (!closed) {
                if (!ensureDevice()) {
                    // The device went away mid-stream: listeners must not wait for the timeout.
                    if (activation.isActive()) {
                        activation.reset();
                        sendEnd();
                    }
                    Thread.sleep(1_000L);
                    continue;
                }
                short[] samples = read();
                if (samples == null) {
                    Thread.sleep(5L);
                    continue;
                }
                if (state.isMicrophoneMuted() || state.isVoiceDisabled()) {
                    if (activation.isActive()) {
                        activation.reset();
                        sendEnd();
                    }
                    continue;
                }
                gain.process(samples, (float) state.getMicrophoneVolume());
                // Upstream input filters: stereo to mono (in read()), gain, then noise suppression.
                samples = noiseSuppression.process(samples);
                MicrophoneTest test = state.getMicrophoneTest();
                test.onCaptured(samples, System.currentTimeMillis());
                if (test.isActive()) {
                    // Upstream flushes the activations during the microphone test.
                    if (activation.isActive()) {
                        activation.reset();
                        sendEnd();
                    }
                    state.setActivationActive(false);
                    continue;
                }
                CaptureActivation.Result result = activation.process(samples, state.getActivationType(),
                        state.isActivationToggled(), state.isPushToTalkPressed(), state.getActivationThreshold(),
                        System.currentTimeMillis());
                state.setActivationActive(activation.isActive());
                if (result == CaptureActivation.Result.ACTIVATED) {
                    sendFrame(samples);
                } else if (result == CaptureActivation.Result.END) {
                    sendEnd();
                }
            }
        } catch (InterruptedException ignored) {
            // closed
        } catch (RuntimeException e) {
            LOGGER.error("Voice capture stopped unexpectedly", e);
        } finally {
            state.setActivationActive(false);
            closeDevice();
            noiseSuppression.close();
            if (encoder != null) encoder.close();
        }
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
        // OpenAL natives are loaded by the game's sound system.
        boolean disabled = state.isInputDeviceDisabled();
        // A new choice in the settings is tried right away instead of after the reopen interval.
        if ((wasDisabled && !disabled) || !state.getInputDevice().equals(openedDevice)) {
            nextOpenAttempt = 0L;
            openFailureLogged = false;
        }
        wasDisabled = disabled;
        if (disabled) state.setInputDeviceFailed(false);
        if (now < nextOpenAttempt || !AL.isCreated() || disabled) return false;
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
        state.setInputDeviceFailed(false);
        state.getMicrophoneTest().setInputOpen(true);
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

    private boolean openOpenAl(boolean stereo) {
        // Upstream stereo_capture: capture two channels and downmix; also the OpenAL Soft 1.25.0-1.25.1 workaround.
        monoCaptureBroken = isMonoCaptureBroken();
        captureChannels = stereo || monoCaptureBroken ? 2 : 1;
        int format = captureChannels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        ALCdevice opened = ALC11.alcCaptureOpenDevice(openedDevice.isEmpty() ? null : openedDevice, sampleRate, format, frameSize);
        if (opened == null || handle(opened) == 0L) {
            if (!openFailureLogged) LOGGER.warn("OpenAL microphone {} is not available; trying Java Sound", describe(openedDevice));
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
            if (!openFailureLogged) LOGGER.warn("Java Sound microphone is not available: {}", e.toString());
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
        return captureChannels == 1 ? captured : toMono(captured);
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
            AesEncryption encryption = config.getEncryption();
            if (encryption != null) data = encryption.encrypt(data);
            udpClient.send(new PlayerAudioPacket(++sequenceNumber, data, VoiceActivation.PROXIMITY_ID,
                    (short) distance.getAsInt(), false));
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.debug("Dropped a microphone frame", e);
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
