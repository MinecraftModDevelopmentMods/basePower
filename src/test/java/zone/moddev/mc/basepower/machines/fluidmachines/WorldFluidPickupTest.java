package zone.moddev.mc.basepower.machines.fluidmachines;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldFluidPickupTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // Forge normally rebuilds these caches after the mod registry phase.
        Blocks.WATER.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
        Blocks.OAK_SLAB.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
    }

    @Test public void waterloggedHostSurvivesTheNativePickup() {
        AtomicReference<BlockState> state = new AtomicReference<>(Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        assertSame(Fluids.WATER, FluidDrainTileEntity.pickupSource(world(state), BlockPos.ZERO, state.get()));
        assertTrue(state.get().is(Blocks.OAK_SLAB));
        assertFalse(state.get().getValue(BlockStateProperties.WATERLOGGED));
    }

    @Test public void ordinaryWaterSourceBecomesAir() {
        AtomicReference<BlockState> state = new AtomicReference<>(Blocks.WATER.defaultBlockState());
        assertSame(Fluids.WATER, FluidDrainTileEntity.pickupSource(world(state), BlockPos.ZERO, state.get()));
        assertTrue(state.get().isAir());
    }

    @Test public void flowingWaterDoesNotProduceABucket() {
        AtomicReference<BlockState> state = new AtomicReference<>(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        BlockState original = state.get();
        assertSame(Fluids.EMPTY, FluidDrainTileEntity.pickupSource(world(state), BlockPos.ZERO, state.get()));
        assertSame(original, state.get());
    }

    @Test public void nonPickupBlockIsNeverRemoved() {
        AtomicReference<BlockState> state = new AtomicReference<>(Blocks.BEDROCK.defaultBlockState());
        assertSame(Fluids.EMPTY, FluidDrainTileEntity.pickupSource(world(state), BlockPos.ZERO, state.get()));
        assertTrue(state.get().is(Blocks.BEDROCK));
    }

    private static LevelAccessor world(AtomicReference<BlockState> state) {
        return (LevelAccessor)Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[]{LevelAccessor.class}, (proxy, method, args) -> {
            if (method.getName().equals("setBlock") || method.getName().equals("setBlockAndUpdate")) {
                state.set((BlockState)args[1]); return true;
            }
            if (method.getName().equals("getBlockState")) return state.get();
            throw new AssertionError("Unexpected world operation " + method.getName());
        });
    }
}
