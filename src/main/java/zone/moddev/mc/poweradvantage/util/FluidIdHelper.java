package zone.moddev.mc.poweradvantage.util;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;

public final class FluidIdHelper {

	private FluidIdHelper() {
		throw new IllegalAccessError("Not an instantiable class");
	}

	public static int getFluidId(Fluid fluid) {
		if (fluid == null) return 0;
		ResourceLocation name = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluid);
		return name == null ? 0 : name.toString().hashCode();
	}

	public static Fluid getFluid(int id) {
		if (id == 0) return null;
		for (Fluid fluid : net.minecraftforge.registries.ForgeRegistries.FLUIDS) {
			ResourceLocation name = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluid);
			if (name != null && name.toString().hashCode() == id) return fluid;
		}
		return null;
	}
}
