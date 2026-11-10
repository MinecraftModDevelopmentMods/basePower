package zone.moddev.mc.basepower.machines.conveyors;

import net.minecraft.world.item.ItemStack;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityInventoryFilter extends TileEntityConveyorFilter {


	public static final int NUM_FILTER_SLOTS = 9;

	public TileEntityInventoryFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(Entities.FILTER_INVENTORY, pos, state, NUM_FILTER_SLOTS + 1);
	}

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		for (int i = 1; i < this.getInventory().length; i++) {
			if (this.getInventory()[i] != null && ItemStack.isSameItem(this.getInventory()[i], item)) {
				return true;
			}
		}
		return false;
	}

}
