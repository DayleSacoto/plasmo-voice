package su.plo.voice.platform.forge.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lombok.Getter;
import lombok.Setter;
import su.plo.voice.platform.forge.client.connection.ClientConnectionState;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket;

/** In-memory client settings. Connection lifecycle methods run on the client game thread. */
@SideOnly(Side.CLIENT)
public final class ClientState {
    @Getter
    private static final ClientState instance = new ClientState();
    @Getter @Setter
    private boolean voiceDisabled;
    @Getter @Setter
    private boolean microphoneMuted;
    @Getter
    private ClientConnectionState connection;

    public ClientConnectionState openConnection() {
        if (connection != null) connection.close();
        connection = new ClientConnectionState();
        return connection;
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    public PlayerInfoPacket createPlayerInfo(String minecraftVersion, String modVersion, byte[] publicKey) {
        return new PlayerInfoPacket(minecraftVersion, modVersion, publicKey, voiceDisabled, microphoneMuted);
    }
}
