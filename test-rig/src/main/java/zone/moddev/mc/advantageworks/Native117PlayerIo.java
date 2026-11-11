package zone.moddev.mc.advantageworks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

final class Native117PlayerIo {
    private static final Method SAVE = findSave();

    private Native117PlayerIo() {}

    static CompoundTag load(PlayerList players, ServerPlayer player) {
        CompoundTag saved = players.loadPlayerData(player.nameAndId()).orElseThrow(
                () -> new IllegalStateException("No player file loaded"));
        ValueInput input = TagValueInput.create(NativeActorIo.reporter("player " + player.getUUID()),
                player.registryAccess(), saved);
        UUID uuid = input.read("UUID", UUIDUtil.CODEC).orElseThrow();
        if (!uuid.equals(player.getUUID())) throw new IllegalStateException("Saved player UUID differs from requested player");
        var dimension = input.read("Dimension", Level.RESOURCE_KEY_CODEC).orElseThrow();
        ServerLevel world = player.level().getServer().getLevel(dimension);
        if (world == null) throw new IllegalStateException("Saved player dimension unavailable: " + dimension.identifier());
        player.setServerLevel(world);
        NativeActorIo.load(player, saved);
        if (!uuid.equals(player.getUUID()) || player.level() != world) {
            throw new IllegalStateException("Native player load changed identity or dimension");
        }
        net.minecraftforge.event.ForgeEventFactory.firePlayerLoadingEvent(player,
                players.getPlayerIo().getPlayerDataFolder(), uuid.toString());
        return saved;
    }

    static void save(PlayerList players, ServerPlayer player) {
        try {
            // Forge skips PlayerList.save for these offline probe players.
            if (player.connection == null) {
                PlayerDataStorage data = players.getPlayerIo();
                if (data == null) throw new IllegalStateException("Native PlayerData is missing");
                CompoundTag expected = NativeActorIo.saveWithoutId(player);
                data.save(player);
                // Native storage logs I/O failures instead of throwing; require the actual saved state.
                Path file = data.getPlayerDataFolder().toPath().resolve(player.getStringUUID() + ".dat");
                if (!expected.equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))) {
                    throw new IllegalStateException("Native player save did not persist the observed state");
                }
            } else {
                NativeActorIo.saveWithoutId(player);
                SAVE.invoke(players, player);
            }
        } catch (IllegalAccessException | InvocationTargetException | IOException error) {
            throw new IllegalStateException("Native player save failed", error);
        }
    }

    private static Method findSave() {
        try {
            Method method = PlayerList.class.getDeclaredMethod("save", ServerPlayer.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException officialNameAbsent) {
            return ObfuscationReflectionHelper.findMethod(PlayerList.class, "m_6765_", ServerPlayer.class);
        }
    }

}
