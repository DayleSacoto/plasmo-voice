package su.plo.voice.platform.forge.encryption;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Port of upstream su.plo.voice.encryption.aes.AesEncryption.
 * Wire format: a random 16-byte IV followed by the AES/CBC/PKCS5Padding ciphertext.
 */
public final class AesEncryption {
    public static final String CIPHER = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesEncryption(byte[] keyData) {
        this.key = new SecretKeySpec(keyData, "AES");
    }

    public byte[] encrypt(byte[] data) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(data);

        byte[] result = Arrays.copyOf(iv, IV_LENGTH + encrypted.length);
        System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);
        return result;
    }

    public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        if (encrypted.length < IV_LENGTH) throw new GeneralSecurityException("Encrypted data has no IV");
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(encrypted, 0, IV_LENGTH));
        return cipher.doFinal(encrypted, IV_LENGTH, encrypted.length - IV_LENGTH);
    }
}
