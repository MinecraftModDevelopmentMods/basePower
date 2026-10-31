package zone.moddev.mc.poweradvantage.machines.fluidmachines;

import cyano.poweradvantage.api.simple.BlockSimpleFluidConduit;
import zone.moddev.mc.poweradvantage.init.Blocks;
import zone.moddev.mc.poweradvantage.util.FluidHandlerHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@SuppressWarnings("deprecation")
public class FluidPipeBlock extends BlockSimpleFluidConduit {

	public FluidPipeBlock() {
		super(Material.PISTON, 0.75f, 4f / 16f);
	}


	/**
	 * Called when a neighboring block changes.
	 */
	@Override
	public void neighborChanged(BlockState state, Level worldIn, BlockPos pos, Block blockIn, BlockPos fromPos, boolean isMoving) {
		pipeCheck(worldIn, pos);
	}

	/**
	 * Called by ItemBlocks after a block is set in the world, to allow post-place logic
	 */
	public void setPlacedBy(Level worldIn, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(worldIn, pos, state, placer, stack);
		pipeCheck(worldIn, pos);
	}

	private void pipeCheck(Level w, BlockPos pos) {
		if (!w.isClientSide) {
			if (numberOfAdjacentFluidHandlers(w, pos) > 0) {
				w.setBlock(pos, Blocks.fluid_pipe_terminal.defaultBlockState(), 3);
			}
		}
	}


	protected static int numberOfAdjacentFluidHandlers(Level w, BlockPos pos) {
		int sum = 0;
		for (Direction f : Direction.values()) {
			BlockEntity e = w.getBlockEntity(pos.relative(f));
			if (FluidHandlerHelper.hasHandler(e, f.getOpposite()) && !(e instanceof cyano.poweradvantage.api.PoweredEntity)) {
				sum++;
			}
		}
		return sum;
	}
}
