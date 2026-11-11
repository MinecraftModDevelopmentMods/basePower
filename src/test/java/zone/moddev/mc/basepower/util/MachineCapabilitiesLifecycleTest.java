package zone.moddev.mc.basepower.util;

import java.lang.reflect.Constructor;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.energy.IEnergyStorage;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basepower.machines.fluidmachines.MetalTankTileEntity;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;
import zone.moddev.mc.basepower.init.Entities;
import static org.junit.Assert.*;

public class MachineCapabilitiesLifecycleTest {
    private static BlockEntityType<MetalTankTileEntity> previousTank;
    private static BlockEntityType<TileEntityConveyor> previousConveyor;

    @BeforeClass public static void nativeCapabilityTokens() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        assertNotNull(ForgeCapabilities.FLUID_HANDLER);
        assertNotNull(ForgeCapabilities.ITEM_HANDLER);
        assertNotNull(ForgeCapabilities.ENERGY);
        previousTank = Entities.METAL_TANK;
        previousConveyor = Entities.CONVEYOR;
        Entities.METAL_TANK = BlockEntityType.Builder.of(MetalTankTileEntity::new, Blocks.IRON_BLOCK).build(null);
        Entities.CONVEYOR = BlockEntityType.Builder.of(TileEntityConveyor::new, Blocks.HOPPER).build(null);
    }

    @AfterClass public static void restoreNativeTypes() {
        Entities.METAL_TANK = previousTank;
        Entities.CONVEYOR = previousConveyor;
    }

    @Test public void chunkUnloadCannotRecreateAdaptersOnAnUnremovedTile() {
        TestTank tank = new TestTank();
        LazyOptional<IFluidHandler> fluid = tank.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP);
        LazyOptional<IItemHandler> item = tank.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN);
        assertTrue(fluid.isPresent());
        assertTrue(item.isPresent());
        tank.onChunkUnloaded();
        assertFalse(tank.isRemoved());
        assertFalse(fluid.isPresent());
        assertFalse(item.isPresent());
        assertFalse(tank.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP).isPresent());
        assertFalse(tank.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).isPresent());
    }

    @Test public void explicitInvalidationBlocksEverySidedAndUnsidedRequest() {
        TestTank tank = new TestTank();
        tank.invalidateNow();
        for (Direction face : Direction.values()) {
            assertFalse(tank.getCapability(ForgeCapabilities.FLUID_HANDLER, face).isPresent());
            assertFalse(tank.getCapability(ForgeCapabilities.ITEM_HANDLER, face).isPresent());
        }
        assertFalse(tank.getCapability(ForgeCapabilities.FLUID_HANDLER, null).isPresent());
        assertFalse(tank.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent());
    }

    @Test public void clearRemovedCreatesFreshOptionalsWithoutRevivingOldOnes() {
        TestTank tank = new TestTank();
        tank.getTank().setFluid(new cyano.poweradvantage.api.fluid.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 500));
        LazyOptional<IFluidHandler> old = tank.getCapability(ForgeCapabilities.FLUID_HANDLER, null);
        tank.onChunkUnloaded();
        tank.clearRemoved();
        LazyOptional<IFluidHandler> current = tank.getCapability(ForgeCapabilities.FLUID_HANDLER, null);
        assertTrue(current.isPresent());
        assertNotSame(old, current);
        assertFalse(old.isPresent());
        assertEquals(500, tank.getTank().getFluidAmount());
    }

    @Test public void invalidationListenersCannotReacquireAnotherAdapter() {
        TestTank tank = new TestTank();
        LazyOptional<IFluidHandler> fluid = tank.getCapability(ForgeCapabilities.FLUID_HANDLER, null);
        tank.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        AtomicBoolean called = new AtomicBoolean(), reacquired = new AtomicBoolean();
        fluid.addListener(ignored -> {
            called.set(true);
            reacquired.set(tank.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent());
        });
        tank.onChunkUnloaded();
        assertTrue(called.get());
        assertFalse(reacquired.get());
    }

    @Test public void removedTilesStillRefuseCapabilitiesUntilCleared() {
        TestTank tank = new TestTank();
        tank.setRemoved();
        tank.reviveNow();
        assertFalse(tank.getCapability(ForgeCapabilities.FLUID_HANDLER, null).isPresent());
        assertFalse(tank.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent());
        tank.clearRemoved();
        assertTrue(tank.getCapability(ForgeCapabilities.FLUID_HANDLER, null).isPresent());
    }

    @Test public void conveyorsFollowTheSameUnloadAndRevivalContract() {
        zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor conveyor =
                new zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState());
        LazyOptional<IItemHandler> old = conveyor.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        assertTrue(old.isPresent());
        conveyor.onChunkUnloaded();
        assertFalse(conveyor.isRemoved());
        assertFalse(old.isPresent());
        assertFalse(conveyor.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent());
        conveyor.clearRemoved();
        LazyOptional<IItemHandler> current = conveyor.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        assertTrue(current.isPresent());
        assertNotSame(old, current);
    }

    @Test public void explicitNativeRevivalRestoresConverterEnergyWithoutLosingItsBuffer() {
        TestConverter converter = new TestConverter();
        converter.receiveEnergy(200, false);
        LazyOptional<IEnergyStorage> old = converter.getCapability(ForgeCapabilities.ENERGY, null);
        assertTrue(old.isPresent());
        converter.onChunkUnloaded();
        assertFalse(old.isPresent());
        assertFalse(converter.getCapability(ForgeCapabilities.ENERGY, null).isPresent());
        converter.reviveNow();
        LazyOptional<IEnergyStorage> current = converter.getCapability(ForgeCapabilities.ENERGY, null);
        assertTrue(current.isPresent());
        assertNotSame(old, current);
        assertEquals(200, converter.getEnergyStored());
    }

    @Test public void converterEnergyIsUnavailableDuringOtherAdapterNotifications() {
        TestConverter converter = new TestConverter();
        LazyOptional<IItemHandler> item = converter.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        AtomicBoolean called = new AtomicBoolean(), reacquired = new AtomicBoolean();
        item.addListener(ignored -> {
            called.set(true);
            reacquired.set(converter.getCapability(ForgeCapabilities.ENERGY, null).isPresent());
        });
        converter.onChunkUnloaded();
        assertTrue(called.get());
        assertFalse(reacquired.get());
    }

    private static final class TestTank extends MetalTankTileEntity {
        TestTank() { super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState()); }
        void invalidateNow() { invalidateCaps(); }
        void reviveNow() { reviveCaps(); }
    }

    private static final class TestConverter extends cyano.poweradvantage.api.modsupport.rf.TileEntityRFConverter {
        private static final BlockEntityType<TestConverter> TYPE =
                BlockEntityType.Builder.of(TestConverter::new, Blocks.IRON_BLOCK).build(null);
        TestConverter() { this(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState()); }
        TestConverter(BlockPos pos, BlockState state) {
            super(TYPE, pos, state, new cyano.poweradvantage.api.ConduitType("test-power"), 100, 1000, "test");
        }
        void reviveNow() { reviveCaps(); }
    }
}
