package zone.moddev.mc.basepower.util;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.Nameable;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/** Gives ordinary inventories the sided interface used by conveyors. */
public class InventoryWrapper implements WorldlyContainer, Nameable {
    private static final int[] NO_SLOTS = new int[0];
    private static final InventoryWrapper NULL_INVENTORY = new InventoryWrapper();
    private final Container inventory;
    private final int[] slots;

    protected InventoryWrapper() { this(new SimpleContainer(0)); }

    protected InventoryWrapper(Container inventory) {
        this.inventory = inventory;
        slots = new int[inventory.getContainerSize()];
        for (int i = 0; i < slots.length; i++) slots[i] = i;
    }

    public static WorldlyContainer wrap(Container inventory) {
        if (inventory == null) return NULL_INVENTORY;
        return inventory instanceof WorldlyContainer ? (WorldlyContainer) inventory : new InventoryWrapper(inventory);
    }

    public boolean isLocked() { return InventoryLocks.isLocked(inventory); }
    @Override public Component getName() {
        return inventory instanceof Nameable ? ((Nameable) inventory).getName() : new TextComponent("Inventory");
    }
    @Override public Component getCustomName() {
        return inventory instanceof Nameable ? ((Nameable) inventory).getCustomName() : null;
    }
    @Override public void clearContent() { inventory.clearContent(); }
    @Override public boolean isEmpty() { return inventory.isEmpty(); }
    @Override public int getContainerSize() { return inventory.getContainerSize(); }
    @Override public int getMaxStackSize() { return inventory.getMaxStackSize(); }
    @Override public ItemStack getItem(int slot) { return inventory.getItem(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { return inventory.removeItem(slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return inventory.removeItemNoUpdate(slot); }
    @Override public void setItem(int slot, ItemStack stack) { inventory.setItem(slot, stack); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return inventory.canPlaceItem(slot, stack); }
    @Override public boolean stillValid(Player player) {
        return slots.length != 0 && inventory.stillValid(player);
    }
    @Override public void startOpen(Player player) { inventory.startOpen(player); }
    @Override public void stopOpen(Player player) { inventory.stopOpen(player); }
    @Override public void setChanged() { inventory.setChanged(); }
    @Override public int[] getSlotsForFace(Direction face) { return isLocked() ? NO_SLOTS : slots; }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) {
        return !isLocked() && inventory.canPlaceItem(slot, stack);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) { return !isLocked(); }
}
