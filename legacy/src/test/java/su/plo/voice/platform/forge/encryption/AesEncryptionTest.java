package su.plo.voice.platform.forge.encryption;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import com.google.common.io.ByteStreams;
import org.junit.Test;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.server.connection.ServerConfig;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.udp.PacketUdp;
import su.plo.voice.proto.packets.udp.PacketUdpCodec;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import static org.junit.Assert.*;

public class AesEncryptionTest {
    private static final byte[] KEY = new byte[16];

    static {
        new Random(1).nextBytes(KEY);
    }

    @Test
    public void roundTripUsesRandomIvPrefix() throws Exception {
        AesEncryption aes = new AesEncryption(KEY);
        for (int size : new int[] {0, 1, 15, 16, 17, 960, 2000}) {
            byte[] data = bytes(size);
            byte[] encrypted = aes.encrypt(data);
            assertEquals(16 + (size / 16 + 1) * 16, encrypted.length); // IV + PKCS5-padded ciphertext
            assertArrayEquals(data, aes.decrypt(encrypted));
        }
        byte[] data = bytes(100);
        assertFalse(Arrays.equals(Arrays.copyOf(aes.encrypt(data), 16), Arrays.copyOf(aes.encrypt(data), 16)));
    }

    @Test
    public void wrongKeyNeverYieldsPlaintext() throws Exception {
        byte[] data = bytes(64);
        byte[] encrypted = new AesEncryption(KEY).encrypt(data);
        byte[] otherKey = KEY.clone();
        otherKey[0] ^= 1;
        try {
            assertFalse(Arrays.equals(data, new AesEncryption(otherKey).decrypt(encrypted)));
        } catch (GeneralSecurityException expected) {
            // Bad padding is the usual outcome.
        }
    }

    @Test
    public void corruptedDataIsRejected() throws Exception {
        AesEncryption aes = new AesEncryption(KEY);
        byte[] encrypted = aes.encrypt(bytes(64));
        assertThrows(GeneralSecurityException.class, () -> aes.decrypt(new byte[8]));
        assertThrows(GeneralSecurityException.class, () -> aes.decrypt(Arrays.copyOf(encrypted, encrypted.length - 1)));
        byte[] flipped = encrypted.clone();
        flipped[flipped.length - 1] ^= 0x55; // breaks the padding block
        try {
            assertFalse(Arrays.equals(bytes(64), aes.decrypt(flipped)));
        } catch (GeneralSecurityException expected) {
            // Bad padding is the usual outcome.
        }
    }

    /** Delivered key -> encrypted PlayerAudioPacket -> real UDP -> relay without the key -> other client decrypts. */
    @Test
    public void encryptedAudioPayloadSurvivesRealUdpCodec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair speaker = generator.generateKeyPair();
        KeyPair listener = generator.generateKeyPair();
        ServerConfig server = new ServerConfig();
        AesEncryption speakerAes = ClientConfig.decode(server.createPacket(speaker.getPublic()), speaker.getPrivate()).getEncryption();
        AesEncryption listenerAes = ClientConfig.decode(server.createPacket(listener.getPublic()), listener.getPrivate()).getEncryption();

        byte[] frame = bytes(120); // stands in for an Opus frame
        UUID secret = UUID.randomUUID();
        byte[] datagram = PacketUdpCodec.encodeThrowing(new PlayerAudioPacket(
                7L, speakerAes.encrypt(frame), VoiceActivation.PROXIMITY_ID, (short) 16, false), secret);

        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket sender = new DatagramSocket()) {
            receiver.setSoTimeout(5_000);
            sender.send(new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), receiver.getLocalPort()));
            DatagramPacket received = new DatagramPacket(new byte[65507], 65507);
            receiver.receive(received);

            PacketUdp decoded = PacketUdpCodec.decodeThrowing(
                    ByteStreams.newDataInput(Arrays.copyOf(received.getData(), received.getLength())), PacketDirection.SERVER);
            assertEquals(secret, decoded.getSecret());
            PlayerAudioPacket audio = (PlayerAudioPacket) decoded.getPacketUntyped();
            assertEquals(7L, audio.getSequenceNumber());
            assertEquals(VoiceActivation.PROXIMITY_ID, audio.getActivationId());
            assertArrayEquals(frame, listenerAes.decrypt(audio.getData()));
        }
    }

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }
}
