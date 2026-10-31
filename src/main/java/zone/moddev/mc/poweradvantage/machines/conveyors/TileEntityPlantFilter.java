package zone.moddev.mc.poweradvantage.machines.conveyors;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.IPlantable;
import zone.moddev.mc.poweradvantage.init.Entities;

public class TileEntityPlantFilter extends TileEntityConveyorFilter {
	public TileEntityPlantFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_PLANT, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null) return false;
		if (item.getItem() instanceof IPlantable) return true;
		if (item.getItem() instanceof BlockItem) {
			Block b = ((BlockItem) item.getItem()).getBlock();
			if (b instanceof IPlantable) return true;
			if (b instanceof BonemealableBlock) return true;

		}
		return false;
	}

}
