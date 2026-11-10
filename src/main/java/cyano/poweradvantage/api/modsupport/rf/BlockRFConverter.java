package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.simple.BlockSimplePowerMachine;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Historical RF-labelled converter block, backed by Forge Energy in 1.13. */
public class BlockRFConverter extends BlockSimplePowerMachine {
    private final Class<? extends TileEntityRFConverter> tileEntityClass;

    public BlockRFConverter(Properties properties, ConduitType type,
            Class<? extends TileEntityRFConverter> tileEntityClass) {
        super(properties, type);
        this.tileEntityClass = tileEntityClass;
    }

    @Override
    public TileEntityRFConverter newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        try {
            return tileEntityClass.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create converter tile " + tileEntityClass.getName(), exception);
        }
    }

    @Override public boolean isPowerSink(ConduitType powerType) { return true; }
    @Override public boolean isPowerSource(ConduitType powerType) { return true; }
    @Override public boolean hasAnalogOutputSignal(BlockState state) { return false; }
    @Override public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) { return 0; }
}
