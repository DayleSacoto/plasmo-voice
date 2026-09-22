package su.plo.voice.platform.forge.network;

import java.util.Arrays;
import java.util.UUID;

import com.google.common.io.ByteStreams;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import org.junit.Test;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.PacketDirection;
import su.plo.voice.proto.packets.tcp.PacketTcpCodec;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.LanguageRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

public final class VoiceChannelTest {
    @Test
    public void preservesUpstreamBytesAndMinecraftEnvelopes() throws Exception {
        check("plasmo:voice/v2".equals(VoiceChannel.NAME), "Upstream channel changed");
        roundTrip(new PlayerStatePacket(true, false), PacketDirection.SERVER);
        roundTrip(new PlayerInfoRequestPacket(), PacketDirection.CLIENT);
        roundTrip(new ConnectionPacket(UUID.fromString("12345678-1234-5678-1234-567812345678"),
                "127.0.0.1", 24454), PacketDirection.CLIENT);
        minecraftEnvelope(new PlayerStatePacket(false, true), PacketDirection.SERVER);
        minecraftEnvelope(new PlayerInfoRequestPacket(), PacketDirection.CLIENT);
    }

    @Test
    public void checksDirectionAndPreservesIncomingBuffer() throws Exception {
        FMLProxyPacket state = VoiceChannel.createPayload(new PlayerStatePacket(true, false),
                PacketDirection.SERVER);
        try {
            check(Arrays.equals(new byte[] {11, 1, 0}, bytes(state.payload())), "Extra framing detected");
            check(!VoiceChannel.readPayload(state, PacketDirection.CLIENT).isPresent(),
                    "Wrong direction accepted");
        } finally {
            state.payload().release();
        }

        ByteBuf offset = Unpooled.wrappedBuffer(new byte[] {99, 11, 1, 0});
        offset.readerIndex(1);
        try {
            PlayerStatePacket decoded = (PlayerStatePacket) VoiceChannel.readPayload(
                    new FMLProxyPacket(offset, VoiceChannel.NAME), PacketDirection.SERVER).get();
            check(decoded.isVoiceDisabled() && !decoded.isMicrophoneMuted(), "State changed");
            check(offset.readerIndex() == 1, "Incoming buffer consumed");
        } finally {
            offset.release();
        }
    }

    @Test
    public void rejectsMalformedAndUnknownPackets() throws Exception {
        rejects(new byte[0]);
        rejects(new byte[] {11, 1});
        rejects(new byte[32767]);
        ByteBuf unknown = Unpooled.wrappedBuffer(new byte[] {127});
        try {
            check(!VoiceChannel.readPayload(new FMLProxyPacket(unknown, VoiceChannel.NAME),
                    PacketDirection.SERVER).isPresent(), "Unknown ID accepted");
        } finally {
            unknown.release();
        }
    }

    @Test
    public void enforcesServerboundSizeLimit() {
        FMLProxyPacket largest = VoiceChannel.createPayload(
                new LanguageRequestPacket("x".repeat(32763)), PacketDirection.SERVER);
        check(largest.payload().readableBytes() == 32766, "C17 boundary changed");
        largest.payload().release();
        try {
            VoiceChannel.createPayload(new LanguageRequestPacket("x".repeat(32764)),
                    PacketDirection.SERVER);
            throw new AssertionError("Oversized C17 payload accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void minecraftEnvelope(Packet<?> packet, PacketDirection direction) throws Exception {
        FMLProxyPacket payload = VoiceChannel.createPayload(packet, direction);
        PacketBuffer wire = new PacketBuffer(Unpooled.buffer());
        try {
            byte[] received;
            String name;
            if (direction == PacketDirection.SERVER) {
                payload.toC17Packet().writePacketData(wire);
                C17PacketCustomPayload incoming = new C17PacketCustomPayload();
                incoming.readPacketData(wire);
                name = incoming.func_149559_c();
                received = incoming.func_149558_e();
            } else {
                payload.toS3FPacket().writePacketData(wire);
                S3FPacketCustomPayload incoming = new S3FPacketCustomPayload();
                incoming.readPacketData(wire);
                name = incoming.func_149169_c();
                received = incoming.func_149168_d();
            }
            check(VoiceChannel.NAME.equals(name), "Minecraft changed the channel");
            check(Arrays.equals(PacketTcpCodec.encode(packet), received), "Minecraft changed the payload");
            check(wire.readableBytes() == 0, "Unexpected envelope bytes");
        } finally {
            payload.payload().release();
            wire.release();
        }
    }

    private static void roundTrip(Packet<?> packet, PacketDirection direction) throws Exception {
        FMLProxyPacket payload = VoiceChannel.createPayload(packet, direction);
        try {
            byte[] expected = PacketTcpCodec.encode(packet);
            check(Arrays.equals(expected, bytes(payload.payload())), "Transport changed codec bytes");
            Packet<?> decoded = VoiceChannel.readPayload(payload, direction).get();
            check(packet.getClass() == decoded.getClass(), "Packet type changed");
            check(Arrays.equals(expected, PacketTcpCodec.encode(decoded)), "Packet fields changed");
            check(PacketTcpCodec.decode(ByteStreams.newDataInput(bytes(payload.payload())), direction).isPresent(),
                    "Upstream codec rejected payload");
        } finally {
            payload.payload().release();
        }
    }

    private static void rejects(byte[] data) throws Exception {
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            VoiceChannel.readPayload(new FMLProxyPacket(buffer, VoiceChannel.NAME), PacketDirection.SERVER);
            throw new AssertionError("Malformed payload accepted");
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) {
        } finally {
            buffer.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
        return data;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
