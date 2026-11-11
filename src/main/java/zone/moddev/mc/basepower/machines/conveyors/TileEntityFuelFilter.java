package zone.moddev.mc.basepower.machines.conveyors;


import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;


public class TileEntityFuelFilter extends TileEntityConveyorFilter {
	public TileEntityFuelFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(zone.moddev.mc.basepower.init.Entities.FILTER_FUEL, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		return zone.moddev.mc.basepower.registry.FuelRegistry.getActualBurntimeForItem(item) > 0;
	}

}
