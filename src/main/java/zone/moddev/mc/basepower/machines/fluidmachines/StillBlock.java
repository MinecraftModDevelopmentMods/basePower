package zone.moddev.mc.basepower.machines.fluidmachines;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.simple.BlockSimpleFluidMachine;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;

public class StillBlock extends BlockSimpleFluidMachine {


	/**
	 * Blockstate property
	 */
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	public StillBlock() {
		super(Properties.of().mapColor(MapColor.STONE).pushReaction(PushReaction.BLOCK).forceSolidOn()
                .strength(3F).lightLevel(state -> state.getValue(ACTIVE) ? 12 : 0));
		this.registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
	}

	/**
	 * Creates a blockstate
	 */
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(ACTIVE);
	}

	/**
	 * Converts metadata into blockstate
	 */
	@Override
	public BlockState getStateFromMeta(final int metaValue) {
		Direction enumFacing = metaToFacing(metaValue);
		if (enumFacing.getAxis() == Direction.Axis.Y) {
			enumFacing = Direction.NORTH;
		}
		return this.defaultBlockState().setValue(FACING, enumFacing)
				.setValue(ACTIVE, (metaValue & 0x4) != 0);
	}

	/**
	 * Converts blockstate into metadata
	 */
	@Override
	public int getMetaFromState(final BlockState bs) {
		int extraBit;
		if ((Boolean) (bs .getValue(ACTIVE))) {
			extraBit = 0x4;
		} else {
			extraBit = 0;
		}
		return facingToMeta((Direction) bs .getValue(FACING)) | extraBit;
	}

	private int facingToMeta(Direction f) {
		switch (f) {
			case NORTH:
				return 0;
			case WEST:
				return 1;
			case SOUTH:
				return 2;
			case EAST:
				return 3;
			default:
				return 0;
		}
	}

	private Direction metaToFacing(int i) {
		int f = i & 0x03;
		switch (f) {
			case 0:
				return Direction.NORTH;
			case 1:
				return Direction.WEST;
			case 2:
				return Direction.SOUTH;
			case 3:
				return Direction.EAST;
			default:
				return Direction.NORTH;
		}
	}

	@Override
	public PoweredEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		return new StillTileEntity(pos, state);
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState bs) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState bs, Level world, BlockPos coord) {
		BlockEntity te = world.getBlockEntity(coord);
		if (te instanceof StillTileEntity) {
			return ((StillTileEntity) te).getRedstoneOutput();
		}
		return 0;
	}

	/**
	 * Determines whether this block/entity should receive energy
	 *
	 * @param powerType Type of power
	 * @return true if this block/entity should receive energy
	 */
	@Override
	public boolean isPowerSink(ConduitType powerType) {
		return true;
	}

	/**
	 * Determines whether this block/entity can provide energy
	 *
	 * @param powerType Type of power
	 * @return true if this block/entity can provide energy
	 */
	@Override
	public boolean isPowerSource(ConduitType powerType) {
		return true;
	}

}
