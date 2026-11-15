package zone.moddev.mc.basepower.util;

import cyano.poweradvantage.api.modsupport.rf.TileEntityRFConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basepower.init.Blocks;
import zone.moddev.mc.basepower.init.Entities;
import zone.moddev.mc.basepower.init.ModSupport;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;
import zone.moddev.mc.basepower.machines.fluidmachines.MetalTankTileEntity;
import static org.junit.Assert.*;

/** Adapter-unit witnesses; NativeInteropProbe separately proves placed-world capability caches. */
public class MachineCapabilitiesLifecycleTest {
    @BeforeClass public static void requireNativeModRegistration() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertNotNull(Capabilities.FluidHandler.BLOCK);
        assertNotNull(Capabilities.ItemHandler.BLOCK);
        assertNotNull(Capabilities.EnergyStorage.BLOCK);
        assertNotSame((Object) Capabilities.FluidHandler.BLOCK, Capabilities.ItemHandler.BLOCK);
        assertNotNull("Run through ModDevGradle unitTest with the actual basePower mod", Entities.METAL_TANK);
        assertNotNull(Entities.CONVEYOR);
        assertNotNull(Entities.RF_STEAM_CONVERTER);
    }

    @Test public void adaptersAreStablePerSideAndSeparateAcrossOwners() {
        MetalTankTileEntity tank = tank();
        MachineCapabilities caps = tank.machineCapabilities();
        assertTrue(caps.isValid());
        IFluidHandler up = caps.fluids(Direction.UP);
        IItemHandler down = caps.items(Direction.DOWN);
        assertNotNull(up);
        assertNotNull(down);
        assertSame(up, caps.fluids(Direction.UP));
        assertSame(down, caps.items(Direction.DOWN));
        assertNotSame(up, caps.fluids(Direction.DOWN));
        assertNotSame(down, caps.items(Direction.UP));
        assertSame(caps.fluids(null), caps.fluids(null));
        assertSame(caps.items(null), caps.items(null));
        assertNotSame(up, tank().machineCapabilities().fluids(Direction.UP));
        assertNotSame(down, tank().machineCapabilities().items(Direction.DOWN));
    }

    @Test public void explicitInvalidationBlocksEverySidedAndUnsidedRequest() {
        MetalTankTileEntity tank = tank();
        assertNotNull(tank.machineCapabilities().fluids(null));
        assertNotNull(tank.machineCapabilities().items(null));
        tank.invalidateCaps();
        assertClosed(tank.machineCapabilities());
        assertFalse(tank.isRemoved());
    }

    @Test public void chunkUnloadCannotRecreateAdaptersOnAnUnremovedTile() {
        MetalTankTileEntity tank = tank();
        assertNotNull(tank.machineCapabilities().fluids(Direction.UP));
        assertNotNull(tank.machineCapabilities().items(Direction.DOWN));
        tank.onChunkUnloaded();
        assertFalse(tank.isRemoved());
        assertClosed(tank.machineCapabilities());
    }

    @Test public void clearRemovedCreatesFreshAdaptersWithoutChangingNativePayloadOrInventory() {
        MetalTankTileEntity tank = tank();
        MachineCapabilities caps = tank.machineCapabilities();
        FluidStack offered = payload(750);
        Tag original = offered.save(registries());
        IFluidHandler oldFluid = caps.fluids(null);
        IItemHandler oldItems = caps.items(null);
        assertEquals(750, oldFluid.fill(offered, FluidAction.EXECUTE));
        tank.setItem(0, new ItemStack(Items.WATER_BUCKET));
        CompoundTag internal = tank.getTank().writeToNBT(new CompoundTag());
        Tag inventory = tank.getItem(0).save(registries());
        tank.onChunkUnloaded();
        assertClosed(caps);
        tank.clearRemoved();
        assertTrue(caps.isValid());
        IFluidHandler currentFluid = caps.fluids(null);
        IItemHandler currentItems = caps.items(null);
        assertNotNull(currentFluid);
        assertNotNull(currentItems);
        assertNotSame(oldFluid, currentFluid);
        assertNotSame(oldItems, currentItems);
        assertEquals(internal, tank.getTank().writeToNBT(new CompoundTag()));
        assertEquals(original, currentFluid.getFluidInTank(0).save(registries()));
        assertEquals(inventory, currentItems.getStackInSlot(0).save(registries()));
        assertEquals(original, offered.save(registries()));
    }

    @Test public void repeatedInvalidationCannotReacquireAnotherAdapterBeforeRevival() {
        MachineCapabilities caps = tank().machineCapabilities();
        IFluidHandler oldFluid = caps.fluids(null);
        IItemHandler oldItems = caps.items(null);
        caps.invalidate();
        assertClosed(caps);
        caps.invalidate();
        assertClosed(caps);
        caps.revive();
        assertTrue(caps.isValid());
        assertNotSame(oldFluid, caps.fluids(null));
        assertNotSame(oldItems, caps.items(null));
    }

    @Test public void removedTilesStillRefuseAdaptersUntilCleared() {
        MetalTankTileEntity tank = tank();
        IFluidHandler oldFluid = tank.machineCapabilities().fluids(null);
        IItemHandler oldItems = tank.machineCapabilities().items(null);
        tank.setRemoved();
        tank.reviveCaps();
        assertTrue(tank.isRemoved());
        assertUnavailable(tank.machineCapabilities());
        tank.clearRemoved();
        assertFalse(tank.isRemoved());
        assertNotNull(tank.machineCapabilities().fluids(null));
        assertNotNull(tank.machineCapabilities().items(null));
        assertNotSame(oldFluid, tank.machineCapabilities().fluids(null));
        assertNotSame(oldItems, tank.machineCapabilities().items(null));
    }

    @Test public void conveyorsKeepExactItemsAcrossUnloadRevivalAndSimulatedTransfers() {
        TileEntityConveyor conveyor = Entities.CONVEYOR.create(BlockPos.ZERO, Blocks.item_conveyor.defaultBlockState());
        assertNotNull(conveyor);
        MachineCapabilities caps = conveyor.machineCapabilities();
        IItemHandler old = caps.items(null);
        assertNotNull(old);
        ItemStack offered = new ItemStack(Items.DIRT, 32);
        assertTrue(old.insertItem(0, offered, true).isEmpty());
        assertTrue(conveyor.isEmpty());
        assertEquals(32, offered.getCount());
        assertTrue(old.insertItem(0, offered, false).isEmpty());
        assertEquals(32, offered.getCount());
        assertEquals(32, conveyor.getItem(0).getCount());
        conveyor.onChunkUnloaded();
        assertFalse(conveyor.isRemoved());
        assertClosed(caps);
        conveyor.clearRemoved();
        IItemHandler current = caps.items(null);
        assertNotNull(current);
        assertNotSame(old, current);
        assertEquals(32, current.getStackInSlot(0).getCount());
        ItemStack preview = current.extractItem(0, 7, true);
        assertTrue(preview.is(Items.DIRT));
        assertEquals(7, preview.getCount());
        assertEquals(32, current.getStackInSlot(0).getCount());
        ItemStack extracted = current.extractItem(0, 7, false);
        assertTrue(extracted.is(Items.DIRT));
        assertEquals(7, extracted.getCount());
        assertEquals(25, conveyor.getItem(0).getCount());
        assertEquals(32, extracted.getCount() + conveyor.getItem(0).getCount());
    }

    @Test public void explicitRevivalConservesConverterEnergyAndSimulationDoesNotChangeItsBuffer() {
        TileEntityRFConverter converter = converter();
        assertEquals(200, converter.receiveEnergy(200, true));
        assertEquals(0, converter.getEnergyStored());
        assertEquals(200, converter.receiveEnergy(200, false));
        IItemHandler old = converter.machineCapabilities().items(null);
        assertNotNull(old);
        converter.invalidateCaps();
        assertClosed(converter.machineCapabilities());
        assertEquals(200, converter.getEnergyStored());
        converter.reviveCaps();
        assertTrue(converter.machineCapabilities().isValid());
        assertNotNull(converter.machineCapabilities().items(null));
        assertNotSame(old, converter.machineCapabilities().items(null));
        assertEquals(75, converter.extractEnergy(75, true));
        assertEquals(200, converter.getEnergyStored());
        assertEquals(75, converter.extractEnergy(75, false));
        assertEquals(125, converter.getEnergyStored());
        assertEquals(200, 75 + converter.getEnergyStored());
    }

    @Test public void converterUnloadClosesItsAdaptersWithoutLosingItsEnergy() {
        TileEntityRFConverter converter = converter();
        assertEquals(200, converter.receiveEnergy(200, false));
        assertNotNull(converter.machineCapabilities().items(null));
        converter.onChunkUnloaded();
        assertFalse(converter.isRemoved());
        assertClosed(converter.machineCapabilities());
        assertEquals(200, converter.getEnergyStored());
        converter.reviveCaps();
        assertTrue(converter.machineCapabilities().isValid());
        assertNotNull(converter.machineCapabilities().items(null));
        assertEquals(200, converter.getEnergyStored());
    }

    @Test public void nativePayloadRoundTripPreservesEveryComponentAndMakesIndependentCopies() {
        FluidStack offered = payload(700);
        Tag original = offered.save(registries());
        cyano.poweradvantage.api.fluid.FluidStack legacy = ForgeFluidAdapter.fromForge(offered);
        assertNotNull(legacy);
        assertNotNull(legacy.tag);
        CompoundTag expectedCustomData = offered.get(DataComponents.CUSTOM_DATA).copyTag();
        for (String key : expectedCustomData.getAllKeys()) {
            assertEquals("Legacy custom data must survive: " + key, expectedCustomData.get(key), legacy.tag.get(key));
        }
        FluidStack returned = ForgeFluidAdapter.toForge(legacy);
        assertNotSame(offered, returned);
        assertEquals(original, returned.save(registries()));
        CompoundTag changed = returned.get(DataComponents.CUSTOM_DATA).copyTag();
        changed.getCompound("details").putInt("batch", 8);
        returned.set(DataComponents.CUSTOM_DATA, CustomData.of(changed));
        returned.setAmount(1);
        assertEquals(original, offered.save(registries()));
        assertEquals(original, ForgeFluidAdapter.toForge(legacy).save(registries()));
        assertEquals(700, legacy.amount);
    }

    @Test public void legacyTagsSurviveTheNativePayloadEnvelopeWithoutAssumingTheOldRepresentation() {
        cyano.poweradvantage.api.fluid.FluidStack source =
                new cyano.poweradvantage.api.fluid.FluidStack(Fluids.WATER, 500);
        source.tag = customData();
        CompoundTag original = source.tag.copy();
        FluidStack nativeStack = ForgeFluidAdapter.toForge(source);
        assertEquals(original, nativeStack.get(DataComponents.CUSTOM_DATA).copyTag());
        cyano.poweradvantage.api.fluid.FluidStack returned = ForgeFluidAdapter.fromForge(nativeStack);
        assertEquals(nativeStack.save(registries()), ForgeFluidAdapter.toForge(returned).save(registries()));
        returned.tag.getCompound("basepower:neoforge_fluid").putInt("amount", 1);
        assertEquals(original, source.tag);
        assertEquals(500, nativeStack.getAmount());
        assertEquals(original, nativeStack.get(DataComponents.CUSTOM_DATA).copyTag());
    }

    @Test public void partialNativeRequestsConserveComponentsAndDoNotTreatAmountAsFluidIdentity() {
        MetalTankTileEntity tank = tank();
        IFluidHandler adapter = tank.machineCapabilities().fluids(null);
        FluidStack offered = payload(750);
        Tag original = offered.save(registries());
        assertEquals(750, adapter.fill(offered, FluidAction.SIMULATE));
        assertEquals(0, tank.getTank().getFluidAmount());
        assertEquals(750, adapter.fill(offered, FluidAction.EXECUTE));
        FluidStack request = offered.copy();
        request.setAmount(250);
        Tag requested = request.save(registries());
        FluidStack preview = adapter.drain(request, FluidAction.SIMULATE);
        assertEquals(requested, preview.save(registries()));
        assertEquals(750, tank.getTank().getFluidAmount());
        FluidStack extracted = adapter.drain(request, FluidAction.EXECUTE);
        assertEquals(requested, extracted.save(registries()));
        assertEquals(500, tank.getTank().getFluidAmount());
        assertEquals(750, extracted.getAmount() + tank.getTank().getFluidAmount());
        FluidStack remaining = offered.copy();
        remaining.setAmount(500);
        assertEquals(remaining.save(registries()), adapter.getFluidInTank(0).save(registries()));
        assertEquals(original, offered.save(registries()));
        assertEquals(requested, request.save(registries()));
        tank.invalidateCaps();
        assertClosed(tank.machineCapabilities());
        tank.reviveCaps();
        IFluidHandler revived = tank.machineCapabilities().fluids(null);
        assertNotSame(adapter, revived);
        assertEquals(remaining.save(registries()), revived.getFluidInTank(0).save(registries()));
    }

    private static MetalTankTileEntity tank() {
        MetalTankTileEntity tank = Entities.METAL_TANK.create(BlockPos.ZERO, Blocks.metal_storage_tank.defaultBlockState());
        assertNotNull(tank);
        assertTrue(Entities.METAL_TANK.isValid(tank.getBlockState()));
        return tank;
    }

    private static TileEntityRFConverter converter() {
        TileEntityRFConverter converter = Entities.RF_STEAM_CONVERTER.create(
                BlockPos.ZERO, ModSupport.converter_rf_steam.defaultBlockState());
        assertNotNull(converter);
        return converter;
    }

    private static RegistryAccess.Frozen registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static CompoundTag customData() {
        CompoundTag data = new CompoundTag();
        data.putString("Owner", "native-lifecycle");
        CompoundTag details = new CompoundTag();
        details.putInt("batch", 7);
        details.putIntArray("sequence", new int[] {3, 5, 8});
        data.put("details", details);
        return data;
    }

    private static FluidStack payload(int amount) {
        FluidStack stack = new FluidStack(Fluids.WATER, amount);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData()));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Lifecycle water"));
        return stack;
    }

    private static void assertClosed(MachineCapabilities caps) {
        assertFalse("Invalidated adapters must not become available again without revival", caps.isValid());
        assertUnavailable(caps);
    }

    private static void assertUnavailable(MachineCapabilities caps) {
        for (Direction face : Direction.values()) {
            assertNull(caps.fluids(face));
            assertNull(caps.items(face));
        }
        assertNull(caps.fluids(null));
        assertNull(caps.items(null));
    }
}
