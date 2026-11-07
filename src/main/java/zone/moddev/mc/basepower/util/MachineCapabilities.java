package zone.moddev.mc.basepower.util;

import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

/** Lazily built sided adapters, discarded when a machine is removed or unloaded. */
public final class MachineCapabilities {
    private final BlockEntity owner;
    private final Map<Direction, LazyOptional<?>> fluids = new HashMap<>();
    private final Map<Direction, LazyOptional<?>> items = new HashMap<>();
    private boolean valid = true;

    public MachineCapabilities(BlockEntity owner) { this.owner = owner; }

    public <T> LazyOptional<T> get(Capability<T> capability, Direction face) {
        if (!valid || owner.isRemoved()) return LazyOptional.empty();
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY && owner instanceof IAdvantageFluidHandler) {
            return fluids.computeIfAbsent(face, side -> LazyOptional.of(() ->
                    new ForgeFluidAdapter((IAdvantageFluidHandler) owner, side, owner::setChanged))).cast();
        }
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && owner instanceof Container) {
            return items.computeIfAbsent(face, side -> LazyOptional.of(() -> side != null && owner instanceof WorldlyContainer
                    ? new SidedInvWrapper((WorldlyContainer) owner, side) : new InvWrapper((Container) owner))).cast();
        }
        return LazyOptional.empty();
    }

    public void invalidate() {
        valid = false;
        fluids.values().forEach(LazyOptional::invalidate);
        items.values().forEach(LazyOptional::invalidate);
        fluids.clear();
        items.clear();
    }

    public boolean isValid() { return valid; }

    public void revive() { valid = true; }
}
