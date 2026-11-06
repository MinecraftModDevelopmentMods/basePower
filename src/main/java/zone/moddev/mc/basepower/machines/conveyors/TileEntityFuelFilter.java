package zone.moddev.mc.basepower.machines.conveyors;


import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.AbstractFurnaceTileEntity;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityFuelFilter extends TileEntityConveyorFilter {
	public TileEntityFuelFilter() { super(Entities.FILTER_FUEL, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		return AbstractFurnaceTileEntity.isFuel(item);
	}

}
