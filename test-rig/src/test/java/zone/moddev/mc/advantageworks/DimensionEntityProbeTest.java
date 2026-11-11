package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.BeforeClass;
import org.junit.Test;

public class DimensionEntityProbeTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void diagnosticsObserveNativeRespawnCodecWithoutInventingLegacyFields() throws Exception {
        for (boolean forced : new boolean[] {false, true}) {
            ProbePlayer player = player(forced);
            CompoundTag saved = saved(player);
            var result = DimensionEntityProbe.spawnDiagnostics(player, saved);
            assertEquals(-1, result.get("playerWorldDimension").getAsInt());
            assertEquals(0, result.get("spawnDimension").getAsInt());
            assertEquals(forced, result.get("spawnForced").getAsBoolean());
            var spawn = result.getAsJsonObject("serializedSpawn");
            assertEquals("minecraft:overworld", spawn.get("dimension").getAsString());
            assertEquals(160, spawn.getAsJsonArray("pos").get(0).getAsInt());
            assertEquals(100, spawn.getAsJsonArray("pos").get(1).getAsInt());
            assertEquals(24, spawn.getAsJsonArray("pos").get(2).getAsInt());
            assertEquals(15.0F, spawn.get("yaw").getAsFloat(), 0.0F);
            assertEquals(-5.0F, spawn.get("pitch").getAsFloat(), 0.0F);
            assertEquals(forced, spawn.has("forced"));
            assertFalse(spawn.has("SpawnX"));
            assertEquals(saved.getCompound("respawn").orElseThrow(), net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(
                            result.getAsJsonObject("serializedRespawn").get("nbtBase64").getAsString())),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
        }
    }

    @Test public void missingOrMalformedSavedRespawnCannotBecomeFalseGreen() throws Exception {
        ProbePlayer player = player(true);
        assertThrows(IllegalStateException.class, () -> DimensionEntityProbe.spawnDiagnostics(player, new CompoundTag()));
        CompoundTag malformed = saved(player);
        malformed.getCompound("respawn").orElseThrow().putString("pos", "not a block position");
        assertThrows(IllegalStateException.class, () -> DimensionEntityProbe.spawnDiagnostics(player, malformed));
    }

    @Test public void savedRespawnMustMatchTheNativeConfiguration() throws Exception {
        ProbePlayer player = player(true);
        CompoundTag mismatched = saved(player(false));
        assertThrows(IllegalStateException.class, () -> DimensionEntityProbe.spawnDiagnostics(player, mismatched));
        player.config = null;
        assertThrows(IllegalStateException.class, () -> DimensionEntityProbe.spawnDiagnostics(player, mismatched));
        var empty = DimensionEntityProbe.spawnDiagnostics(player, new CompoundTag());
        assertTrue(empty.get("spawnForced").isJsonNull());
        assertTrue(empty.get("bedLocation").isJsonNull());
        assertTrue(empty.get("serializedRespawn").isJsonNull());
    }

    private static CompoundTag saved(ProbePlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(NativeActorIo.reporter("respawn test"), player.registryAccess());
        output.store("respawn", ServerPlayer.RespawnConfig.CODEC, player.config);
        return output.buildResult();
    }

    private static ProbePlayer player(boolean forced) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        ProbePlayer player = (ProbePlayer) unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), ProbePlayer.class);
        player.world = Native117EntityContextTest.world(Level.NETHER);
        player.config = new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(160, 100, 24), 15.0F, -5.0F), forced);
        return player;
    }

    private static final class ProbePlayer extends ServerPlayer {
        ServerLevel world;
        RespawnConfig config;
        private ProbePlayer() {
            super(null, null, new GameProfile(UUID.randomUUID(), "RespawnControl"),
                    net.minecraft.server.level.ClientInformation.createDefault());
        }
        @Override public ServerLevel level() { return world; }
        @Override public RegistryAccess registryAccess() { return world.registryAccess(); }
        @Override public RespawnConfig getRespawnConfig() { return config; }
    }
}
