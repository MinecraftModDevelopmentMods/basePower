package zone.moddev.mc.basepower.migration;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class NativeWolfTestWorld {
    private NativeWolfTestWorld() {}

    static ServerLevel create() throws ReflectiveOperationException {
        registerNativeAttributes();
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field unsafe = allocator.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        ServerLevel world = (ServerLevel) allocator.getMethod("allocateInstance", Class.class)
                .invoke(unsafe.get(null), ServerLevel.class);
        set(world, "dimension", Level.NETHER);
        set(world, "registryAccess", RegistriesHolder.ACCESS);
        var server = (net.minecraft.server.dedicated.DedicatedServer) allocator.getMethod("allocateInstance", Class.class)
                .invoke(unsafe.get(null), net.minecraft.server.dedicated.DedicatedServer.class);
        Field subscribers = net.minecraft.server.MinecraftServer.class.getDeclaredField("debugSubscribers");
        subscribers.setAccessible(true);
        subscribers.set(server, new net.minecraft.util.debug.ServerDebugSubscribers(server));
        Field serverField = ServerLevel.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(world, server);
        return world;
    }

    private static synchronized void registerNativeAttributes() throws ReflectiveOperationException {
        var wolf = net.minecraft.world.entity.EntityType.WOLF;
        if (!net.minecraftforge.common.ForgeMod.SWIM_SPEED.isPresent()
                || net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier(wolf)
                        .hasAttribute(net.minecraftforge.common.ForgeMod.SWIM_SPEED.getHolder().orElseThrow())) return;
        var eventType = net.minecraftforge.event.entity.EntityAttributeModificationEvent.class;
        var nativeHandler = net.minecraftforge.common.ForgeMod.class.getDeclaredMethod("onRegisterAttributes", eventType);
        nativeHandler.setAccessible(true);
        // Dispatch Forge's real default-attribute lifecycle outside a mod-loader launch.
        var listener = net.minecraftforge.event.entity.EntityAttributeModificationEvent.BUS.addListener(event -> {
            try {
                nativeHandler.invoke(null, event);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native Forge attribute registration failed", failure);
            }
        });
        try {
            net.minecraftforge.common.ForgeHooks.modifyAttributes();
        } finally {
            net.minecraftforge.event.entity.EntityAttributeModificationEvent.BUS.removeListener(listener);
        }
    }

    private static final class RegistriesHolder {
        private static final RegistryAccess ACCESS = createRegistries();
    }

    private static RegistryAccess createRegistries() {
        var vanilla = net.minecraft.data.registries.VanillaRegistries.createLookup();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(
                copy(vanilla, Registries.WOLF_VARIANT), copy(vanilla, Registries.WOLF_SOUND_VARIANT),
                copy(vanilla, Registries.PAINTING_VARIANT))).freeze();
    }

    private static <T> Registry<T> copy(net.minecraft.core.HolderLookup.Provider vanilla,
            net.minecraft.resources.ResourceKey<? extends Registry<T>> key) {
        var registry = new MappedRegistry<T>(key, Lifecycle.stable());
        vanilla.lookupOrThrow(key).listElements().forEach(holder ->
                registry.register(holder.key(), holder.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        return registry;
    }

    static void load(net.minecraft.world.entity.Entity entity, net.minecraft.nbt.CompoundTag saved) {
        entity.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess(), saved));
    }

    static net.minecraft.nbt.CompoundTag save(net.minecraft.world.entity.Entity entity) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    private static void set(ServerLevel world, String name, Object value) throws ReflectiveOperationException {
        Field field = Level.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(world, value);
    }
}
