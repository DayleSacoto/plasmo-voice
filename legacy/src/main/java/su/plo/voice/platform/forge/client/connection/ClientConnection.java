package su.plo.voice.platform.forge.client.connection;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Objects;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import net.minecraft.network.NetworkManager;

import su.plo.voice.platform.forge.PlasmoVoiceMod;
import su.plo.voice.platform.forge.network.VoiceChannel;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;

@SideOnly(Side.CLIENT)
public final class ClientConnection {

    private final VoiceChannel channel;
    @Getter
    private final NetworkManager connection;

    private KeyPair keyPair;

    public ClientConnection(VoiceChannel channel, NetworkManager connection) {
        this.channel = Objects.requireNonNull(channel);
        this.connection = Objects.requireNonNull(connection);
    }

    public KeyPair getKeyPair() {
        if (keyPair == null) {
            throw new IllegalStateException("KeyPair is not initialized");
        }

        return keyPair;
    }

    public void generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        keyPair = generator.generateKeyPair();
    }

    public void handle(Packet<?> packet) {
        if (packet instanceof PlayerInfoRequestPacket) {
            handle((PlayerInfoRequestPacket) packet);
        }
    }

    private void handle(PlayerInfoRequestPacket packet) {
        channel.sendToServer(new PlayerInfoPacket(
                "1.7.10",
                PlasmoVoiceMod.VERSION,
                getKeyPair().getPublic().getEncoded(),
                false,
                false
        ));
    }
}