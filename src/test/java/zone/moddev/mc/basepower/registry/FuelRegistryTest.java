package zone.moddev.mc.basepower.registry;

import java.util.function.Function;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class FuelRegistryTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void lastOrdinaryFuelItemLeavesAnEmptySlot() {
        assertTrue(FuelRegistry.decrementFuelItem(new ItemStack(Items.COAL)).isEmpty());
    }

    @Test public void nativeCookingComponentsRetainTheirExactConfiguredBurnTime() {
        var fuel = new net.minecraft.world.item.component.CookingFuel(
                new net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt.Constant(937),
                new net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat.Constant(1F));
        assertEquals(937, FuelRegistry.nativeBurnTime(-1, fuel, null));
        assertEquals(0, FuelRegistry.nativeBurnTime(-1, null, null));
        assertEquals(237, FuelRegistry.nativeBurnTime(237, fuel, null));
        assertEquals("An explicit item override can disable a native fuel", 0, FuelRegistry.nativeBurnTime(0, fuel, null));
    }

    @Test public void dataDrivenFuelNeedsARealReloadedServerContext() {
        var fuel = new ItemStack(Items.COAL).get(net.minecraft.core.component.DataComponents.COOKING_FUEL);
        assertNotNull(fuel);
        assertTrue(fuel.burnTime() instanceof net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt.Reference);
        assertEquals(0, FuelRegistry.nativeBurnTime(-1, fuel, null));
    }

    @Test public void explicitFuelAlgorithmsStillTakePriorityOverNativeComponents() {
        var registry = FuelRegistry.getInstance();
        Function<ItemStack, Short> algorithm = stack -> (short)523;
        registry.registerFuel(Items.STICK, algorithm);
        try {
            assertEquals(523, FuelRegistry.getActualBurntimeForItem(new ItemStack(Items.STICK)));
        } finally {
            assertTrue(registry.getFuelAlgorithmsForItem(Items.STICK).remove(algorithm));
        }
    }

    @Test public void nativeComponentValuesStillPassThroughTheForgeFuelEvent() {
        var stack = new ItemStack(Items.COAL);
        var component = new net.minecraft.world.item.component.CookingFuel(
                new net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt.Constant(937),
                new net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat.Constant(1F));
        stack.set(net.minecraft.core.component.DataComponents.COOKING_FUEL, component);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var listener = net.minecraftforge.event.furnace.FurnaceFuelBurnTimeEvent.BUS.addListener(event -> {
            if (event.getItemStack() == stack) {
                assertEquals(937, event.getBurnTime());
                assertSame(net.minecraft.world.item.crafting.RecipeType.SMELTING, event.getRecipeType());
                calls.incrementAndGet();
                event.setBurnTime(222);
            }
        });
        try {
            assertEquals(222, FuelRegistry.getActualBurntimeForItem(stack));
            assertEquals(1, calls.get());
            assertSame(component, stack.get(net.minecraft.core.component.DataComponents.COOKING_FUEL));
        } finally {
            net.minecraftforge.event.furnace.FurnaceFuelBurnTimeEvent.BUS.removeListener(listener);
        }
    }

    @Test public void largerFuelStackConsumesExactlyOneItem() {
        ItemStack stack = new ItemStack(Items.COAL, 3);
        assertSame(stack, FuelRegistry.decrementFuelItem(stack));
        assertEquals(Items.COAL, stack.getItem());
        assertEquals(2, stack.getCount());
    }

    @Test public void lastLavaBucketReturnsExactlyOneEmptyBucket() {
        ItemStack stack = new ItemStack(Items.LAVA_BUCKET);
        assertNotNull("Native crafting remainder must exist before consumption", stack.getCraftingRemainder());
        ItemStack result = FuelRegistry.decrementFuelItem(stack);
        assertEquals(Items.BUCKET, result.getItem());
        assertEquals(1, result.getCount());
    }

    @Test public void nativeEmptyAndLegacyNullInputsKeepTheirMeaning() {
        assertSame(ItemStack.EMPTY, FuelRegistry.decrementFuelItem(ItemStack.EMPTY));
        assertNull(FuelRegistry.decrementFuelItem(null));
    }

    @Test public void explicitPostBurnAlgorithmsStillOverrideNativeRemainders() {
        Function<ItemStack, ItemStack> algorithm = stack -> {
            assertEquals(3, stack.getCount());
            return new ItemStack(Items.CHARCOAL, 2);
        };
        FuelRegistry registry = FuelRegistry.getInstance();
        registry.registerPostBurnItem(Items.STICK, algorithm);
        try {
            ItemStack result = FuelRegistry.decrementFuelItem(new ItemStack(Items.STICK, 3));
            assertEquals(Items.CHARCOAL, result.getItem());
            assertEquals(2, result.getCount());
        } finally {
            assertTrue(registry.getPostBurnAlgorithmsForItem(Items.STICK).remove(algorithm));
        }
    }
}
