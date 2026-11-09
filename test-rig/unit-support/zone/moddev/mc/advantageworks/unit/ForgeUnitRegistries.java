package zone.moddev.mc.advantageworks.unit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.NewRegistryEvent;
import net.minecraftforge.registries.RegisterEvent;

/** Runs the narrow Forge registry phases needed by native test entities outside ModLauncher. */
public final class ForgeUnitRegistries {
    private ForgeUnitRegistries() {}

    public static synchronized void registerVanillaFluidTypes() throws ReflectiveOperationException {
        if (ForgeRegistries.FLUID_TYPES.get() != null) return;
        NewRegistryEvent event = new NewRegistryEvent();
        Field types = ForgeRegistries.class.getDeclaredField("DEFERRED_FLUID_TYPES");
        types.setAccessible(true);
        Method create = DeferredRegister.class.getDeclaredMethod("createRegistry", NewRegistryEvent.class);
        create.setAccessible(true);
        create.invoke(types.get(null), event);
        Method fill = NewRegistryEvent.class.getDeclaredMethod("fill");
        fill.setAccessible(true);
        fill.invoke(event);
        registerForgeEntries("VANILLA_FLUID_TYPES", ForgeRegistries.Keys.FLUID_TYPES, ForgeRegistries.FLUID_TYPES.get());
    }

    public static void registerForgeEntries(String name, ResourceKey<?> key, IForgeRegistry<?> registry)
            throws ReflectiveOperationException {
        Field field = ForgeMod.class.getDeclaredField(name);
        field.setAccessible(true);
        Method register = DeferredRegister.class.getDeclaredMethod("addEntries", RegisterEvent.class);
        register.setAccessible(true);
        Constructor<RegisterEvent> ctor = RegisterEvent.class.getDeclaredConstructor(
                ResourceKey.class, ForgeRegistry.class, Registry.class);
        ctor.setAccessible(true);
        register.invoke(field.get(null), ctor.newInstance(key, registry, null));
    }
}
