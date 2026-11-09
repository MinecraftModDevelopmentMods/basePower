package zone.moddev.mc.advantageworks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native117EngineSignaturesTest {
    @Test public void nativeChunkRegistryAndTileSignaturesAreExact() throws Exception {
        assertEquals(LevelChunk.class, ServerChunkCache.class.getMethod("getChunkNow", int.class, int.class).getReturnType());
        assertEquals(ServerLevel.class, MinecraftServer.class.getMethod("getLevel", ResourceKey.class).getReturnType());
        assertEquals(void.class, BlockEntity.class.getMethod("load", CompoundTag.class).getReturnType());
        assertEquals(CompoundTag.class, BlockEntity.class.getMethod("save", CompoundTag.class).getReturnType());
    }

    @Test public void nativePlayerSaveRemainsProtectedAndRespawnUsesRegistryKeys() throws Exception {
        Method save = PlayerList.class.getDeclaredMethod("save", ServerPlayer.class);
        assertTrue(Modifier.isProtected(save.getModifiers()));
        assertEquals(ResourceKey.class, ServerPlayer.class.getMethod("getRespawnDimension").getReturnType());
        assertEquals(void.class, ServerPlayer.class.getMethod("setRespawnPosition", ResourceKey.class,
                net.minecraft.core.BlockPos.class, float.class, boolean.class, boolean.class).getReturnType());
    }

    @Test public void realNativeBitStoragePadsFiveSevenAndThirteenBitValues() throws Exception {
        Class<?> storage = Class.forName("net.minecraft.util.BitStorage");
        for (int bits : new int[] {5, 7, 13}) {
            Object instance = storage.getConstructor(int.class, int.class).newInstance(bits, 4096);
            int perLong = 64 / bits;
            storage.getMethod("set", int.class, int.class).invoke(instance, perLong - 1, 3);
            storage.getMethod("set", int.class, int.class).invoke(instance, perLong, 17);
            long[] raw = (long[]) storage.getMethod("getRaw").invoke(instance);
            assertEquals((4096 + perLong - 1) / perLong, raw.length);
            assertEquals(3L << ((perLong - 1) * bits), raw[0]);
            assertEquals(17L, raw[1]);
            assertEquals(17, storage.getMethod("get", int.class).invoke(instance, perLong));
        }
    }
}
