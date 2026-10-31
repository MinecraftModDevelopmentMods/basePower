package zone.moddev.mc.poweradvantage.util;

import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;

/** Looks up the stable Advantage fluid handler exposed by local machines. */
public final class FluidHandlerHelper {
    private FluidHandlerHelper() {}

    public static IAdvantageFluidHandler getHandler(BlockEntity tileEntity, Direction face) {
        if (tileEntity == null || tileEntity.isRemoved()) return null;
        if (tileEntity instanceof IAdvantageFluidHandler) return (IAdvantageFluidHandler) tileEntity;
        return tileEntity.getCapability(net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face).isPresent()
                ? new AdvantageFluidAdapter(tileEntity, face) : null;
    }

    public static boolean hasHandler(BlockEntity tileEntity, Direction face) {
        return getHandler(tileEntity, face) != null;
    }
}
