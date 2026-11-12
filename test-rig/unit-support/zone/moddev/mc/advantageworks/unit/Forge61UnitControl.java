package zone.moddev.mc.advantageworks.unit;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

/** Proves the test-only fixture reaches Forge's native item, registry, and capability paths. */
public final class Forge61UnitControl {
    public static void main(String[] args) throws Exception {
        net.minecraftforge.fml.ModList.init();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (new ItemStack(Items.DIRT, 16).get(DataComponents.MAX_STACK_SIZE) != 64)
            throw new AssertionError("Native item component gathering did not complete");
        if (!net.minecraftforge.fml.ModLoader.isLoadingStateValid())
            throw new AssertionError("The native unit loader is invalid");
        if (net.minecraftforge.fml.ModList.size() != 0)
            throw new AssertionError("The native unit loader contains mods: "
                    + net.minecraftforge.fml.ModList.size());
        for (int run = 0; run < 2; run++) {
            ForgeUnitRegistries.registerVanillaFluidTypes();
            ForgeUnitRegistries.registerForgeEntries("ATTRIBUTES", ForgeRegistries.Keys.ATTRIBUTES, ForgeRegistries.ATTRIBUTES);
            if (Fluids.WATER.getFluidType() != ForgeMod.WATER_TYPE.get()
                    || !ForgeMod.SWIM_SPEED.isPresent()) {
                throw new AssertionError("Native Forge registry objects did not bind");
            }
        }
        if (ForgeCapabilities.FLUID_HANDLER == null || ForgeCapabilities.ITEM_HANDLER == null
                || (Object) ForgeCapabilities.FLUID_HANDLER == ForgeCapabilities.ITEM_HANDLER) {
            throw new AssertionError("Native Forge capability tokens were not transformed");
        }
        System.out.println("Forge 64 plain-JUnit bootstrap control PASS");
    }
}
