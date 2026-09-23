package su.plo.voice.platform.forge.server.connection;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import su.plo.voice.platform.forge.audio.codec.AudioDecoder;
import su.plo.voice.platform.forge.audio.codec.AudioEncoder;
import su.plo.voice.platform.forge.audio.codec.OpusCodec;
import su.plo.voice.platform.forge.client.audio.ClientVoiceSources;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.platform.forge.client.connection.UdpClient;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.udp.clientbound.SelfAudioInfoPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import static org.junit.Assert.*;

/** Synthetic end-to-end voice: PCM -> Opus -> AES -> UDP -> proximity routing -> decrypt -> Opus -> PCM. */
public class VoiceRoutingTest {
    private static final Logger LOGGER = LogManager.getLogger("test");
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SIZE = 960;

    @Test
    public void proximityVoiceReachesOnlyListenersInRange() throws Exception {
        ServerConfig config = new ServerConfig();
        try (UdpServer server = new UdpServer(LOGGER, "127.0.0.1", 0, "127.0.0.1", 0, 15_000)) {
            server.start();
            server.setProximityActivation(config.getProximityActivation());

            Peer speaker = new Peer(server, config, presence(false, false, 0, 0));
            Peer listener = new Peer(server, config, presence(false, false, 0, 10));
            Peer far = new Peer(server, config, presence(false, false, 0, 100));
            Peer deafened = new Peer(server, config, presence(true, false, 0, 5));
            Peer nether = new Peer(server, config, presence(false, false, -1, 5));

            List<UUID> requests = new CopyOnWriteArrayList<>();
            ClientVoiceSources sources = new ClientVoiceSources(() -> false, info -> false, sourceId -> {
                requests.add(sourceId);
                // Stands in for the server's SourceInfoPacket reply on the client thread.
                listener.sources.updateSourceInfo(sourceInfo(speaker, config));
            });
            listener.sources = sources;

            try (AudioEncoder encoder = OpusCodec.createEncoder(
                    config.getCaptureInfo().getEncoderInfo(), SAMPLE_RATE, false, 1024)) {
                for (int frame = 0; frame < 60; frame++) {
                    speaker.send(frame, speaker.config.getEncryption().encrypt(encoder.encode(tone(frame))));
                    Thread.sleep(5L);
                }
            }
            await(() -> listener.audio.size() >= 55);

            assertEquals(1, requests.size()); // throttled, answered once
            assertEquals(speaker.session.getSourceId(), requests.get(0));
            assertToneHeard(decode(listener));
            assertTrue(far.audio.isEmpty());
            assertTrue(deafened.audio.isEmpty());
            assertTrue(nether.audio.isEmpty());
            assertTrue(speaker.audio.stream().noneMatch(packet -> packet instanceof SourceAudioPacket));
            assertTrue(speaker.audio.stream().anyMatch(packet -> packet instanceof SelfAudioInfoPacket));
            assertEquals(16, speaker.session.getLastDistance());

            // A muted microphone is dropped by the server, like upstream.
            speaker.session.setPresence(presence(false, true, 0, 0));
            int before = listener.audio.size();
            speaker.send(100, speaker.config.getEncryption().encrypt(new byte[] {1, 2, 3}));
            Thread.sleep(200L);
            assertEquals(before, listener.audio.size());
        }
    }

    @Test
    public void listenerRangeFollowsUpstreamFormula() {
        UdpServer.Session speaker = new UdpServer.Session(UUID.randomUUID(), UUID.randomUUID());
        UdpServer.Session listener = new UdpServer.Session(UUID.randomUUID(), UUID.randomUUID());
        speaker.setPresence(presence(false, false, 0, 0));
        assertFalse(UdpServer.isListener(speaker, listener, (short) 16)); // not authenticated
        assertFalse(UdpServer.isListener(speaker, speaker, (short) 16));
    }

    private static final class Peer {
        final UdpServer.Session session;
        final ClientConfig config;
        final UdpClient client;
        final List<Packet<?>> audio = new CopyOnWriteArrayList<>();
        volatile ClientVoiceSources sources;

        Peer(UdpServer server, ServerConfig serverConfig, UdpServer.Presence presence) throws Exception {
            session = server.createSession(UUID.randomUUID());
            await(() -> server.connectionPacket(session) != null);
            ConnectionPacket connection = server.connectionPacket(session);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            config = ClientConfig.decode(serverConfig.createPacket(keys.getPublic()), keys.getPrivate());
            ClientConnectionState.UdpState udp = new ClientConnectionState(sent -> {}).replaceUdp();
            client = new UdpClient(LOGGER, connection.getSecret(), connection.getIp(), connection.getPort(), udp, packet -> {
                audio.add(packet);
                ClientVoiceSources current = sources;
                if (current != null && packet instanceof SourceAudioPacket) current.onAudio((SourceAudioPacket) packet);
            });
            client.start();
            await(session::isAuthenticated);
            await(udp::isConfirmed);
            session.setPresence(presence);
        }

        void send(long sequenceNumber, byte[] data) {
            client.send(new PlayerAudioPacket(sequenceNumber, data, VoiceActivation.PROXIMITY_ID, (short) 16, false));
        }
    }

    private static PlayerSourceInfo sourceInfo(Peer speaker, ServerConfig config) {
        return new PlayerSourceInfo("plasmovoice", speaker.session.getSourceId(), config.getProximityLine().getId(),
                null, speaker.session.getSourceState(), new OpusDecoderInfo(), false, true, 0,
                new VoicePlayerInfo(speaker.session.getPlayerId(), "speaker", false, false, false));
    }

    private static UdpServer.Presence presence(boolean voiceDisabled, boolean microphoneMuted, int dimension, double x) {
        return new UdpServer.Presence(true, voiceDisabled, microphoneMuted, dimension, x, 64, 0);
    }

    /** Decrypts and decodes the routed frames in sequence order, as the playback thread does. */
    private static List<short[]> decode(Peer listener) throws Exception {
        List<short[]> heard = new ArrayList<>();
        try (AudioDecoder decoder = OpusCodec.createDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            listener.audio.stream()
                    .filter(packet -> packet instanceof SourceAudioPacket)
                    .map(packet -> (SourceAudioPacket) packet)
                    .sorted(Comparator.comparingLong(SourceAudioPacket::getSequenceNumber))
                    .forEach(packet -> {
                        try {
                            heard.add(decoder.decode(listener.config.getEncryption().decrypt(packet.getData())));
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    });
        }
        return heard.subList(heard.size() - 30, heard.size());
    }

    private static void assertToneHeard(List<short[]> frames) {
        int crossings = 0;
        for (short[] pcm : frames) {
            assertEquals(FRAME_SIZE, pcm.length);
            for (int i = 1; i < pcm.length; i++) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++;
        }
        assertEquals("zero crossings/s for 440 Hz", 880.0, crossings / (frames.size() * 0.02), 60.0);
    }

    private static short[] tone(int frame) {
        short[] pcm = new short[FRAME_SIZE];
        for (int i = 0; i < FRAME_SIZE; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * (frame * FRAME_SIZE + i) / (double) SAMPLE_RATE) * 8000);
        }
        return pcm;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not reached in time", System.currentTimeMillis() < deadline);
            Thread.sleep(10L);
        }
    }
}
