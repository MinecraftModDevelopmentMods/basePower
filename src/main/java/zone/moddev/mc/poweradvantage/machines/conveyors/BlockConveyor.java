package zone.moddev.mc.poweradvantage.machines.conveyors;

import cyano.poweradvantage.api.GUIBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/** Six-facing conveyor block; legacy metadata remains the facing index. */
public class BlockConveyor extends GUIBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public BlockConveyor(Material material, float hardness) {
        super(Block.Properties.of(material).strength(hardness)
                .sound(SoundType.METAL).noOcclusion().dynamicShape());
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override public BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { return new TileEntityConveyor(pos, state); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public boolean isOpaqueCube(BlockState state) { return false; }
    public boolean isFullCube(BlockState state) { return false; }
    @Override public boolean hasAnalogOutputSignal(BlockState state) { return true; }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) {
        BlockEntity tile = world.getBlockEntity(pos);
        return tile instanceof TileEntityConveyor && !((TileEntityConveyor) tile).getItem(0).isEmpty() ? 7 : 0;
    }

    public BlockState getStateFromMeta(int meta) {
        Direction facing = Direction.from3DDataValue(meta);
        return defaultBlockState().setValue(FACING, facing == null ? Direction.NORTH : facing);
    }
    public int getMetaFromState(BlockState state) { return state.getValue(FACING).get3DDataValue(); }
}
