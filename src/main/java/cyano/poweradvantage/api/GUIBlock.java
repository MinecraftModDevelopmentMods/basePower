package cyano.poweradvantage.api;

import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import zone.moddev.mc.basepower.util.FluidContainerHelper;

/**
 * <p>
 * The GUIBlock is a convenient abstract class for all blocks that should show a GUI when the player 
 * right-clicks on the block. After creating an instance of the GUIBlock class, get a GUI index 
 * by calling <code>int gui_index = MachineGUIRegistry.addGUI(...)</code> and set the GUI indes and 
 * GUI owner with 
 * <code>myGUIBlock.setGuiID(gui_index); myGUIBlock.setGuiOwner(PowerAdvantage.getInstance())</code>. 
 * Of course, if you are managing the GUIs yourself, then you will provide your own GUI index and 
 * use your mod's class instance as the owner instead of PowerAdvantage.
 * </p> 
 * @author DrCyano
 *
 */
public abstract class GUIBlock extends Block implements EntityBlock {

	/** Defaults for machine blocks formerly made from the piston material. */
	public static Block.Properties machineProperties(float hardness) {
		return Block.Properties.of().mapColor(MapColor.STONE).pushReaction(PushReaction.BLOCK)
				.forceSolidOn().strength(hardness).noOcclusion().dynamicShape();
	}

	protected GUIBlock(Block.Properties properties) {
		super(properties);
	}

    public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) { return 0; }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos, Direction side) {
        return getAnalogOutputSignal(state, world, pos);
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int data) {
        super.triggerEvent(state, level, pos, id, data);
        BlockEntity tile = level.getBlockEntity(pos);
        return tile != null && tile.triggerEvent(id, data);
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MenuProvider provider ? provider : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (!type.isValid(state)) return null;
        return (world, pos, currentState, tile) -> {
            if (tile.isRemoved() || tile.getType() != type || tile.getLevel() != world) return;
            if (tile instanceof PoweredEntity) {
                ((PoweredEntity) tile).tick();
            } else if (tile instanceof zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor) {
                ((zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor) tile).tick();
            }
        };
    }

	@Deprecated
	protected void setPowerAdvantageHardness(float hardness) {
		// Block properties are immutable after construction in 1.13.
	}
	
	private int guiId = 0;
	private Object guiOwner = null;
	/**
	 * Sets the GUI index number for the GUI to show when this block is right-clicked by the player. 
	 * In short, when the player right-clicks this block, the following code is called<br>
	 * <code>player.openGui(this.getGuiOwner(), this.getGuiID(),world,pos);</code>
	 * @param idNumber The number of the GUI to show according to the Forge GUI system.
	 * @param guiOwner This is the object that was used to register the GUI handler (e.g. <i>PowerAdvantage.getInstance()</i> in 
	 * <code>NetworkRegistry.INSTANCE.registerGuiHandler(PowerAdvantage.getInstance(), MachineGUIRegistry.getInstance());</code>
	 * ). This is usually the mod's main class, or if you are using the <b>MachineGUIRegistry</b>, 
	 * then you would set the GUI owner to PowerAdvantage.getInstance().
	 */
	public void setGuiID(int idNumber, Object guiOwner){
		this.guiId = idNumber;
		this.guiOwner = guiOwner;
	}
	/**
	 * Gets the GUI index number for the GUI to show when this block is right-clicked by the player. 
	 * In short, when the player right-clicks this block, the following code is called<br>
	 * <code>player.openGui(this.getGuiOwner(), this.getGuiID(),world,pos);</code>
	 * @return The number of the GUI to show according to the Forge GUI system.
	 */
	public int getGuiID(){
		return guiId;
	}
	/**
	 * Gets the object that was used to register the GUI handler (e.g. <i>PowerAdvantage.getInstance()</i> in 
	 * <code>NetworkRegistry.INSTANCE.registerGuiHandler(PowerAdvantage.getInstance(), MachineGUIRegistry.getInstance());</code>
	 * ). This is usually the mod's main class, or if you are using the <b>MachineGUIRegistry</b>, 
	 * then you would set the GUI owner to PowerAdvantage.getInstance(). 
	 * In short, when the player right-clicks this block, the following code is called<br>
	 * <code>player.openGui(this.getGuiOwner(), this.getGuiID(),world,pos);</code>
	 * @return The owner of the GUI when you registered the GUI handler (not the GUI handler 
	 * itself).
	 */
	public Object getGuiOwner(){
		return this.guiOwner;
	}
	/**
	 * Boilerplate code
	 */
	public boolean isFullCube(BlockState bs) {
        return false;
    }
    
	/**
	 * Boilerplate code
	 */
    public boolean isOpaqueCube(BlockState bs) {
        return false;
    }

	/**
	 * 3 = normal block (model specified in assets folder as .json model)<br>
	 * -1 = special renderer
	 */
	@Override
    public RenderShape getRenderShape(BlockState bs) {
        return RenderShape.MODEL;
    }
	
	/**
     * Override of default block behavior to show the player the GUI for this 
     * block. Calls <code>player.openGui(this.getGuiOwner(), this.getGuiID(),world,pos);</code> on 
     * right-click.
     * @return true if the interaction resulted in opening the GUI, false 
     * otherwise
     */
	@SuppressWarnings("deprecation")
    public InteractionResult use(BlockState bs, Level w, BlockPos coord,
                                    Player player, InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        Direction facing = hit.getDirection();
        if (w.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        final BlockEntity tileEntity = w.getBlockEntity(coord);
        if (tileEntity == null || player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        // handle buckets and fluid containers
		net.minecraft.world.item.ItemStack item = player.getItemInHand(hand);
		if(item != null && !item.isEmpty() && tileEntity instanceof IAdvantageFluidHandler
				&& FluidContainerHelper.interact(player, hand, (IAdvantageFluidHandler) tileEntity, facing)) {
			return InteractionResult.SUCCESS;
		}

        // open GUI
        if(this.getGuiOwner() == null) return InteractionResult.PASS;
		if(tileEntity instanceof PoweredEntity && player instanceof ServerPlayer){
			((PoweredEntity)tileEntity).syncPowerAdvantageDataTo((ServerPlayer)player);
		}
		if (!(player instanceof ServerPlayer)) return InteractionResult.PASS;
		MachineGUIRegistry.open((ServerPlayer) player, w, coord, this.getGuiID());
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack item, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    
   
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState,
			boolean isMoving) {
		if (state.getBlock() != newState.getBlock()) {
			world.updateNeighbourForOutputSignal(pos, this);
		}
	}

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel world,
            BlockPos pos, boolean moving) {
        // Native BlockEntity.preRemoveSideEffects drops inventories before this callback.
        onRemove(state, world, pos, world.getBlockState(pos), moving);
        super.affectNeighborsAfterRemoval(state, world, pos, moving);
    }

    public void wasExploded(Level world, BlockPos pos, net.minecraft.world.level.Explosion explosion) {}

    @Override
    public void wasExploded(net.minecraft.server.level.ServerLevel world, BlockPos pos,
            net.minecraft.world.level.Explosion explosion) {
        super.wasExploded(world, pos, explosion);
        wasExploded((Level) world, pos, explosion);
    }

}
