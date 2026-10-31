package zone.moddev.mc.poweradvantage.machines.fluidmachines;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.simple.BlockSimpleFluidMachine;
import cyano.poweradvantage.api.simple.TileEntitySimplePowerMachine;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

public class FluidDischargeBlock extends BlockSimpleFluidMachine {

	public FluidDischargeBlock() {
		super(Material.PISTON, 3f);
	}

	@Override
	public PoweredEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		return new FluidDischargeTileEntity(pos, state);
	}

	/**
	 * Destroys the TileEntity associated with this block when this block
	 * breaks.
	 */
	@Override
	public void onRemove(BlockState state, Level world, BlockPos coord, BlockState newState, boolean isMoving) {
		if (state.getBlock() != newState.getBlock()) {
			final BlockEntity tileEntity = world.getBlockEntity(coord);
			if (tileEntity instanceof TileEntitySimplePowerMachine) {
				Containers.dropContents(world, coord, (Container) tileEntity);
				world .updateNeighbourForOutputSignal(coord, this);
			}
		}
		super.onRemove(state, world, coord, newState, isMoving);
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState state, Level world, BlockPos coord) {
		BlockEntity te = world.getBlockEntity(coord);
		if (te != null && te instanceof FluidDischargeTileEntity) {
			return ((FluidDischargeTileEntity) te).getRedstoneOutput();
		}
		return 0;
	}

	/**
	 * Determines whether this block/entity should receive energy. If this is not a sink, then it
	 * will never be given power by a power source.
	 *
	 * @param powerType Type of power
	 * @return true if this block/entity should receive energy
	 */
	@Override
	public boolean isPowerSink(ConduitType powerType) {
		return true;
	}

	/**
	 * Determines whether this block/entity can provide energy.
	 *
	 * @param powerType Type of power
	 * @return true if this block/entity can provide energy
	 */
	@Override
	public boolean isPowerSource(ConduitType powerType) {
		return false;
	}
}
