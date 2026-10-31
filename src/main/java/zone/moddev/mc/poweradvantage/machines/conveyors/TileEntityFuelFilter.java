package zone.moddev.mc.poweradvantage.machines.conveyors;


import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import zone.moddev.mc.poweradvantage.init.Entities;

public class TileEntityFuelFilter extends TileEntityConveyorFilter {
	public TileEntityFuelFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_FUEL, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		return AbstractFurnaceBlockEntity.isFuel(item);
	}

}
