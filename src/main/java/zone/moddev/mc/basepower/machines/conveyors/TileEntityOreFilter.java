package zone.moddev.mc.basepower.machines.conveyors;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.Tags;


public class TileEntityOreFilter extends TileEntityConveyorFilter {
	public TileEntityOreFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(zone.moddev.mc.basepower.init.Entities.FILTER_ORE, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null || item.isEmpty()) return false;
		return item.is(Tags.Items.INGOTS) || item.is(Tags.Items.ORES)
				|| item.is(Tags.Items.DUSTS) || item.is(Tags.Items.NUGGETS);
	}

}
