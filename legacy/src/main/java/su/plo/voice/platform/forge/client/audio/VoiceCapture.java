package su.plo.voice.platform.forge.client.audio;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

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
 * Microphone capture for one accepted server config (upstream VoiceAudioCapture + AlInputDevice).
 * Every OpenAL call happens on the capture thread; the game thread only starts and closes it.
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
    private String openedDevice;
    private final IntBuffer intBuffer = BufferUtils.createIntBuffer(1);
    private ALCdevice device;
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
            if (encoder != null) encoder.close();
        }
    }

    private boolean ensureDevice() {
        if (device != null) {
            if (!state.getInputDevice().equals(openedDevice) || state.isInputDeviceDisabled()
                    || (captureChannels == 2) != (state.isStereoCapture() || monoCaptureBroken)) {
                LOGGER.info("Microphone settings changed; reopening");
                closeDevice();
                nextOpenAttempt = 0L;
                openFailureLogged = false;
                return false;
            }
            if (hasDisconnectExt && getInteger(ALC_CONNECTED) == 0) {
                LOGGER.warn("Microphone disconnected; waiting for a device");
                closeDevice();
                return false;
            }
            if (!started) {
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
        if (now < nextOpenAttempt || !AL.isCreated() || disabled) return false;
        nextOpenAttempt = now + REOPEN_INTERVAL_MS;

        // Upstream stereo_capture: capture two channels and downmix; also the OpenAL Soft 1.25.0-1.25.1 workaround.
        monoCaptureBroken = isMonoCaptureBroken();
        captureChannels = state.isStereoCapture() || monoCaptureBroken ? 2 : 1;
        int format = captureChannels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        openedDevice = state.getInputDevice();
        ALCdevice opened = ALC11.alcCaptureOpenDevice(openedDevice.isEmpty() ? null : openedDevice, sampleRate, format, frameSize);
        if (opened == null || handle(opened) == 0L) {
            if (!openFailureLogged) LOGGER.warn("Microphone {} is not available; voice capture is idle",
                    openedDevice.isEmpty() ? "(system default)" : openedDevice);
            openFailureLogged = true;
            return false;
        }
        device = opened;
        openFailureLogged = false;
        state.getMicrophoneTest().setInputOpen(true);
        hasDisconnectExt = ALC10.alcIsExtensionPresent(device, "ALC_EXT_disconnect");
        buffer = BufferUtils.createByteBuffer(frameSize * captureChannels * 2);
        LOGGER.info("Microphone opened: {} ({} Hz, {} channel capture)",
                ALC10.alcGetString(device, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER), sampleRate, captureChannels);
        return true;
    }

    private short[] read() {
        if (getInteger(ALC11.ALC_CAPTURE_SAMPLES) < frameSize) return null;
        buffer.clear();
        ALC11.alcCaptureSamples(device, buffer, frameSize);
        short[] captured = new short[frameSize * captureChannels];
        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(captured);
        if (captureChannels == 1) return captured;

        short[] mono = new short[frameSize];
        for (int i = 0; i < frameSize; i++) mono[i] = (short) ((captured[i * 2] + captured[i * 2 + 1]) / 2);
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
        if (device == null) return;
        if (started) ALC11.alcCaptureStop(device);
        ALC11.alcCaptureCloseDevice(device);
        device = null;
        state.getMicrophoneTest().setInputOpen(false);
        started = false;
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
