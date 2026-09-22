package su.plo.voice.platform.forge.server.connection;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;

import lombok.Getter;
import su.plo.voice.proto.data.audio.capture.CaptureInfo;
import su.plo.voice.proto.data.audio.codec.CodecInfo;
import su.plo.voice.proto.data.config.PlayerIconConfig;
import su.plo.voice.proto.data.encryption.EncryptionInfo;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

/** Configuration and key shared by all connections in one server lifecycle. */
public final class ServerConfig {
    @Getter
    private final UUID serverId = UUID.randomUUID();
    private final byte[] aesKey;
    @Getter
    private final CaptureInfo captureInfo = new CaptureInfo(48000, 1024,
            new CodecInfo("opus", Map.of("mode", "VOIP", "bitrate", "-1000")));

    public ServerConfig() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        aesKey = generator.generateKey().getEncoded();
    }

    public ConfigPacket createPacket(PublicKey publicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return new ConfigPacket(serverId, captureInfo,
                new EncryptionInfo("AES/CBC/PKCS5Padding", cipher.doFinal(aesKey)),
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap(), new PlayerIconConfig());
    }
}
