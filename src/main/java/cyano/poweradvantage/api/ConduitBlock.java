package cyano.poweradvantage.api;

import zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;

/**
 * 
 * This is the superclass for blocks that conduct power from power generators to 
 * the machines that need power. If you are making an add-on mod, you probably 
 * want to extend the 
 * <b>cyano.poweradvantage.api.simple.BlockSimplePowerConduit</b> class 
 * instead of this class.
 * <br><br>
 * Note that the conduit blocks do not have tile entities. They do not store data for energy 
 * transmission. Simply implementing the ITypedConduit interface adds them to the energy 
 * transmission algorithm that finds the connections between generators and machines. 
 * 
 * 
 * @author DrCyano
 *
 */
public abstract class ConduitBlock extends net.minecraft.world.level.block.Block implements  ITypedConduit {
	protected ConduitBlock(Properties properties) {
		super(properties);
	}

	/**
	 * This method is called whenever the block is placed into the world
	 */
	@Override
	public void onPlace(BlockState state, Level w, BlockPos coord, BlockState oldState, boolean isMoving) {
		super.onPlace(state, w, coord, oldState, isMoving);
		ConduitRegistry.getInstance().conduitBlockPlacedEvent(w, coord, getTypes());
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

}
