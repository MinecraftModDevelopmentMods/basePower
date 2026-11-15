package zone.moddev.mc.advantageworks.unit;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.common.NeoForgeMod;

/** Verifies native ModDev test bootstrap; never manufactures a fake Forge registry. */
public final class ForgeUnitRegistries {
    private ForgeUnitRegistries() { }
    public static void registerVanillaFluidTypes() {
        if (!NeoForgeMod.WATER_TYPE.isBound()) throw new AssertionError("Native NeoForge fluid types were not registered");
    }
    public static void registerForgeEntries(String name, ResourceKey<?> key, Registry<?> registry) {
        if (name.equals("ATTRIBUTES") && !NeoForgeMod.SWIM_SPEED.isBound())
            throw new AssertionError("Native NeoForge attributes were not registered");
        if (!registry.key().equals(key)) throw new AssertionError("Incorrect native registry");
    }
}
