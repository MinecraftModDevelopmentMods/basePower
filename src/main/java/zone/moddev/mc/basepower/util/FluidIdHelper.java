package zone.moddev.mc.basepower.util;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import java.util.Map;

public final class FluidIdHelper {

	private FluidIdHelper() {
		throw new IllegalAccessError("Not an instantiable class");
	}

	public static int getFluidId(Fluid fluid) {
		if (fluid == null) return 0;
		String name = FluidRegistry.getFluidName(fluid);
		return name == null ? 0 : name.hashCode();
	}

	public static Fluid getFluid(int id) {
		if (id == 0) return null;
		for (Map.Entry<String, Fluid> entry : FluidRegistry.getRegisteredFluids().entrySet()) {
			if (entry.getKey().hashCode() == id) {
				return entry.getValue();
			}
		}
		return null;
	}
}
