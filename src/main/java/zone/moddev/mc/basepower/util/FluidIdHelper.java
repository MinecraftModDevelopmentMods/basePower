package zone.moddev.mc.basepower.util;

import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;

public final class FluidIdHelper {

	private FluidIdHelper() {
		throw new IllegalAccessError("Not an instantiable class");
	}

	public static int getFluidId(Fluid fluid) {
		if (fluid == null) return 0;
		ResourceLocation name = BuiltInRegistries.FLUID.getKey(fluid);
		return name == null ? 0 : name.toString().hashCode();
	}

	public static Fluid getFluid(int id) {
		if (id == 0) return null;
		for (Fluid fluid : BuiltInRegistries.FLUID) {
			ResourceLocation name = BuiltInRegistries.FLUID.getKey(fluid);
			if (name != null && name.toString().hashCode() == id) return fluid;
		}
		return null;
	}
}
