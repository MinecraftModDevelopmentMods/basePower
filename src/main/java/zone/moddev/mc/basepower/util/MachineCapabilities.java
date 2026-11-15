package zone.moddev.mc.basepower.util;

import net.minecraft.core.registries.BuiltInRegistries;

import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;

/** Sided adapters registered through NeoForge; no Forge LazyOptional lifetime remains. */
public final class MachineCapabilities {
    private final BlockEntity owner;
    private final Map<Direction, IFluidHandler> fluids = new HashMap<>();
    private final Map<Direction, IItemHandler> items = new HashMap<>();
    private boolean valid = true;

    public MachineCapabilities(BlockEntity owner) { this.owner = owner; }

    public IFluidHandler fluids(Direction face) {
        if (!valid || owner.isRemoved() || !(owner instanceof IAdvantageFluidHandler handler)) return null;
        return fluids.computeIfAbsent(face, side -> new ForgeFluidAdapter(handler, side, owner::setChanged));
    }

    public IItemHandler items(Direction face) {
        if (!valid || owner.isRemoved() || !(owner instanceof Container inventory)) return null;
        return items.computeIfAbsent(face, side -> side != null && inventory instanceof WorldlyContainer sided
                ? new SidedInvWrapper(sided, side) : new InvWrapper(inventory));
    }

    public void invalidate() {
        valid = false;
        fluids.clear();
        items.clear();
        if (owner.getLevel() != null) owner.getLevel().invalidateCapabilities(owner.getBlockPos());
    }

    public boolean isValid() { return valid; }
    public void revive() {
        if (valid) return;
        valid = true;
        if (owner.getLevel() != null) owner.getLevel().invalidateCapabilities(owner.getBlockPos());
    }

    private static MachineCapabilities adapters(BlockEntity tile) {
        if (tile instanceof PoweredEntity powered) return powered.machineCapabilities();
        if (tile instanceof TileEntityConveyor conveyor) return conveyor.machineCapabilities();
        return null;
    }

    public static void register(RegisterCapabilitiesEvent event) {
        Set<String> owners = Set.of("basepower", "basesteam", "baseelectric");
        Block[] blocks = BuiltInRegistries.BLOCK.entrySet().stream()
                .filter(entry -> owners.contains(entry.getKey().location().getNamespace()))
                .map(Map.Entry::getValue).toArray(Block[]::new);
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, tile, side) -> {
            MachineCapabilities caps = adapters(tile);
            return caps == null ? null : caps.fluids(side);
        }, blocks);
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, tile, side) -> {
            MachineCapabilities caps = adapters(tile);
            return caps == null ? null : caps.items(side);
        }, blocks);
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, tile, side) ->
                tile != null && !tile.isRemoved() && tile instanceof net.neoforged.neoforge.energy.IEnergyStorage energy
                        ? energy : null, blocks);
    }
}
