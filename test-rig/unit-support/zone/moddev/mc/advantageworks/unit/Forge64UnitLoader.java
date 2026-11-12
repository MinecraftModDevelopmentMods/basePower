package zone.moddev.mc.advantageworks.unit;

import java.util.List;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;

/** Native empty discovery for plain JUnit only, never a substitute for a Forge game launch. */
public final class Forge64UnitLoader {
    private static boolean initialized;

    private Forge64UnitLoader() {}

    public static synchronized void initialize() {
        if (initialized) return;
        if (FMLLoader.getGamePath() != null)
            throw new IllegalStateException("JUnit discovery must not run inside a Forge launch");
        initializeEmptyDiscovery();
        if (!LoadingModList.getMods().isEmpty() || !LoadingModList.getErrors().isEmpty()
                || !LoadingModList.getBrokenFiles().isEmpty())
            throw new IllegalStateException("Native JUnit discovery is not an empty successful result");
        initializeEmptyDispatch();
        if (!ModLoader.isLoadingStateValid() || ModList.size() != 0
                || !ModList.getMods().isEmpty() || !ModList.getModFiles().isEmpty())
            throw new IllegalStateException("Native JUnit loader did not initialize an empty mod list");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        initialized = true;
    }

    private static void initializeEmptyDispatch() {
        try {
            if (ModLoader.isLoadingStateValid() || FMLLoader.backgroundScanHandler != null)
                throw new IllegalStateException("JUnit discovery found an active mod loader");
            var setLoaded = ModList.class.getDeclaredMethod("setLoadedMods", List.class);
            setLoaded.setAccessible(true);
            setLoaded.invoke(null, List.of());
            // Preserve the old worldless fixture's empty event-dispatch readiness. Running
            // gatherAndInitializeMods here would falsely require a ModLauncher game layer.
            var valid = ModLoader.class.getDeclaredField("loadingStateValid");
            valid.setAccessible(true);
            valid.setBoolean(null, true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot initialize empty JUnit event dispatch", failure);
        }
    }

    private static void initializeEmptyDiscovery() {
        try {
            // Only worldless JUnit bypasses system-mod sorting. ModList still reads Forge's
            // own discovery record; no production mods or successful game launch are claimed.
            Class<?> stateType = Class.forName("net.minecraftforge.fml.loading.ModSorter$State");
            var constructor = stateType.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            Object state = constructor.newInstance(List.of(), List.of());
            Class<?> discovery = Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl");
            var temporaryState = discovery.getDeclaredField("temp");
            temporaryState.setAccessible(true);
            if (temporaryState.get(null) != null)
                throw new IllegalStateException("Native mod discovery is already initializing");
            temporaryState.set(null, state);
            try {
                ModList.init();
            } finally {
                temporaryState.set(null, null);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot initialize native empty JUnit discovery", failure);
        }
    }
}
