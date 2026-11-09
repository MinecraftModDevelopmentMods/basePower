package zone.moddev.mc.basepower.machines.conveyors;

import zone.moddev.mc.basepower.util.InventoryWrapper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityOverflowFilter extends TileEntityConveyorFilter {
	public TileEntityOverflowFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_OVERFLOW, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null || item.getItem() == null) return false;
		boolean canDrop = canInsert(item, Direction.DOWN);
		boolean canPass = canInsert(item, this.getFacing());
		if (canPass) {
			return canDrop;
		} else {
			return true;
		}
	}

	private boolean canInsert(ItemStack item, Direction f) {
		BlockEntity target = getLevel().getBlockEntity(getBlockPos().relative(f));
		if (target instanceof Container) {
			return canInsertItemInto(item, InventoryWrapper.wrap((Container) target), f.getOpposite());
		} else {
			return false;
		}
	}

	@Override
	protected boolean isValidItemFor(ItemStack item, BlockEntity target, Direction side) {
		// do all of the logic in the matchesFilter method
		return true;
	}

}
