package cyano.poweradvantage.api;

import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.Block;
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
public abstract class GUIBlock extends net.minecraft.world.level.block.BaseEntityBlock{

	/**
	 * Constructor for GUI block
	 * @param m Material for the block (determines what tools can break it and how it interacts with 
	 * other Minecraft rules).
	 */
	public GUIBlock(Material m) {
		this(m, 1.0F);
	}

	public GUIBlock(Material m, float hardness) {
		this(Block.Properties.of(m).strength(hardness).noOcclusion().dynamicShape());
	}

	protected GUIBlock(Block.Properties properties) {
		super(properties);
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
    @Override
    public InteractionResult use(BlockState bs, Level w, BlockPos coord,
                                    Player player, InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        Direction facing = hit.getDirection();
        if (w.isClientSide) {
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
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState,
			boolean isMoving) {
		if (state.getBlock() != newState.getBlock()) {
			BlockEntity te = world.getBlockEntity(pos);
			if (te instanceof Container) {
				Containers.dropContents(world, pos, (Container) te);
				((Container) te).clearContent();
				world .updateNeighbourForOutputSignal(pos, this);
			}
			super.onRemove(state, world, pos, newState, isMoving);
		}
	}

}
