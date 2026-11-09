package zone.moddev.mc.basepower.machines.fluidmachines;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTank;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import cyano.poweradvantage.api.simple.TileEntitySimpleFluidMachine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import zone.moddev.mc.basepower.init.Entities;
import zone.moddev.mc.basepower.util.FluidHandlerHelper;

/** Places stored fluid into the world or into a tank below. */
public class FluidDischargeTileEntity extends TileEntitySimpleFluidMachine {
    private int oldLevel = -1;

    public FluidDischargeTileEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        super(Entities.FLUID_DISCHARGE, pos, state, FluidTank.BUCKET_VOLUME, "tile.basepower.fluid_discharge.name");
    }

    @Override
    public void powerUpdate() {
        BlockPos below = getBlockPos().below();
        IAdvantageFluidHandler other = FluidHandlerHelper.getHandler(getLevel().getBlockEntity(below), Direction.UP);
        if (getTank().getFluidAmount() > 0 && other != null) {
            int accepted = other.fill(Direction.UP, getTank().getFluid(), true);
            if (accepted > 0) getTank().drain(accepted, true);
        } else if (getTank().getFluidAmount() >= FluidTank.BUCKET_VOLUME) {
            Fluid fluid = getTank().getFluid().getFluid();
            BlockPos target = findPlacement(getLevel(), below, fluid, 32);
            if (target != null) {
                getLevel().setBlock(target, fluid.defaultFluidState().createLegacyBlock(), 3);
                getTank().drain(FluidTank.BUCKET_VOLUME, true);
            }
        }
        if (oldLevel != getTank().getFluidAmount()) {
            oldLevel = getTank().getFluidAmount();
            sync();
        }
        super.powerUpdate();
    }

    private static BlockPos findPlacement(Level world, BlockPos initial, Fluid fluid, int range) {
        BlockPos position = initial;
        Direction[] directions = { Direction.DOWN, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST };
        while (range-- > 0) {
            if (world.isEmptyBlock(position)) return position;
            FluidState state = world.getFluidState(position);
            if (state.isEmpty() || !state.getType().isSame(fluid)) return null;
            if (!state.isSource()) return position;
            java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
            for (Direction direction : directions) {
                BlockPos candidate = position.relative(direction);
                FluidState candidateState = world.getFluidState(candidate);
                if (world.isEmptyBlock(candidate) || (!candidateState.isEmpty()
                        && candidateState.getType().isSame(fluid) && !candidateState.isSource())) {
                    candidates.add(candidate);
                }
            }
            if (candidates.isEmpty()) return null;
            position = candidates.get(candidates.size() == 1 ? 0 : world.random.nextInt(candidates.size()));
        }
        return null;
    }

    @Override public void tickUpdate(boolean server) {}
    public FluidStack getFluid() { return getTank().getFluid(); }
    public int getFluidCapacity() { return getTank().getCapacity(); }
    @Override public boolean canAccept(Fluid fluid) { return true; }
    @Override protected ItemStack[] getInventory() { return new ItemStack[0]; }
    public int getRedstoneOutput() { return getTank().getFluidAmount() * 15 / getTank().getCapacity(); }
    @Override public boolean isPowerSink(ConduitType type) { return true; }
    @Override public boolean isPowerSource(ConduitType type) { return false; }
    @Override public boolean isFluidSource() { return false; }
    @Override public boolean isFluidSink() { return true; }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        getTank().writeToNBT(tag);
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag) { getTank().readFromNBT(tag); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return new ClientboundBlockEntityDataPacket(getBlockPos(), 0, getUpdateTag());
    }
    @Override public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        handleUpdateTag(packet.getTag());
    }

    public static BlockPos scanFluidSpaceForNonsourceBlock(Level world, BlockPos initial, Fluid fluid, int range) {
        return findPlacement(world, initial, fluid, range);
    }
    public static boolean isFluidBlock(net.minecraft.world.level.block.Block block, Fluid fluid) {
        return FluidDrainTileEntity.isFluidBlock(block, fluid);
    }
    public static boolean isFluidSourceBlock(net.minecraft.world.level.block.Block block, Fluid fluid, Level world, BlockPos pos) {
        return FluidDrainTileEntity.isFluidSourceBlock(block, fluid, world, pos);
    }
}
