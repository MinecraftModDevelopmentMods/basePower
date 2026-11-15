package zone.moddev.mc.advantageworks;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

final class WorksCapabilities {
    private WorksCapabilities() { }

    static IFluidHandler fluid(BlockEntity tile, Direction face) {
        if (tile == null || tile.getLevel() == null) throw new AssertionError("Missing live capability owner");
        IFluidHandler handler = tile.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, tile.getBlockPos(), face);
        if (handler == null) throw new AssertionError("Missing real fluid capability at " + tile.getBlockPos());
        return handler;
    }
}
