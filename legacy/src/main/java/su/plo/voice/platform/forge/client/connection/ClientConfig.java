package su.plo.voice.platform.forge.client.connection;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import su.plo.voice.proto.data.encryption.EncryptionInfo;
import su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket;

/** Accepted protocol configuration; does not initialize codecs or audio devices. */
@SideOnly(Side.CLIENT)
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClientConfig {
    private final ConfigPacket packet;
    private final SecretKeySpec aesKey;

    public static ClientConfig decode(ConfigPacket packet, PrivateKey privateKey) throws GeneralSecurityException {
        EncryptionInfo encryption = packet.getEncryption();
        if (encryption == null) return new ClientConfig(packet, null);
        if (!"AES/CBC/PKCS5Padding".equals(encryption.getAlgorithm())) {
            throw new GeneralSecurityException("Unsupported encryption algorithm");
        }
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] key = cipher.doFinal(encryption.getData());
        try {
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new GeneralSecurityException("Invalid AES key length");
            }
            return new ClientConfig(packet, new SecretKeySpec(key, "AES"));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
