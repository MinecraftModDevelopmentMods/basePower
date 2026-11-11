package cyano.poweradvantage.api.simple;

import cyano.poweradvantage.api.*;
import zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;

import java.util.Random;

/**
 * This block class provides all of the standard code for creating a machine 
 * block with an inventory and user interface that gives power to connected 
 * machines.<br>
 * Example usage:<br><pre>
int guiID = zone.moddev.mc.basepower.registry.MachineGUIRegistry.addGUI(new MySimpleMachineGUI());
Block myMachineBlock = new MyBlockSimplePowerSource(guiID,PowerAdvantage.getInstance());
myMachineBlock.setTranslationKey(MODID+"."+"my_machine");
myMachineBlock.setRegistryName(MODID, "my_machine");
net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS.register(myMachineBlock);
 * </pre>
 * @author DrCyano
 *
 */
public abstract class BlockSimplePowerMachine extends GUIBlock implements ITypedConduit {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private final ConduitType[] type;


	/**
	 * Sets the block properties and the energy types accepted by this machine.
	 */
	protected BlockSimplePowerMachine(Block.Properties properties, ConduitType... energyType) {
		super(properties);
		this.type = energyType;
		this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
	}
	
	/**
	 * Creates a TileEntity for this block when the block is placed into the 
	 * world.
	 * @return A new TileEntity instance, probably one that extends 
	 * <b>TileEntitySimplePowerMachine</b>.
	 */
	@Override
    public abstract PoweredEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state);
	
	/**
	 * Override of default block behavior
	 */
	@Override
	public void onPlace(final BlockState state, final Level world, final BlockPos coord,
			final BlockState oldState, final boolean isMoving) {
		super.onPlace(state, world, coord, oldState, isMoving);
		this.setDefaultFacing(world, coord, state);
		ConduitRegistry.getInstance().conduitBlockPlacedEvent(world, coord, getTypes());
	}
	/**
	 * This method is called when the block is removed from the world by an entity.
	 */
	@Override
	public void onRemove(BlockState state, Level w, BlockPos coord, BlockState newState,
			boolean isMoving) {
		if (state.getBlock() != newState.getBlock()) {
			ConduitRegistry.getInstance().conduitBlockRemovedEvent(w, coord, getTypes());
		}
		super.onRemove(state, w, coord, newState, isMoving);
	}
	/**
	 * This method is called when the block is destroyed by an explosion.
	 */
	@Override
	public void wasExploded(Level w, BlockPos coord, Explosion boom){
		super.wasExploded(w, coord, boom);
		ConduitRegistry.getInstance().conduitBlockRemovedEvent(w, coord, getTypes());
	}
	
	/**
	 * Used to decides whether or not a conduit should connect to this block 
	 * based on its energy type.
	 * @return The type of energy for this block 
	 */
	@Override
	public ConduitType[] getTypes() {
		return type;
	}

	/**
	 * Determines whether this conduit is compatible with an adjacent one
	 * @param connection A context object that provides the power type and block direction information
	 * @return True if power should be allowed to flow through this connection, false otherwise
	 */
	@Override
	public boolean canAcceptConnection(PowerConnectorContext connection){
		ConduitType[] myTypes = getTypes();
		for(int i = 0; i < myTypes.length; i++){
			if(ConduitType.areSameType(myTypes[i],connection.powerType)) return true;
		}
		return false;
	}
	
	/**
	 * Override of default block behavior
	 */
	/**
	 * Creates the blockstate of this block when it is placed in the world
	 */
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    
    /**
	 * Creates the blockstate of this block when it is placed in the world
	 */
    @Override
    public void setPlacedBy(final Level world, final BlockPos coord, final BlockState bs,
            final LivingEntity placer, final ItemStack srcItemStack) {
        world.setBlock(coord, bs.setValue((Property) FACING, (Comparable)placer.getDirection().getOpposite()), 2);
        if (srcItemStack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
            final BlockEntity tileEntity = world.getBlockEntity(coord);
        	if (tileEntity instanceof PoweredEntity){
				((PoweredEntity)tileEntity).setCustomInventoryName(srcItemStack.getDisplayName().getString());
        	}
        }
    }
    
    /**
     * Destroys the TileEntity associated with this block when this block 
     * breaks.
     */
    /**
     * Sets the default blockstate
     * @param w World instance
     * @param coord Block coordinate
     * @param state Block state
     */
    protected void setDefaultFacing(final Level w, final BlockPos coord, final BlockState state) {
        if (w.isClientSide()) {
            return;
        }
        final BlockState block = w.getBlockState(coord.north());
        final BlockState block2 = w.getBlockState(coord.south());
        final BlockState block3 = w.getBlockState(coord.west());
        final BlockState block4 = w.getBlockState(coord.east());
        Direction enumFacing = (Direction)state .getValue(FACING);
        if (enumFacing == Direction.NORTH && block.isRedstoneConductor(w, coord.north()) && !block2.isRedstoneConductor(w, coord.south())) {
            enumFacing = Direction.SOUTH;
        }
        else if (enumFacing == Direction.SOUTH && block2.isRedstoneConductor(w, coord.south()) && !block.isRedstoneConductor(w, coord.north())) {
            enumFacing = Direction.NORTH;
        }
        else if (enumFacing == Direction.WEST && block3.isRedstoneConductor(w, coord.west()) && !block4.isRedstoneConductor(w, coord.east())) {
            enumFacing = Direction.EAST;
        }
        else if (enumFacing == Direction.EAST && block4.isRedstoneConductor(w, coord.east()) && !block3.isRedstoneConductor(w, coord.west())) {
            enumFacing = Direction.WEST;
        }
        w.setBlock(coord, state.setValue((Property) FACING, (Comparable)enumFacing), 2);
    }
    
    /**
    * This method tells Minecraft whether this block has a signal that can be 
    * measured by a redstone comparator.
    * <br><br>
    * You are encouraged to integrate redstone control into your machines. 
    * This means outputin a redstone signal with the 
    * <code>getComparatorInputOverride(...)</code> method and (in your 
    * TileEntity class) reading the redstone input with the 
    * <code>World.isBlockPowered(...)</code> method.<br><br>
    * Typically, machines output a redstone value proportional to the amount of 
    * stuff in their inventory and are disabled when they receive a redstone 
    * signal.
	 * @param state The blockstate of this block
    * @return true if this block can be measured by a redstone comparator, 
    * false otherwise
    */
    @Override
    public abstract boolean hasAnalogOutputSignal(BlockState state);
    
    /**
     * This method gets the output for a redstone comparator placed against this 
     * block.
     * <br><br>
     * You are encouraged to integrate redstone control into your machines. 
     * This means outputin a redstone signal with the 
     * <code>getComparatorInputOverride(...)</code> method and (in your 
     * TileEntity class) reading the redstone input with the 
     * <code>World.isBlockPowered(...)</code> method.<br><br>
     * Typically, machines output a redstone value proportional to the amount of 
     * stuff in their inventory and are disabled when they receive a redstone 
     * signal.
	 * @param state The blockstate of this block
     * @param world World object
     * @param coord Coordinates of this block
     * @return a number from 0 to 15
     */
    @Override
    public  abstract  int getAnalogOutputSignal(BlockState state, final Level world, final BlockPos coord);
    

    /**
     * Converts metadata into blockstate
     */
    public BlockState getStateFromMeta(final int metaValue) {
        Direction enumFacing = Direction.from3DDataValue(metaValue);
        if (enumFacing.getAxis() == Direction.Axis.Y) {
            enumFacing = Direction.NORTH;
        }
        return this.defaultBlockState().setValue( FACING, enumFacing);
    }
    
    /**
     * Converts blockstate into metadata
     */
    public int getMetaFromState(final BlockState bs) {
        return ((Direction)bs .getValue(FACING)).get3DDataValue();
    }
    
    /**
     * Creates a blockstate
     */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
    }

	@Override
	public BlockState rotate(BlockState state, Rotation rot) {
		return state.setValue(FACING, rot.rotate((Direction)state .getValue(FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirrorIn) {
		return state.rotate(mirrorIn.getRotation((Direction)state .getValue(FACING)));
	}

	/**
	 * Determines whether this block/entity should receive energy of this type. If this is not a sink, then it
	 * will never be given power by a power source.
	 * @param powerType Type of power
	 * @return true if this block/entity should receive energy
	 */
	@Override
	public abstract boolean isPowerSink(ConduitType powerType) ;

	/**
	 * Determines whether this block/entity can provide energy of this type.
	 * @param powerType Type of power
	 * @return true if this block/entity can provide energy
	 */
	@Override
	public abstract boolean isPowerSource(ConduitType powerType) ;

}
