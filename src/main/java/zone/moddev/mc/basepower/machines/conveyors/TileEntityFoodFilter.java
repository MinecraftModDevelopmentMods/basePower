package zone.moddev.mc.basepower.machines.conveyors;

import net.minecraft.world.item.ItemStack;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityFoodFilter extends TileEntityConveyorFilter {
	public TileEntityFoodFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_FOOD, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		return !item.isEmpty() && item.getItem().isEdible();
	}

}
