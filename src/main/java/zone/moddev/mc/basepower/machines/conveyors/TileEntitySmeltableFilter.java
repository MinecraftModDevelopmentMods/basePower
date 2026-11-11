package zone.moddev.mc.basepower.machines.conveyors;

import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeType;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntitySmeltableFilter extends TileEntityConveyorFilter {
	public TileEntitySmeltableFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_SMELT, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null || item.isEmpty() || level == null) return false;
		SingleRecipeInput probe = new SingleRecipeInput(item.copy());
		return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, probe, level).isPresent();
	}

}
