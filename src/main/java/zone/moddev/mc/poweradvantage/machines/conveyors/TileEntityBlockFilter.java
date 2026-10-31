package zone.moddev.mc.poweradvantage.machines.conveyors;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import zone.moddev.mc.poweradvantage.init.Entities;

public class TileEntityBlockFilter extends TileEntityConveyorFilter {
	public TileEntityBlockFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_BLOCK, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		return item.getItem() instanceof BlockItem;
	}

}
