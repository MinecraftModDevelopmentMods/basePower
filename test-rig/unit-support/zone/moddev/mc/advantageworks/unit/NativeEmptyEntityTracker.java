package zone.moddev.mc.advantageworks.unit;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

/** Empty native entity-ID lookup for an unticked, worldless test context. */
public final class NativeEmptyEntityTracker {
    private NativeEmptyEntityTracker() { }

    public static void install(ServerLevel world) throws ReflectiveOperationException {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        Field instance = type.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        Object allocator = instance.get(null);
        var allocate = type.getMethod("allocateInstance", Class.class);
        var cache = (ServerChunkCache) allocate.invoke(allocator, ServerChunkCache.class);
        var chunks = (ChunkMap) allocate.invoke(allocator, ChunkMap.class);
        var entities = new Int2ObjectOpenHashMap<>();
        set(chunks, "entityMap", entities);
        set(cache, "chunkMap", chunks);
        set(world, "chunkSource", cache);
        int first = world.getNextEntityId(), second = world.getNextEntityId();
        if (first == 0 || second == 0 || first == second || !entities.isEmpty()
                || cache.hasEntityWithId(first) || cache.hasEntityWithId(second)) {
            throw new AssertionError("Empty native entity-ID lookup did not stay empty");
        }
    }

    private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
