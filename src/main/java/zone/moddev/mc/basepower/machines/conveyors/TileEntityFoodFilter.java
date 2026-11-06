package zone.moddev.mc.basepower.machines.conveyors;

import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityFoodFilter extends TileEntityConveyorFilter {
	public TileEntityFoodFilter() { super(Entities.FILTER_FOOD, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		return item.getItem() instanceof ItemFood;
	}

}
