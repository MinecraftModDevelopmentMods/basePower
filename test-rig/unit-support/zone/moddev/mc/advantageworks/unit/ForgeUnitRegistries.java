package zone.moddev.mc.advantageworks.unit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.NewRegistryEvent;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

/** Runs the narrow Forge registry phases needed by native test entities outside ModLauncher. */
public final class ForgeUnitRegistries {
    private ForgeUnitRegistries() {}

    public static synchronized void registerVanillaFluidTypes() throws ReflectiveOperationException {
        if (ForgeRegistries.FLUID_TYPES.get() == null) {
            initializeRegistry(ForgeRegistries.Keys.FLUID_TYPES);
        }
        registerForgeEntries("VANILLA_FLUID_TYPES", ForgeRegistries.Keys.FLUID_TYPES, ForgeRegistries.FLUID_TYPES.get());
    }

    public static synchronized void registerIngredientSerializers() throws ReflectiveOperationException {
        if (ForgeRegistries.INGREDIENT_SERIALIZERS.get() == null) {
            initializeRegistry(ForgeRegistries.Keys.INGREDIENT_SERIALIZERS);
        }
        registerForgeEntries("INGREDIENT_SERIALIZERS", ForgeRegistries.Keys.INGREDIENT_SERIALIZERS,
                ForgeRegistries.INGREDIENT_SERIALIZERS.get());
    }

    private static void initializeRegistry(ResourceKey<?> key) throws ReflectiveOperationException {
            Field registries = ForgeRegistries.class.getDeclaredField("registries");
            registries.setAccessible(true);
            DeferredRegister<?> types = ((List<?>) registries.get(null)).stream().map(DeferredRegister.class::cast)
                    .filter(entry -> key.equals(entry.getRegistryKey()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing native registry " + key));
            NewRegistryEvent event = new NewRegistryEvent();
            dispatch(types, "createRegistry", NewRegistryEvent.class, event);
            Method fill = NewRegistryEvent.class.getDeclaredMethod("fill");
            fill.setAccessible(true);
            fill.invoke(event);
    }

    public static synchronized void registerForgeEntries(String name, ResourceKey<?> key, IForgeRegistry<?> registry)
            throws ReflectiveOperationException {
        Field field = ForgeMod.class.getDeclaredField(name);
        field.setAccessible(true);
        DeferredRegister<?> register = (DeferredRegister<?>) field.get(null);
        if (!register.getRegistryKey().equals(key) || registry == null)
            throw new IllegalArgumentException("Wrong registry for native entries " + name);
        if (register.getEntries().stream().allMatch(RegistryObject::isPresent)) return;
        Constructor<RegisterEvent> ctor = RegisterEvent.class.getDeclaredConstructor(
                ResourceKey.class, ForgeRegistry.class, Registry.class);
        ctor.setAccessible(true);
        dispatch(register, "handleEvent", RegisterEvent.class, ctor.newInstance(key, registry, null));
        if (!register.getEntries().stream().allMatch(RegistryObject::isPresent))
            throw new IllegalStateException("Native Forge entries did not bind: " + name);
    }

    private static void dispatch(DeferredRegister<?> register, String name, Class<?> eventType, Object event)
            throws ReflectiveOperationException {
        Class<?> dispatcher = Class.forName(DeferredRegister.class.getName() + "$EventDispatcher");
        Constructor<?> ctor = dispatcher.getDeclaredConstructor(DeferredRegister.class);
        ctor.setAccessible(true);
        Method handle = dispatcher.getDeclaredMethod(name, eventType);
        handle.setAccessible(true);
        handle.invoke(ctor.newInstance(register), event);
    }
}
