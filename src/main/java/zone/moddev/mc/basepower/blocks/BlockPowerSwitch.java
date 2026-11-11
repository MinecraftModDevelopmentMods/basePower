package zone.moddev.mc.basepower.blocks;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.ITypedConduit;
import cyano.poweradvantage.api.PowerConnectorContext;
import zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/**
 * This block class is a user-friendly power switch for turning power on and off. You do not need to 
 * extend this class, just specify the type of power being switched in the constructor.
 * @author DrCyano
 *
 */
public class BlockPowerSwitch extends cyano.poweradvantage.api.ConduitBlock implements ITypedConduit {
	/**
	 * Blockstate peroperty. If true, conduct power, if false, don't
	 */
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
	private final ConduitType[] powerType;

	/**
	 * Constructor for making a power switch
	 *
	 * @param powerType     Type of power to switch
	 * @param properties    Block behavior, including break hardness
	 */
	public BlockPowerSwitch(ConduitType powerType, Block.Properties properties) {
		super(properties);
		this.powerType = new ConduitType[1];
		this.powerType[0] = powerType;
		this.registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
	}

	/**
	 * Constructor for making a power switch
	 *
	 * @param powerType Type of power to switch
	 */
	public BlockPowerSwitch(ConduitType powerType) {
		this(powerType, zone.moddev.mc.basepower.init.Blocks.properties("fluid_switch",
                Properties.of().mapColor(MapColor.STONE).pushReaction(PushReaction.BLOCK)
				.forceSolidOn().strength(0.75F)));
	}

	/**
	 * Creates a blockstate
	 */
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ACTIVE);
	}

	/**
	 * Converts metadata into blockstate
	 */
	public BlockState getStateFromMeta(final int metaValue) {
		return this.defaultBlockState()
				.setValue(ACTIVE, metaValue > 0);
	}

	/**
	 * Converts blockstate into metadata
	 */
	public int getMetaFromState(final BlockState bs) {
		if ((Boolean) (bs .getValue(ACTIVE))) {
			return 1;
		} else {
			return 0;
		}
	}

	/**
	 * Determines whether this conduit is compatible with an adjacent one
	 *
	 * @param connection A context object that provides the power type and block direction information
	 * @return True if power should be allowed to flow through this connection, false otherwise
	 */
	@Override
	public boolean canAcceptConnection(PowerConnectorContext connection) {
		return (Boolean) connection.thisBlock .getValue(ACTIVE) && ConduitType.areSameType(powerType[0], connection.powerType);
	}

	@Override
	public ConduitType[] getTypes() {
		return powerType;
	}

	@Override
	public boolean isPowerSink(ConduitType powerType) {
		return false;
	}

	@Override
	public boolean isPowerSource(ConduitType powerType) {
		return false;
	}

	/**
	 * Used to determine ambient occlusion and culling when rebuilding chunks for render
	 */
	public boolean isOpaqueCube(BlockState bs) {
		return true;
	}

	public boolean isFullCube(BlockState bs) {
		return true;
	}

	/**
	 * Override of default block behavior
	 */
	@Override
	public void onPlace(final BlockState state, final Level world, final BlockPos coord, final BlockState oldState, final boolean isMoving) {
		super.onPlace(state, world, coord, oldState, isMoving);
		ConduitRegistry.getInstance().conduitBlockPlacedEvent(world, coord, getTypes());
	}

	/**
	 * This method is called when the block is removed from the world by an entity.
	 */
	@Override
	public void onRemove(BlockState state, Level w, BlockPos coord, BlockState newState, boolean isMoving) {
		if (state.getBlock() != newState.getBlock()) {
			ConduitRegistry.getInstance().conduitBlockRemovedEvent(w, coord, getTypes());
		}
		super.onRemove(state, w, coord, newState, isMoving);
	}

	/**
	 * This method is called when the block is destroyed by an explosion.
	 */
	@Override
	public void wasExploded(Level w, BlockPos coord, Explosion boom) {
		super.wasExploded(w, coord, boom);
		ConduitRegistry.getInstance().conduitBlockRemovedEvent(w, coord, getTypes());
	}

	/**
	 * Called when a neighboring block changes.
	 */
	public void onNeighborChange(BlockGetter w, BlockPos pos, BlockPos neighbor) {
		if (w instanceof Level) {
			Level world = (Level) w;
			BlockState state = world.getBlockState(pos);
			// redstone power given or taken
			boolean activated = !world.hasNeighborSignal(pos);
			world.setBlock(pos, state.setValue(ACTIVE, activated), 2);
			if (activated) {
				ConduitRegistry.getInstance().conduitBlockPlacedEvent(world, pos, getTypes());
			} else {
				ConduitRegistry.getInstance().conduitBlockRemovedEvent(world, pos, getTypes());
			}
		}
	}

	@Override
	public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block,
            net.minecraft.world.level.redstone.Orientation orientation, boolean isMoving) {
		onNeighborChange(world, pos, pos);
	}

}
