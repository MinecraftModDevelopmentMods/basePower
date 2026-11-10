package zone.moddev.mc.basepower.migration;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.animal.WolfVariant;
import net.minecraft.world.entity.animal.WolfVariants;
import net.minecraft.world.level.Level;

final class NativeWolfTestWorld {
    private NativeWolfTestWorld() {}

    static ServerLevel create() throws ReflectiveOperationException {
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field unsafe = allocator.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        ServerLevel world = (ServerLevel) allocator.getMethod("allocateInstance", Class.class)
                .invoke(unsafe.get(null), ServerLevel.class);
        set(world, "dimension", Level.NETHER);
        set(world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        // The native wolf constructor now resolves its default variant in the world's registry.
        var variants = new MappedRegistry<>(Registries.WOLF_VARIANT, Lifecycle.stable());
        Registry.register(variants, WolfVariants.PALE, new WolfVariant(
                ResourceLocation.parse("minecraft:entity/wolf/wolf"),
                ResourceLocation.parse("minecraft:entity/wolf/wolf_tame"),
                ResourceLocation.parse("minecraft:entity/wolf/wolf_angry"), HolderSet.direct()));
        set(world, "registryAccess", new RegistryAccess.ImmutableRegistryAccess(List.of(variants)).freeze());
        return world;
    }

    private static void set(ServerLevel world, String name, Object value) throws ReflectiveOperationException {
        Field field = Level.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(world, value);
    }
}
