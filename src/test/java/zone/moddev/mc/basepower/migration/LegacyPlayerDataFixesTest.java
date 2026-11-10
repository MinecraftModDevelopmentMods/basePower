package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyPlayerDataFixesTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void savedRegistryBookNamesSurviveBothPlayerInventories() {
        for (String inventory : new String[] {"Inventory", "EnderItems"}) {
            for (String name : new String[] {"minecraft:efficiency", "steamadvantage:steam_capacity"}) {
                CompoundTag book = enchanted("minecraft:enchanted_book", "StoredEnchantments", false);
                assertTrue(LegacyWorldDataHook.migrateLegacyEnchantments(book.getCompound("tag"),
                        Collections.singletonMap(32, name)));
                CompoundTag source = player(inventory, book), before = source.copy();
                CompoundTag result = convert(source, 512);
                CompoundTag actual = item(result, inventory, 0);
                assertStack("minecraft:enchanted_book", 1, actual);
                assertSlot(actual, 0);
                assertComponentEnchantment(actual, "minecraft:stored_enchantments", name, 3);
                assertEquals(before, source);
            }
        }
    }

    @Test public void namedOldEnchAndAlreadyModernEnchantmentsSurvive() {
        for (String key : new String[] {"ench", "Enchantments"}) {
            CompoundTag result = convert(player("Inventory", enchanted("minecraft:diamond_pickaxe", key, true)), 512);
            CompoundTag actual = item(result, "Inventory", 0);
            assertStack("minecraft:diamond_pickaxe", 1, actual);
            assertComponentEnchantment(actual, "minecraft:enchantments", "steamadvantage:steam_capacity", 3);
            assertEquals(17, actual.getCompound("components").getInt("minecraft:damage"));
        }
    }

    @Test public void numericEnchantmentsStillUseTheNativeMapping() {
        for (String key : new String[] {"ench", "StoredEnchantments"}) {
            CompoundTag result = convert(player("Inventory", enchanted("minecraft:enchanted_book", key, false)), 512);
            String component = key.equals("ench") ? "minecraft:enchantments" : "minecraft:stored_enchantments";
            CompoundTag actual = item(result, "Inventory", 0);
            assertStack("minecraft:enchanted_book", 1, actual);
            assertComponentEnchantment(actual, component, "minecraft:efficiency", 3);
        }
    }

    @Test public void nativeContainerBoundaryFixesNestedItemsExactlyOnce() {
        CompoundTag chest = stack("minecraft:chest", 0);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "Chest");
        ListTag contents = new ListTag();
        contents.add(enchanted("minecraft:diamond_pickaxe", "ench", true));
        CompoundTag wool = stack("minecraft:wool", 14);
        wool.putByte("Slot", (byte) 1);
        wool.putByte("Count", (byte) 7);
        contents.add(wool);
        CompoundTag book = enchanted("minecraft:enchanted_book", "StoredEnchantments", true);
        book.putByte("Slot", (byte) 2);
        contents.add(book);
        CompoundTag foreign = stack("other:component", 0);
        foreign.putByte("Slot", (byte) 7);
        foreign.putByte("Count", (byte) 3);
        CompoundTag privateData = new CompoundTag();
        privateData.put("Private", stack("minecraft:wool", 14));
        privateData.putIntArray("PrivateArray", new int[] {19, 23});
        foreign.put("tag", privateData);
        contents.add(foreign);
        blockEntity.put("Items", contents);
        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", blockEntity);
        chest.put("tag", tag);
        CompoundTag source = player("Inventory", chest), before = source.copy();
        CompoundTag result = convert(source, 512);
        CompoundTag fixedChest = item(result, "Inventory", 0);
        assertStack("minecraft:chest", 1, fixedChest);
        assertSlot(fixedChest, 0);
        ListTag fixed = fixedChest.getCompound("components").getList("minecraft:container", 10);
        assertEquals("Container slots: " + fixedChest, 4, fixed.size());
        CompoundTag pick = itemInContainerSlot(fixed, 0);
        assertStack("minecraft:diamond_pickaxe", 1, pick);
        assertEquals(17, pick.getCompound("components").getInt("minecraft:damage"));
        assertComponentEnchantment(pick, "minecraft:enchantments", "steamadvantage:steam_capacity", 3);
        assertStack("minecraft:red_wool", 7, itemInContainerSlot(fixed, 1));
        CompoundTag fixedBook = itemInContainerSlot(fixed, 2);
        assertStack("minecraft:enchanted_book", 1, fixedBook);
        assertComponentEnchantment(fixedBook, "minecraft:stored_enchantments", "steamadvantage:steam_capacity", 3);
        CompoundTag fixedForeign = itemInContainerSlot(fixed, 7);
        assertStack("other:component", 3, fixedForeign);
        assertEquals(privateData, fixedForeign.getCompound("components").getCompound("minecraft:custom_data"));
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        assertEquals(result, convert(result, current));
        assertEquals(before, source);
    }

    @Test public void foreignItemPayloadIsNotRestoredOverNativeChanges() {
        CompoundTag foreign = enchanted("other:component", "StoredEnchantments", true);
        foreign.getCompound("tag").putInt("Private", 19);
        CompoundTag source = player("Inventory", foreign);
        CompoundTag before = source.copy();
        CompoundTag actual = item(convert(source, 512), "Inventory", 0);
        assertStack("other:component", 1, actual);
        assertSlot(actual, 0);
        assertFalse("Native damage removal must not be undone by a whole-stack overlay", actual.contains("Damage"));
        CompoundTag components = actual.getCompound("components");
        assertFalse("Legacy subtype is not vanilla item wear", components.contains("minecraft:damage"));
        assertFalse("The non-book's private data must not become a stored enchantment",
                components.contains("minecraft:stored_enchantments"));
        assertEquals(item(before, "Inventory", 0).getCompound("tag"),
                components.getCompound("minecraft:custom_data"));
        assertEquals(before, source);
    }

    @Test public void restorationChangesOnlyTheSavedName() {
        CompoundTag source = player("Inventory", enchanted("other:component", "StoredEnchantments", true));
        RecordingFixer fixer = new RecordingFixer(root -> {
            CompoundTag book = item(root, "Inventory", 0);
            book.putString("id", "other:native_result");
            enchantment(book, "StoredEnchantments").putString("id", "minecraft:protection");
            enchantment(book, "StoredEnchantments").putShort("lvl", (short) 5);
            book.getCompound("tag").putInt("NativeField", 42);
        }, true);
        CompoundTag result = LegacyPlayerDataFixes.update(fixer, DataFixTypes.PLAYER, source, 704);
        CompoundTag book = item(result, "Inventory", 0);
        assertEquals("other:native_result", book.getString("id"));
        assertEquals(42, book.getCompound("tag").getInt("NativeField"));
        assertEquals(5, enchantment(book, "StoredEnchantments").getInt("lvl"));
        assertEquals("steamadvantage:steam_capacity", enchantment(book, "StoredEnchantments").getString("id"));
    }

    @Test public void sourceIsDetachedEvenWhenFixerMutatesNestedTagsAndArrays() {
        CompoundTag source = player("Inventory", stack("minecraft:stone", 0));
        source.putIntArray("PrivateArray", new int[] {1, 2});
        CompoundTag before = source.copy();
        RecordingFixer fixer = new RecordingFixer(root -> {
            root.getIntArray("PrivateArray")[0] = 99;
            item(root, "Inventory", 0).getCompound("tag").putInt("Native", 1);
            root.putString("Native", "changed");
        });
        CompoundTag result = LegacyPlayerDataFixes.update(fixer, DataFixTypes.PLAYER, source, 512);
        assertEquals(before, source);
        assertNotSame(source, result);
        item(result, "Inventory", 0).putInt("AfterReturn", 1);
        assertEquals(before, source);
    }

    @Test public void usesThePassedFixerAndNativePlayerTypeWithExplicitSchemaBoundaries() {
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        for (int source : new int[] {-1, 512, 703, 704, 1343, 2230, 3464, 3465, 3466}) {
            RecordingFixer fixer = new RecordingFixer(root -> {});
            LegacyPlayerDataFixes.update(fixer, DataFixTypes.PLAYER, player("Inventory", stack("minecraft:stone", 0)), source);
            List<String> expected = new ArrayList<>();
            if (source < 704) expected.add(source + "->704");
            if (source < 3465) expected.add(Math.max(source, 704) + "->3465");
            expected.add(Math.max(source, 3465) + "->" + current);
            assertEquals(expected, fixer.calls);
        }
    }

    @Test public void currentOrNewerDataIsCopiedWithoutOldVersionDfu() {
        CompoundTag modern = convert(player("Inventory", stack("minecraft:diamond_pickaxe", 17)), 512);
        for (int version : new int[] {SharedConstants.getCurrentVersion().getDataVersion().getVersion(), Integer.MAX_VALUE}) {
            CompoundTag source = modern.copy();
            source.putInt("DataVersion", version);
            RecordingFixer fixer = new RecordingFixer(root -> fail("Current data must not be reinterpreted"));
            CompoundTag result = LegacyPlayerDataFixes.update(fixer, DataFixTypes.PLAYER, source, version);
            assertEquals(source, result);
            assertNotSame(source, result);
            assertTrue(fixer.calls.isEmpty());
        }
    }

    @Test public void changedCountsFailClosed() {
        rejected(root -> item(root, "Inventory", 0).putByte("Count", (byte) 2));
    }

    @Test public void changedOrMissingSlotsFailClosed() {
        rejected(root -> item(root, "Inventory", 0).putByte("Slot", (byte) 1));
        rejected(root -> item(root, "Inventory", 0).remove("Slot"));
    }

    @Test public void changedInventoryCardinalityFailsClosed() {
        rejected(root -> root.getList("Inventory", 10).remove(0));
        rejected(root -> root.getList("Inventory", 10).add(stack("minecraft:stone", 0)));
    }

    @Test public void addingAnItemToAnEmptyInventoryFailsClosed() {
        for (String inventory : new String[] {"Inventory", "EnderItems"}) {
            CompoundTag source = new CompoundTag();
            source.put(inventory, new ListTag());
            try {
                LegacyPlayerDataFixes.update(new RecordingFixer(root -> root.getList(inventory, 10)
                        .add(stack("minecraft:stone", 0))), DataFixTypes.PLAYER, source, 704);
                fail("An empty inventory must not acquire an unpaired item");
            } catch (IllegalStateException expected) { assertTrue(source.getList(inventory, 10).isEmpty()); }
        }
    }

    @Test public void post704PlayerBooksRetainNames() {
        for (int version : new int[] {704, 1343, 1519, 2230}) {
            CompoundTag source = player("EnderItems", enchanted("minecraft:enchanted_book", "StoredEnchantments", true));
            CompoundTag result = convert(source, version);
            CompoundTag actual = item(result, "EnderItems", 0);
            assertStack("minecraft:enchanted_book", 1, actual);
            assertSlot(actual, 0);
            assertComponentEnchantment(actual, "minecraft:stored_enchantments", "steamadvantage:steam_capacity", 3);
        }
    }

    @Test public void missingOrReplacedInventoryFailsClosed() {
        rejected(root -> root.remove("Inventory"));
        rejected(root -> root.putString("Inventory", "not an inventory"));
    }

    @Test public void changedNamedEnchantmentCardinalityFailsClosed() {
        rejected(root -> item(root, "Inventory", 0).getCompound("tag").getList("StoredEnchantments", 10).remove(0));
    }

    @Test public void wrongNativeTypeIsRejected() {
        try {
            LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.CHUNK,
                    new CompoundTag(), 512);
            fail("The player-only wrapper must reject a different type");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("PLAYER")); }
    }

    private static void rejected(Consumer<CompoundTag> mutation) {
        CompoundTag source = player("Inventory", enchanted("minecraft:enchanted_book", "StoredEnchantments", true));
        CompoundTag before = source.copy();
        try {
            LegacyPlayerDataFixes.update(new RecordingFixer(mutation), DataFixTypes.PLAYER, source, 704);
            fail("An unpairable native inventory result must fail closed");
        } catch (IllegalStateException expected) { assertEquals(before, source); }
    }

    private static CompoundTag convert(CompoundTag source, int version) {
        return LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER, source, version);
    }

    static CompoundTag player(String inventory, CompoundTag item) {
        CompoundTag player = new CompoundTag();
        player.putInt("DataVersion", 512);
        ListTag items = new ListTag();
        items.add(item);
        player.put(inventory, items);
        return player;
    }

    static CompoundTag stack(String id, int damage) {
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putByte("Count", (byte) 1);
        item.putByte("Slot", (byte) 0);
        item.putShort("Damage", (short) damage);
        item.put("tag", new CompoundTag());
        return item;
    }

    static CompoundTag enchanted(String id, String key, boolean named) {
        CompoundTag item = stack(id, 17);
        CompoundTag entry = new CompoundTag();
        if (named) entry.putString("id", "steamadvantage:steam_capacity");
        else entry.putShort("id", (short) 32);
        entry.putShort("lvl", (short) 3);
        ListTag enchantments = new ListTag();
        enchantments.add(entry);
        item.getCompound("tag").put(key, enchantments);
        return item;
    }

    static CompoundTag item(CompoundTag root, String inventory, int index) { return root.getList(inventory, 10).getCompound(index); }
    static CompoundTag enchantment(CompoundTag item, String key) { return item.getCompound("tag").getList(key, 10).getCompound(0); }

    private static void assertStack(String id, int count, CompoundTag item) {
        assertEquals(id, item.getString("id"));
        assertEquals("Stack count: " + item, count, item.getInt("count"));
        assertFalse("Legacy count remains in " + item, item.contains("Count"));
    }

    private static void assertSlot(CompoundTag item, int slot) {
        assertTrue("Missing inventory slot in " + item, item.contains("Slot", 1));
        assertEquals(slot, item.getByte("Slot"));
    }

    private static void assertComponentEnchantment(CompoundTag item, String component, String name, int level) {
        CompoundTag levels = item.getCompound("components").getCompound(component).getCompound("levels");
        assertEquals("Enchantment levels: " + item, Set.of(name), levels.getAllKeys());
        assertEquals(level, levels.getInt(name));
    }

    private static CompoundTag itemInContainerSlot(ListTag slots, int slot) {
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompound(i);
            if (entry.contains("slot", 3) && entry.getInt("slot") == slot) return entry.getCompound("item");
        }
        throw new AssertionError("Missing slot " + slot + " in " + slots);
    }

    private static final class RecordingFixer implements DataFixer {
        private final Consumer<CompoundTag> mutation;
        private final boolean legacyStageOnly;
        final List<String> calls = new ArrayList<>();
        RecordingFixer(Consumer<CompoundTag> mutation) { this(mutation, false); }
        RecordingFixer(Consumer<CompoundTag> mutation, boolean legacyStageOnly) {
            this.mutation = mutation;
            this.legacyStageOnly = legacyStageOnly;
        }
        @Override public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int from, int to) {
            assertSame(References.PLAYER, type);
            calls.add(from + "->" + to);
            if (!legacyStageOnly || to == 3465) mutation.accept((CompoundTag) input.getValue());
            return input;
        }
        @Override public Schema getSchema(int version) { throw new AssertionError("Not used by the wrapper"); }
    }
}
