package zone.moddev.mc.basepower.init;

import zone.moddev.mc.basepower.compat.BaseMetalsCompat;
import zone.moddev.mc.basepower.registry.FuelRegistry;
import cyano.poweradvantage.api.FluidCategoryRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.UniversalBucket;

import java.util.HashSet;
import java.util.Set;

public abstract class Fuels {

    public static final int CRUDE_OIL_FUEL_PER_FLUID_UNIT = 5;
    public static final int REFINED_OIL_FUEL_PER_FLUID_UNIT = 25;

    private static boolean initDone = false;

    public static void init() {
        if (initDone) return;

        BaseMetalsCompat.registerFuelForOre("dustCoal", (short) 1600);
        BaseMetalsCompat.registerFuelForOre("dustCarbon", (short) 1600);

        Item universalBucket = ForgeModContainer.getInstance().universalBucket;
        FuelRegistry.getInstance().registerFuel(universalBucket, Fuels::getFluidFuelValue);
        FuelRegistry.getInstance().registerPostBurnItem(universalBucket,
                stack -> new ItemStack(net.minecraft.init.Items.BUCKET));

        Set<Item> registeredContainers = new HashSet<>();
        registeredContainers.add(universalBucket);
        for (FluidContainerRegistry.FluidContainerData datum
                : FluidContainerRegistry.getRegisteredFluidContainerData()) {
            if (datum.filledContainer == null || datum.fluid == null) continue;
            if (!isSupportedFuel(datum.fluid)) continue;
            Item item = datum.filledContainer.getItem();
            if (registeredContainers.add(item)) {
                FuelRegistry.getInstance().registerFuel(item, Fuels::getFluidFuelValue);
            }
        }

        initDone = true;
    }

    private static short getFluidFuelValue(ItemStack stack) {
        FluidStack fluid = null;
        if (stack != null && stack.getItem() instanceof UniversalBucket) {
            fluid = ((UniversalBucket) stack.getItem()).getFluid(stack);
        }
        if (fluid == null && stack != null) {
            fluid = FluidContainerRegistry.getFluidForFilledItem(stack);
        }
        if (fluid == null || fluid.amount <= 0) return 0;
        if (FluidCategoryRegistry.matches(FluidCategoryRegistry.CRUDE_OIL, fluid.getFluid())) {
            return (short) (fluid.amount * CRUDE_OIL_FUEL_PER_FLUID_UNIT);
        }
        if (fluid.getFluid() == Fluids.refined_oil || "refined_oil".equals(fluid.getFluid().getName())) {
            return (short) (fluid.amount * REFINED_OIL_FUEL_PER_FLUID_UNIT);
        }
        return 0;
    }

    private static boolean isSupportedFuel(FluidStack fluid) {
        return fluid != null && (FluidCategoryRegistry.matches(
                FluidCategoryRegistry.CRUDE_OIL, fluid.getFluid())
                || fluid.getFluid() == Fluids.refined_oil
                || "refined_oil".equals(fluid.getFluid().getName()));
    }
}