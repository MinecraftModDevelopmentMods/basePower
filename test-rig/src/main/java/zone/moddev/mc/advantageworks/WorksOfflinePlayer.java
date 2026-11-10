package zone.moddev.mc.advantageworks;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Forge 50 no longer ships FakePlayer. These actors never join the player list. */
class WorksOfflinePlayer extends ServerPlayer {
    private static final GameProfile SENTINEL = new GameProfile(
            UUID.fromString("ec003652-141d-4c8d-b575-25381335af83"), "AdvWorksSentinel");

    WorksOfflinePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        connection = new ServerGamePacketListenerImpl(level.getServer(),
                new Connection(PacketFlow.SERVERBOUND), this, cookie(this)) {
            @Override public void send(Packet<?> packet) {}
        };
    }

    static CommonListenerCookie cookie(ServerPlayer player) {
        return CommonListenerCookie.createInitial(player.getGameProfile(), false);
    }

    static WorksOfflinePlayer sentinel(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (player instanceof WorksOfflinePlayer actor && actor.getUUID().equals(SENTINEL.getId())) return actor;
        }
        return new WorksOfflinePlayer(level, SENTINEL);
    }

    @Override public void tick() {}
    @Override public void doTick() {}
}
