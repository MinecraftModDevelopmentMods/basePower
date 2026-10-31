package zone.moddev.mc.poweradvantage.util;

import java.lang.reflect.Constructor;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.poweradvantage.machines.fluidmachines.MetalTankTileEntity;
import static org.junit.Assert.*;

public class MachineCapabilitiesLifecycleTest {
    @BeforeClass public static void nativeCapabilityTokens() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        assertNotNull(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY);
        assertNotNull(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY);
        assertNotNull(CapabilityEnergy.ENERGY);
    }

    @Test public void chunkUnloadCannotRecreateAdaptersOnAnUnremovedTile() {
        TestTank tank = new TestTank();
        LazyOptional<IFluidHandler> fluid = tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, Direction.UP);
        LazyOptional<IItemHandler> item = tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.DOWN);
        assertTrue(fluid.isPresent());
        assertTrue(item.isPresent());
        tank.onChunkUnloaded();
        assertFalse(tank.isRemoved());
        assertFalse(fluid.isPresent());
        assertFalse(item.isPresent());
        assertFalse(tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, Direction.UP).isPresent());
        assertFalse(tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.DOWN).isPresent());
    }

    @Test public void explicitInvalidationBlocksEverySidedAndUnsidedRequest() {
        TestTank tank = new TestTank();
        tank.invalidateNow();
        for (Direction face : Direction.values()) {
            assertFalse(tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face).isPresent());
            assertFalse(tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face).isPresent());
        }
        assertFalse(tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null).isPresent());
        assertFalse(tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null).isPresent());
    }

    @Test public void clearRemovedCreatesFreshOptionalsWithoutRevivingOldOnes() {
        TestTank tank = new TestTank();
        tank.getTank().setFluid(new cyano.poweradvantage.api.fluid.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 500));
        LazyOptional<IFluidHandler> old = tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        tank.onChunkUnloaded();
        tank.clearRemoved();
        LazyOptional<IFluidHandler> current = tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        assertTrue(current.isPresent());
        assertNotSame(old, current);
        assertFalse(old.isPresent());
        assertEquals(500, tank.getTank().getFluidAmount());
    }

    @Test public void invalidationListenersCannotReacquireAnotherAdapter() {
        TestTank tank = new TestTank();
        LazyOptional<IFluidHandler> fluid = tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        AtomicBoolean called = new AtomicBoolean(), reacquired = new AtomicBoolean();
        fluid.addListener(ignored -> {
            called.set(true);
            reacquired.set(tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null).isPresent());
        });
        tank.onChunkUnloaded();
        assertTrue(called.get());
        assertFalse(reacquired.get());
    }

    @Test public void removedTilesStillRefuseCapabilitiesUntilCleared() {
        TestTank tank = new TestTank();
        tank.setRemoved();
        tank.reviveNow();
        assertFalse(tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null).isPresent());
        assertFalse(tank.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null).isPresent());
        tank.clearRemoved();
        assertTrue(tank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null).isPresent());
    }

    @Test public void conveyorsFollowTheSameUnloadAndRevivalContract() {
        zone.moddev.mc.poweradvantage.machines.conveyors.TileEntityConveyor conveyor =
                new zone.moddev.mc.poweradvantage.machines.conveyors.TileEntityConveyor(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState());
        LazyOptional<IItemHandler> old = conveyor.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        assertTrue(old.isPresent());
        conveyor.onChunkUnloaded();
        assertFalse(conveyor.isRemoved());
        assertFalse(old.isPresent());
        assertFalse(conveyor.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null).isPresent());
        conveyor.clearRemoved();
        LazyOptional<IItemHandler> current = conveyor.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        assertTrue(current.isPresent());
        assertNotSame(old, current);
    }

    @Test public void explicitNativeRevivalRestoresConverterEnergyWithoutLosingItsBuffer() {
        TestConverter converter = new TestConverter();
        converter.receiveEnergy(200, false);
        LazyOptional<IEnergyStorage> old = converter.getCapability(CapabilityEnergy.ENERGY, null);
        assertTrue(old.isPresent());
        converter.onChunkUnloaded();
        assertFalse(old.isPresent());
        assertFalse(converter.getCapability(CapabilityEnergy.ENERGY, null).isPresent());
        converter.reviveNow();
        LazyOptional<IEnergyStorage> current = converter.getCapability(CapabilityEnergy.ENERGY, null);
        assertTrue(current.isPresent());
        assertNotSame(old, current);
        assertEquals(200, converter.getEnergyStored());
    }

    @Test public void converterEnergyIsUnavailableDuringOtherAdapterNotifications() {
        TestConverter converter = new TestConverter();
        LazyOptional<IItemHandler> item = converter.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        AtomicBoolean called = new AtomicBoolean(), reacquired = new AtomicBoolean();
        item.addListener(ignored -> {
            called.set(true);
            reacquired.set(converter.getCapability(CapabilityEnergy.ENERGY, null).isPresent());
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
        TestConverter() { super(null, net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(), new cyano.poweradvantage.api.ConduitType("test-power"), 100, 1000, "test"); }
        void reviveNow() { reviveCaps(); }
    }
}
