package zone.moddev.mc.basepower.machines.conveyors;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Direction;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basepower.util.InventoryWrapper;
import static org.junit.Assert.*;

public class ConveyorTransferTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void laterEmptySlotRemainsAnAvailableDestination() {
        SimpleContainer destination = new SimpleContainer(2);
        destination.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        assertTrue(TileEntityConveyor.canInsertItemInto(new ItemStack(Items.DIRT), InventoryWrapper.wrap(destination), Direction.UP));
    }

    @Test public void fullStackIsNotAnAvailableDestination() {
        SimpleContainer destination = new SimpleContainer(1);
        destination.setItem(0, new ItemStack(Items.DIRT, 64));
        assertFalse(TileEntityConveyor.canInsertItemInto(new ItemStack(Items.DIRT), InventoryWrapper.wrap(destination), Direction.UP));
    }

    @Test public void transfersPreserveDifferentCustomItemData() {
        SimpleContainer source = new SimpleContainer(2), target = new SimpleContainer(2);
        source.setItem(0, tagged(3, "A")); source.setItem(1, tagged(3, "B")); target.setItem(0, tagged(1, "A"));
        for (int n = 0; n < 6; n++) assertTrue(TileEntityConveyor.transferItem(InventoryWrapper.wrap(source), Direction.EAST,
                InventoryWrapper.wrap(target), Direction.WEST));
        assertTrue(source.isEmpty());
        assertEquals(4, target.getItem(0).getCount()); assertEquals("A", zone.moddev.mc.basepower.util.ItemData.customData(target.getItem(0)).getString("Witness").orElseThrow());
        assertEquals(3, target.getItem(1).getCount()); assertEquals("B", zone.moddev.mc.basepower.util.ItemData.customData(target.getItem(1)).getString("Witness").orElseThrow());
    }

    private static ItemStack tagged(int count, String value) {
        ItemStack stack = new ItemStack(Items.DIRT, count);
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                stack, tag -> tag.putString("Witness", value));
        return stack;
    }
}
