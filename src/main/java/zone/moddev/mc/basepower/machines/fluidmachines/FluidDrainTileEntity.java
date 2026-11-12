package zone.moddev.mc.basepower.machines.fluidmachines;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTank;
import cyano.poweradvantage.api.fluid.FluidTankInfo;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import cyano.poweradvantage.api.simple.TileEntitySimpleFluidMachine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import zone.moddev.mc.basepower.util.FluidHandlerHelper;

/** Pulls source blocks or adjacent Advantage tanks into the pipe network. */
public class FluidDrainTileEntity extends TileEntitySimpleFluidMachine {
    public FluidDrainTileEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        super(zone.moddev.mc.basepower.init.Entities.FLUID_DRAIN, pos, state, FluidTank.BUCKET_VOLUME, "tile.basepower.fluid_drain.name");
    }

    @Override
    public void powerUpdate() {
        if (getTank().getFluidAmount() > 0) tryPushFluid(getBlockPos().below(), Direction.UP);

        for (Direction side : Direction.values()) {
            BlockPos adjacent = getBlockPos().relative(side);
            IAdvantageFluidHandler other = FluidHandlerHelper.getHandler(
                    getLevel().getBlockEntity(adjacent), side.getOpposite());
            if (other == null) continue;
            for (FluidTankInfo info : other.getTankInfo(side.getOpposite())) {
                FluidStack available = info.fluid;
                if (available == null || (getTank().getFluid() != null
                        && !getTank().getFluid().getFluid().isSame(available.getFluid()))) continue;
                FluidStack drained = other.drain(side.getOpposite(), getTank().getCapacity() - getTank().getFluidAmount(), false);
                if (drained == null) continue;
                int accepted = getTank().fill(drained, false);
                if (accepted > 0) {
                    FluidStack moved = other.drain(side.getOpposite(), accepted, true);
                    if (moved != null) getTank().fill(moved, true);
                    super.powerUpdate();
                    return;
                }
            }
        }

        if (getTank().getFluidAmount() == 0) {
            BlockPos source = scanFluidSpaceForSourceBlock(getLevel(), getBlockPos().above(), 32);
            if (source != null) {
                Fluid fluid = pickupSource(getLevel(), source, getLevel().getBlockState(source));
                if (fluid != net.minecraft.world.level.material.Fluids.EMPTY)
                    getTank().fill(new FluidStack(fluid, FluidTank.BUCKET_VOLUME), true);
            }
        }
        super.powerUpdate();
    }

    static Fluid pickupSource(LevelAccessor world, BlockPos position, BlockState state) {
        if (!(state.getBlock() instanceof BucketPickup)) return net.minecraft.world.level.material.Fluids.EMPTY;
        Fluid fluid = state.getFluidState().getType();
        ItemStack bucket = ((BucketPickup)state.getBlock()).pickupBlock(null, world, position, state);
        return bucket.isEmpty() ? net.minecraft.world.level.material.Fluids.EMPTY : fluid;
    }

    private void tryPushFluid(BlockPos position, Direction face) {
        IAdvantageFluidHandler handler = FluidHandlerHelper.getHandler(getLevel().getBlockEntity(position), face);
        if (handler == null || getTank().getFluid() == null) return;
        int accepted = handler.fill(face, getTank().getFluid(), true);
        if (accepted > 0) {
            getTank().drain(accepted, true);
            sync();
        }
    }

    public FluidStack getFluid() { return getTank().getFluid(); }
    public int getFluidCapacity() { return getTank().getCapacity(); }
    public int getRedstoneOutput() { return getTank().getFluidAmount() * 15 / getTank().getCapacity(); }
    @Override public boolean canAccept(Fluid fluid) { return true; }
    @Override protected ItemStack[] getInventory() { return new ItemStack[0]; }
    @Override public void tickUpdate(boolean server) {}
    @Override public boolean isPowerSink(ConduitType type) { return false; }
    @Override public boolean isPowerSource(ConduitType type) { return true; }
    @Override public boolean isFluidSource() { return true; }
    @Override public boolean isFluidSink() { return false; }

    public static BlockPos scanFluidSpaceForSourceBlock(Level world, BlockPos initial, int range) {
        BlockPos position = initial;
        Direction[] directions = { Direction.UP, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST };
        while (range-- > 0) {
            FluidState state = world.getFluidState(position);
            if (!state.isEmpty() && state.isSource()
                    && world.getBlockState(position).getBlock() instanceof BucketPickup) return position;
            java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
            for (Direction direction : directions) {
                BlockPos candidate = position.relative(direction);
                FluidState candidateState = world.getFluidState(candidate);
                if (!candidateState.isEmpty() && (state.isEmpty()
                        || candidateState.getType().isSame(state.getType()))) candidates.add(candidate);
            }
            if (candidates.isEmpty()) return null;
            position = candidates.get(candidates.size() == 1 ? 0 : world.getRandom().nextInt(candidates.size()));
        }
        return null;
    }

    public static boolean isFluidBlock(net.minecraft.world.level.block.Block block, Fluid fluid) {
        return block.defaultBlockState().getFluidState().getType().isSame(fluid);
    }

    public static boolean isFluidSourceBlock(net.minecraft.world.level.block.Block block, Fluid fluid, Level world, BlockPos pos) {
        FluidState state = world.getFluidState(pos);
        return !state.isEmpty() && state.isSource() && state.getType().isSame(fluid);
    }
}
