package su.plo.voice.platform.forge.server.connection;

import java.security.KeyPairGenerator;

import org.junit.Test;
import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.server.connection.ServerConnection.PlayerInfoResult;

import static org.junit.Assert.*;

public class ServerVersionTest {
    private static final String SERVER = "2.1.17";
    private static final String MIN = ServerConnection.DEFAULT_CLIENT_MOD_MIN_VERSION;

    @Test
    public void currentVersionIsAccepted() {
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion("2.1.17", SERVER, MIN));
        // Upstream clients report loader-prefixed and snapshot versions.
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion("neoforge-1.21.1-2.1.17", SERVER, MIN));
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion("2.1.17+ALPHA", SERVER, MIN));
    }

    /**
     * The handshake carries the voice compatibility version. The release version of this mod has its own numbering
     * and is not a voice version: a server, upstream or legacy, would turn such a client away.
     */
    @Test
    public void handshakeVersionIsTheVoiceCompatibilityVersionNotTheReleaseVersion() {
        assertEquals(SERVER, PlasmoVoiceMod.VOICE_COMPAT_VERSION);
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion(PlasmoVoiceMod.VOICE_COMPAT_VERSION,
                PlasmoVoiceMod.VOICE_COMPAT_VERSION, MIN));
        assertEquals(PlayerInfoResult.UNSUPPORTED_VERSION, ServerConnection.checkVersion("0.4.0", SERVER, MIN));
    }

    @Test
    public void wrongMajorIsUnsupported() {
        assertEquals(PlayerInfoResult.UNSUPPORTED_VERSION, ServerConnection.checkVersion("1.0.12", SERVER, MIN));
        assertEquals(PlayerInfoResult.UNSUPPORTED_VERSION, ServerConnection.checkVersion("3.0.0", SERVER, MIN));
    }

    @Test
    public void versionBelowMinimumIsUnsupported() {
        assertEquals(PlayerInfoResult.UNSUPPORTED_VERSION, ServerConnection.checkVersion("2.0.9", SERVER, "2.1.0"));
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion("2.1.0", SERVER, "2.1.0"));
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion("2.0.0", SERVER, MIN));
    }

    @Test
    public void unparsableMinimumFallsBackToDefault() {
        assertEquals(PlayerInfoResult.ACCEPTED, ServerConnection.checkVersion("2.0.0", SERVER, "latest"));
    }

    @Test
    public void malformedVersionIsRejected() {
        assertEquals(PlayerInfoResult.MALFORMED_VERSION, ServerConnection.checkVersion("abc", SERVER, MIN));
        assertEquals(PlayerInfoResult.MALFORMED_VERSION, ServerConnection.checkVersion("", SERVER, MIN));
        assertEquals(PlayerInfoResult.MALFORMED_VERSION, ServerConnection.checkVersion("2.1", SERVER, MIN));
        assertEquals(PlayerInfoResult.MALFORMED_VERSION, ServerConnection.checkVersion("2.1.99999999999", SERVER, MIN));
    }

    @Test
    public void onlyValidRsaKeysDecode() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        assertNotNull(ServerConnection.decodePublicKey(generator.generateKeyPair().getPublic().getEncoded()));
        assertNull(ServerConnection.decodePublicKey(new byte[] {1, 2, 3}));
        assertNull(ServerConnection.decodePublicKey(new byte[0]));
    }
}
