package zone.moddev.mc.advantageworks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

final class Native117PlayerIo {
    private static final Method SAVE = findSave();
    private static final Field PLAYER_DATA = findPlayerData();

    private Native117PlayerIo() {}

    static void save(PlayerList players, ServerPlayer player) {
        try {
            // Forge skips PlayerList.save for these offline probe players.
            if (player.connection == null) {
                PlayerDataStorage data = (PlayerDataStorage) PLAYER_DATA.get(players);
                if (data == null) throw new IllegalStateException("Native PlayerData is missing");
                data.save(player);
            } else {
                SAVE.invoke(players, player);
            }
        } catch (IllegalAccessException | InvocationTargetException error) {
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

    private static Field findPlayerData() {
        try {
            Field field = PlayerList.class.getDeclaredField("playerIo");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException officialNameAbsent) {
            return ObfuscationReflectionHelper.findField(PlayerList.class, "f_11204_");
        }
    }
}
