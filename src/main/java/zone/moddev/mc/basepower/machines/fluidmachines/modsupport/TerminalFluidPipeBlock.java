package zone.moddev.mc.basepower.machines.fluidmachines.modsupport;

import cyano.poweradvantage.api.ConduitType;
import zone.moddev.mc.basepower.init.Blocks;
import zone.moddev.mc.basepower.machines.fluidmachines.FluidPipeBlock;
import zone.moddev.mc.basepower.util.FluidHandlerHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

@SuppressWarnings("deprecation")
public class TerminalFluidPipeBlock extends FluidPipeBlock implements EntityBlock {

	@Override
	public BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		return new TerminalFluidPipeTileEntity(pos, state);
	}


	@Override
	public boolean isPowerSink(ConduitType powerType) {
		return true;
	}

	@Override
	public boolean isPowerSource(ConduitType powerType) {
		return true;
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
			if (numberOfAdjacentFluidHandlers(w, pos) == 0) {
				w.setBlock(pos, Blocks.fluid_pipe.defaultBlockState(), 3);
			}
		}
	}

	/**
	 * This method determines whether to connect to a neighboring block.
	 * Override this method to change block connection behavior.
	 *
	 * @param w          World instance
	 * @param thisBlock  The block that is checking its neighbor
	 * @param bs         Block state of this block
	 * @param face       The face on the first block through which the connection would happen
	 * @return Default implementation: true if the neighboring block implements
	 * ITypedConductor and has the same energy type as this block. Overriding
	 * the canConnectTo(ConductorType) method will change the results of this
	 * method.
	 */
	@Override
	protected boolean canConnectTo(BlockGetter w, BlockPos thisBlock, BlockState bs, Direction face) {
		BlockPos otherBlock = thisBlock.relative(face);
		return super.canConnectTo(w, thisBlock, bs, face)
				|| FluidHandlerHelper.hasHandler(w.getBlockEntity(otherBlock), face.getOpposite());
	}

}
