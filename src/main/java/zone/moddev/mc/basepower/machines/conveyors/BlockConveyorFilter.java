package zone.moddev.mc.basepower.machines.conveyors;

import cyano.poweradvantage.api.GUIBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Horizontal conveyor filter block retaining its legacy metadata codec. */
public class BlockConveyorFilter extends GUIBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private final Class<? extends TileEntityConveyor> tileEntityClass;

    public BlockConveyorFilter(Material material, float hardness,
            Class<? extends TileEntityConveyor> tileEntityClass) {
        super(Block.Properties.of(material).strength(hardness)
                .sound(SoundType.METAL).noOcclusion().dynamicShape());
        this.tileEntityClass = tileEntityClass;
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        try {
            return tileEntityClass.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
        } catch (ReflectiveOperationException exception) {
            PowerAdvantage.LOGGER.error("Could not create conveyor filter {}", tileEntityClass.getName(), exception);
            return null;
        }
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public boolean isOpaqueCube(BlockState state) { return false; }
    public boolean isFullCube(BlockState state) { return false; }
    @Override public boolean hasAnalogOutputSignal(BlockState state) { return true; }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof TileEntityConveyorFilter)) return 0;
        TileEntityConveyorFilter filter = (TileEntityConveyorFilter) tile;
        if (filter.getItem(0).isEmpty()) return 0;
        return filter.matchesFilter(filter.getItem(0)) ? 15 : 7;
    }

    public BlockState getStateFromMeta(int meta) {
        Direction facing = Direction.from3DDataValue(meta);
        return defaultBlockState().setValue(FACING, facing.getAxis().isHorizontal() ? facing : Direction.NORTH);
    }
    public int getMetaFromState(BlockState state) { return state.getValue(FACING).get3DDataValue(); }
}
