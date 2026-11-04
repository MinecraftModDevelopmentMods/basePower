package zone.moddev.mc.basepower.util;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

public final class FluidHandlerHelper {

	private FluidHandlerHelper() {
	}

	public static IFluidHandler getHandler(TileEntity tileEntity, EnumFacing face) {
		if (tileEntity == null) {
			return null;
		}
		if (!tileEntity.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face)) {
			return null;
		}
		return tileEntity.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face);
	}

	public static boolean hasHandler(TileEntity tileEntity, EnumFacing face) {
		return getHandler(tileEntity, face) != null;
	}
}
