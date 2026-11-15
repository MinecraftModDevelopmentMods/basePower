package zone.moddev.mc.basepower.util;

import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTankInfo;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Exposes the stable Advantage contract through Forge's native fluid capability. */
public final class ForgeFluidAdapter implements IFluidHandler {
    private final IAdvantageFluidHandler handler;
    private final Direction face;
    private final Runnable changed;

    public ForgeFluidAdapter(IAdvantageFluidHandler handler, Direction face, Runnable changed) {
        this.handler = handler;
        this.face = face;
        this.changed = changed;
    }

    public static net.neoforged.neoforge.fluids.FluidStack toForge(FluidStack stack) {
        if (stack == null || stack.amount <= 0) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        var result = new net.neoforged.neoforge.fluids.FluidStack(stack.getFluid(), stack.amount);
        if (stack.tag != null) {
            var custom = stack.tag.copy();
            if (custom.contains("basepower:fluid_components", 10)) {
                var encoded = new net.minecraft.nbt.CompoundTag();
                encoded.putString("id", net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());
                encoded.putInt("amount", stack.amount);
                encoded.put("components", custom.getCompound("basepower:fluid_components").copy());
                result = net.neoforged.neoforge.fluids.FluidStack.parseOptional(registries(), encoded);
                if (result.isEmpty()) throw new IllegalStateException("Invalid saved fluid components");
                custom.remove("basepower:fluid_components");
            }
            if (!custom.isEmpty())
            result.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(custom));
        }
        return result;
    }

    public static FluidStack fromForge(net.neoforged.neoforge.fluids.FluidStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        FluidStack copy = new FluidStack(stack.getFluid(), stack.getAmount());
        copy.tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if (!stack.isComponentsPatchEmpty()) {
            var components = ((net.minecraft.nbt.CompoundTag) stack.save(registries())).getCompound("components").copy();
            components.remove("minecraft:custom_data");
            if (!components.isEmpty()) copy.tag.put("basepower:fluid_components", components);
        }
        if (copy.tag.isEmpty()) copy.tag = null;
        return copy;
    }

    private static net.minecraft.core.HolderLookup.Provider registries() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.registryAccess()
                : net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    private FluidTankInfo[] tanks() {
        FluidTankInfo[] tanks = handler.getTankInfo(face);
        return tanks == null ? new FluidTankInfo[0] : tanks;
    }

    @Override public int getTanks() { return tanks().length; }
    @Override public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) {
        FluidTankInfo[] tanks = tanks();
        return tank < 0 || tank >= tanks.length || tanks[tank] == null
                ? net.neoforged.neoforge.fluids.FluidStack.EMPTY : toForge(tanks[tank].fluid);
    }
    @Override public int getTankCapacity(int tank) {
        FluidTankInfo[] tanks = tanks();
        return tank < 0 || tank >= tanks.length || tanks[tank] == null ? 0 : Math.max(0, tanks[tank].capacity);
    }
    @Override public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack resource) {
        return resource != null && !resource.isEmpty() && getTankCapacity(tank) > 0
                && handler.canFill(face, resource.getFluid());
    }
    @Override public int fill(net.neoforged.neoforge.fluids.FluidStack resource, FluidAction action) {
        FluidStack copy = fromForge(resource);
        if (copy == null || !handler.canFill(face, copy.getFluid())) return 0;
        int accepted = Math.max(0, Math.min(resource.getAmount(), handler.fill(face, copy, action.execute())));
        if (accepted > 0 && action.execute()) changed.run();
        return accepted;
    }
    @Override public net.neoforged.neoforge.fluids.FluidStack drain(net.neoforged.neoforge.fluids.FluidStack resource, FluidAction action) {
        FluidStack copy = fromForge(resource);
        if (copy == null || !handler.canDrain(face, copy.getFluid())) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        FluidStack result = handler.drain(face, copy, action.execute());
        if (result == null || !copy.isFluidEqual(result)) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        return drained(result, resource.getAmount(), action);
    }
    @Override public net.neoforged.neoforge.fluids.FluidStack drain(int amount, FluidAction action) {
        if (amount <= 0) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        FluidStack available = handler.drain(face, amount, false);
        if (available == null || !handler.canDrain(face, available.getFluid())) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        return drained(handler.drain(face, new FluidStack(available, Math.min(amount, available.amount)), action.execute()), amount, action);
    }
    private net.neoforged.neoforge.fluids.FluidStack drained(FluidStack result, int limit, FluidAction action) {
        if (result == null || result.amount <= 0) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        net.neoforged.neoforge.fluids.FluidStack copy = toForge(new FluidStack(result, Math.min(limit, result.amount)));
        if (action.execute()) changed.run();
        return copy;
    }
}
