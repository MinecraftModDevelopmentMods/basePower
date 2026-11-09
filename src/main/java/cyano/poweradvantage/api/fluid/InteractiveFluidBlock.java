package cyano.poweradvantage.api.fluid;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/** A native 1.13 fluid block which can affect immersed living entities. */
public class InteractiveFluidBlock extends LiquidBlock {
    private final BiConsumer<Level, LivingEntity> immersionEffect;
    private final boolean flammable;

    public InteractiveFluidBlock(FlowingFluid fluid, Block.Properties properties, boolean flammable,
            BiConsumer<Level, LivingEntity> immersionEffect) {
        this(() -> fluid, properties, flammable, immersionEffect);
    }

    public InteractiveFluidBlock(Supplier<? extends FlowingFluid> fluid, Block.Properties properties,
            boolean flammable, BiConsumer<Level, LivingEntity> immersionEffect) {
        super(fluid, properties);
        this.flammable = flammable;
        this.immersionEffect = immersionEffect;
    }

    @Override
    public void entityInside(BlockState state, Level world, BlockPos pos, Entity entity) {
        if (immersionEffect != null && entity instanceof LivingEntity) {
            immersionEffect.accept(world, (LivingEntity) entity);
        }
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter world, BlockPos pos, Direction face) {
        return flammable ? 60 : 0;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter world, BlockPos pos, Direction face) {
        return flammable ? 30 : 0;
    }
}
