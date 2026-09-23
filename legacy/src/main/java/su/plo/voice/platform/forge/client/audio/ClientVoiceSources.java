package su.plo.voice.platform.forge.client.audio;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import su.plo.voice.platform.forge.audio.codec.AudioDecoder;
import su.plo.voice.platform.forge.audio.codec.OpusCodec;
import su.plo.voice.platform.forge.client.connection.ClientConfig;
import su.plo.voice.platform.forge.encryption.AesEncryption;
import su.plo.voice.proto.data.audio.source.SourceInfo;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Remote voice sources of one server configuration (upstream VoiceClientSourceManager + BaseClientAudioSource).
 * Source info arrives on the client thread, audio on the UDP worker; decoding happens on the UDP worker.
 */
@SideOnly(Side.CLIENT)
public final class ClientVoiceSources implements AutoCloseable {
    private static final long SOURCE_INFO_REQUEST_INTERVAL_MS = 1_000L;

    private final ClientConfig config;
    private final BooleanSupplier voiceDisabled;
    private final Consumer<UUID> sourceInfoRequester;
    private final PcmSink sink;
    private final Map<UUID, Source> sources = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequestById = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ClientVoiceSources(ClientConfig config, BooleanSupplier voiceDisabled,
                              Consumer<UUID> sourceInfoRequester, PcmSink sink) {
        this.config = config;
        this.voiceDisabled = voiceDisabled;
        this.sourceInfoRequester = sourceInfoRequester;
        this.sink = sink;
    }

    public void updateSourceInfo(SourceInfo info) {
        if (closed) return;
        sources.computeIfAbsent(info.getId(), id -> new Source()).info = info;
    }

    public void onAudio(SourceAudioPacket packet) {
        if (closed || voiceDisabled.getAsBoolean()) return;
        Source source = sources.get(packet.getSourceId());
        // Upstream asks the server again when the source is unknown or its state changed.
        if (source == null || source.info.getState() != packet.getSourceState()) requestSourceInfo(packet.getSourceId());
        if (source != null) source.process(packet);
    }

    public void onAudioEnd(SourceAudioEndPacket packet) {
        Source source = sources.get(packet.getSourceId());
        if (source != null) source.end();
    }

    @Override
    public void close() {
        closed = true;
        sources.values().forEach(Source::close);
        sources.clear();
    }

    private void requestSourceInfo(UUID sourceId) {
        long now = System.currentTimeMillis();
        Long last = lastRequestById.get(sourceId);
        if (last != null && now - last <= SOURCE_INFO_REQUEST_INTERVAL_MS) return;
        lastRequestById.put(sourceId, now);
        sourceInfoRequester.accept(sourceId);
    }

    public interface PcmSink {
        void accept(SourceInfo source, long sequenceNumber, short[] pcm);
    }

    private final class Source {
        private volatile SourceInfo info;
        private AudioDecoder decoder;
        private boolean decoderStereo;
        private long lastSequenceNumber = -1L;

        synchronized void process(SourceAudioPacket packet) {
            if (closed) return;
            // Upstream drops a late packet unless the sequence jumped back far (source restart).
            if (lastSequenceNumber >= 0 && packet.getSequenceNumber() <= lastSequenceNumber
                    && lastSequenceNumber - packet.getSequenceNumber() < 10L) return;
            lastSequenceNumber = packet.getSequenceNumber();

            SourceInfo current = info;
            try {
                byte[] data = packet.getData();
                AesEncryption encryption = config.getEncryption();
                if (encryption != null) data = encryption.decrypt(data);
                if (decoder == null || decoderStereo != current.isStereo()) {
                    if (decoder != null) decoder.close();
                    int sampleRate = config.getPacket().getCaptureInfo().getSampleRate();
                    decoder = OpusCodec.createDecoder(sampleRate, current.isStereo(), sampleRate / 1000 * 20);
                    decoderStereo = current.isStereo();
                }
                sink.accept(current, packet.getSequenceNumber(), decoder.decode(data));
            } catch (GeneralSecurityException | IOException ignored) {
                // A corrupted or foreign frame is dropped; the next one is independent.
            }
        }

        synchronized void end() {
            lastSequenceNumber = -1L;
            if (decoder != null) decoder.reset();
        }

        synchronized void close() {
            if (decoder != null) decoder.close();
            decoder = null;
        }
    }
}
