package zone.moddev.mc.poweradvantage.machines.conveyors;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.crafting.RecipeType;
import zone.moddev.mc.poweradvantage.init.Entities;

public class TileEntitySmeltableFilter extends TileEntityConveyorFilter {
	public TileEntitySmeltableFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(Entities.FILTER_SMELT, pos, state, 1); }

	@Override
	public boolean matchesFilter(ItemStack item) {
		if (item == null || item.isEmpty() || level == null) return false;
		SimpleContainer probe = new SimpleContainer(1);
		probe.setItem(0, item.copy());
		return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, probe, level).isPresent();
	}

}
