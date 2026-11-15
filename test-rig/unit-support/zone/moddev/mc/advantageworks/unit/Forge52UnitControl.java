package zone.moddev.mc.advantageworks.unit;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Native NeoForge bootstrap control retained alongside the historical test helpers. */
public final class Forge52UnitControl {
    public static void main(String[] args) {
        if (new ItemStack(Items.DIRT, 16).get(DataComponents.MAX_STACK_SIZE) != 64)
            throw new AssertionError("Native item components unavailable");
        ForgeUnitRegistries.registerVanillaFluidTypes();
        if (Fluids.WATER.getFluidType() != NeoForgeMod.WATER_TYPE.value() || !NeoForgeMod.SWIM_SPEED.isBound())
            throw new AssertionError("Native fluid and attribute registration missing");
        if (Capabilities.FluidHandler.BLOCK == null || Capabilities.ItemHandler.BLOCK == null)
            throw new AssertionError("Native capability tokens unavailable");
        System.out.println("NeoForge 1.21.1 native JUnit bootstrap control PASS");
    }
}
