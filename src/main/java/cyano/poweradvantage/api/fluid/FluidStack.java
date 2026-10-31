package cyano.poweradvantage.api.fluid;

import javax.annotation.Nullable;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import zone.moddev.mc.poweradvantage.init.Fluids;

/**
 * Stable Advantage millibucket value object. Native Forge fluid capabilities
 * are bridged without changing this API or its saved NBT representation.
 */
public final class FluidStack {
    private final Fluid fluid;
    public int amount;
    @Nullable public CompoundTag tag;

    public FluidStack(Fluid fluid, int amount) {
        if (fluid == null) throw new IllegalArgumentException("fluid");
        this.fluid = fluid;
        this.amount = Math.max(0, amount);
    }

    public FluidStack(FluidStack other, int amount) {
        this(other.fluid, amount);
        this.tag = other.tag == null ? null : other.tag.copy();
    }

    public Fluid getFluid() { return fluid; }
    public FluidStack copy() { return new FluidStack(this, amount); }

    public boolean isFluidEqual(@Nullable FluidStack other) {
        return other != null && fluid.isSame(other.fluid)
                && (tag == null ? other.tag == null : tag.equals(other.tag));
    }

    public CompoundTag writeToNBT(CompoundTag nbt) {
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluid);
        if (id != null) nbt.putString("FluidName", id.toString());
        nbt.putInt("Amount", amount);
        if (tag != null) nbt.put("Tag", tag.copy());
        return nbt;
    }

    @Nullable
    public static FluidStack loadFluidStackFromNBT(CompoundTag nbt) {
        if (nbt == null || !nbt.contains("FluidName", 8)) return null;
        Fluid fluid = Fluids.byName(nbt.getString("FluidName"));
        if (fluid == null) return null;
        FluidStack stack = new FluidStack(fluid, nbt.getInt("Amount"));
        if (nbt.contains("Tag", 10)) stack.tag = nbt.getCompound("Tag").copy();
        return stack;
    }
}
