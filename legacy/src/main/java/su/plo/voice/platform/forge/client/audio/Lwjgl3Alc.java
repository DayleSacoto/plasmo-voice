package su.plo.voice.platform.forge.client.audio;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * LWJGL 3 ALC entry points by name: the LWJGL 2 API compiled against here has neither per-thread contexts
 * nor device lists, and lwjgl3ify does not rewrite string class names, so these resolve to the real LWJGL 3 classes.
 */
@SideOnly(Side.CLIENT)
public final class Lwjgl3Alc {
    private static final int ALC_DEFAULT_ALL_DEVICES_SPECIFIER = 0x1012;
    private static final int ALC_ALL_DEVICES_SPECIFIER = 0x1013;
    private static final int ALC_CAPTURE_DEVICE_SPECIFIER = 0x310;
    private static final int ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER = 0x311;
    static final int ALC_HRTF_SOFT = 0x1992;
    static final int ALC_NUM_HRTF_SPECIFIERS_SOFT = 0x1994;
    static final int ALC_HRTF_SPECIFIER_SOFT = 0x1995;
    private static final int ALC_HRTF_ID_SOFT = 0x1996;

    private static Lwjgl3Alc instance;

    private final Method openDevice;
    private final Method closeDevice;
    private final Method createContext;
    private final Method destroyContext;
    private final Method isExtensionPresent;
    private final Method getInteger;
    private final Method getString;
    private final Method getStringList;
    private final Method setThreadContext;
    private final Method createAlcCapabilities;
    private final Method createAlCapabilities;
    private final Method setCurrentThread;
    private final Method resetDevice;

    private Lwjgl3Alc() throws ReflectiveOperationException {
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
        getStringList = Class.forName("org.lwjgl.openal.ALUtil").getMethod("getStringList", long.class, int.class);
        setThreadContext = Class.forName("org.lwjgl.openal.EXTThreadLocalContext")
                .getMethod("alcSetThreadContext", long.class);
        createAlcCapabilities = alc.getMethod("createCapabilities", long.class);
        createAlCapabilities = al.getMethod("createCapabilities", alcCapabilities);
        setCurrentThread = al.getMethod("setCurrentThread", alCapabilities);
        resetDevice = Class.forName("org.lwjgl.openal.SOFTHRTF").getMethod("alcResetDeviceSOFT", long.class, int[].class);
    }

    /** Throws when LWJGL 3 is not present (plain LWJGL 2 without lwjgl3ify). */
    public static synchronized Lwjgl3Alc get() throws ReflectiveOperationException {
        if (instance == null) instance = new Lwjgl3Alc();
        return instance;
    }

    /** Upstream AlInputDeviceFactory.getDeviceNames; empty when OpenAL cannot enumerate. */
    public static List<String> inputDevices() {
        return deviceList(ALC_CAPTURE_DEVICE_SPECIFIER);
    }

    /** Upstream AlOutputDeviceFactory.getDeviceNames. */
    public static List<String> outputDevices() {
        return deviceList(ALC_ALL_DEVICES_SPECIFIER);
    }

    public static String defaultInputDevice() {
        return defaultDevice(ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER);
    }

    public static String defaultOutputDevice() {
        return defaultDevice(ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
    }

    @SuppressWarnings("unchecked")
    private static List<String> deviceList(int specifier) {
        try {
            List<String> devices = (List<String>) get().getStringList.invoke(null, 0L, specifier);
            return devices == null ? Collections.<String>emptyList() : devices;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static String defaultDevice(int specifier) {
        try {
            return get().getString(0L, specifier);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Null or empty opens the system default device. */
    long openDevice(String name) throws ReflectiveOperationException {
        return (Long) openDevice.invoke(null, name == null || name.isEmpty() ? null : name);
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

    /** ALC_SOFT_HRTF alcResetDeviceSOFT with ALC_HRTF_SOFT and the default HRTF (ALC_HRTF_ID_SOFT 0). */
    boolean resetDeviceHrtf(long device, boolean enabled) throws ReflectiveOperationException {
        int[] attributes = {ALC_HRTF_SOFT, enabled ? 1 : 0, ALC_HRTF_ID_SOFT, 0, 0};
        return (Boolean) resetDevice.invoke(null, device, attributes);
    }

    static String describe(Throwable e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return cause.toString();
    }
}
