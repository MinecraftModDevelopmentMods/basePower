package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;
import static org.junit.Assert.*;

public class LegacyMachineItemsTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        AdvantageLegacyMigrations.registerPowerAdvantage();
    }

    @Test public void colouredWoolSurvivesAnActualConveyorInventoryLoad() {
        CompoundTag tile = convertedTile(stack("minecraft:wool", 14, 7));
        TileEntityConveyor conveyor = new TileEntityConveyor(net.minecraft.core.BlockPos.ZERO, Blocks.HOPPER.defaultBlockState());
        conveyor.loadWithComponents(tile, registries());
        assertSame(Items.RED_WOOL, conveyor.getItem(0).getItem());
        assertEquals(7, conveyor.getItem(0).getCount());
    }

    private static net.minecraft.core.RegistryAccess registries() {
        return net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    @Test public void preservesVanillaMetadataVariantsInMachineSlots() {
        for (String[] row : new String[][] {{"minecraft:coal", "1", "minecraft:charcoal"},
                {"minecraft:dye", "4", "minecraft:lapis_lazuli"},
                {"minecraft:planks", "1", "minecraft:spruce_planks"}}) {
            CompoundTag item = convertedTile(stack(row[0], Integer.parseInt(row[1]), 7))
                    .getList("Items", 10).getCompound(0);
            assertStack(row[2], 7, item);
            assertEquals(0, item.getByte("Slot"));
            assertTrue("Missing inventory slot in " + item, item.contains("Slot", 1));
        }
    }

    @Test public void nativeItemFixersPreserveDamageAndEnchantmentEffects() {
        CompoundTag pick = stack("minecraft:diamond_pickaxe", 17, 1);
        CompoundTag tag = new CompoundTag();
        ListTag enchantments = new ListTag();
        CompoundTag enchantment = new CompoundTag();
        enchantment.putShort("id", (short) 32);
        enchantment.putShort("lvl", (short) 3);
        enchantments.add(enchantment);
        tag.put("ench", enchantments);
        CompoundTag display = new CompoundTag();
        display.putString("Name", "Survey Pick");
        tag.put("display", display);
        pick.put("tag", tag);
        CompoundTag actual = convertedTile(pick).getList("Items", 10).getCompound(0);
        assertStack("minecraft:diamond_pickaxe", 1, actual);
        assertEquals(17, actual.getCompound("components").getInt("minecraft:damage"));
        assertEnchantment(actual, "minecraft:enchantments", "minecraft:efficiency", 3);
        ItemStack loaded = ItemStack.parseOptional(registries(), actual);
        assertSame(Items.DIAMOND_PICKAXE, loaded.getItem());
        assertEquals(1, loaded.getCount());
        assertEquals(17, loaded.getDamageValue());
        assertEquals("Survey Pick", loaded.getHoverName().getString());
    }

    @Test public void nestedStoredItemsAreConvertedWithoutChangingMachineState() {
        CompoundTag tile = tile(stack("minecraft:wool", 14, 7));
        CompoundTag nested = new CompoundTag();
        nested.put("Reference", stack("minecraft:dye", 4, 1));
        tile.put("Recipe", nested);
        tile.putFloat("Energy", 19.5F);
        tile.putString("CustomName", "keep");
        CompoundTag actual = convert(chunk(tile), 512).getList("block_entities", 10).getCompound(0);
        assertStack("minecraft:lapis_lazuli", 1, actual.getCompound("Recipe").getCompound("Reference"));
        assertStack("minecraft:red_wool", 7, actual.getList("Items", 10).getCompound(0));
        assertEquals(19.5F, actual.getFloat("Energy"), 0F);
        assertEquals("keep", actual.getString("CustomName"));
    }

    @Test public void foreignPrivatePayloadsAreNotInterpretedAsAdvantageInventories() {
        CompoundTag foreign = tile(stack("minecraft:wool", 14, 7));
        foreign.putString("id", "other:private_machine");
        CompoundTag expected = foreign.copy();
        CompoundTag root = chunk(foreign);
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        assertEquals(expected, root.getCompound("Level").getList("TileEntities", 10).getCompound(0));
    }

    @Test public void currentVersionMachineDataIsNotRewritten() {
        CompoundTag currentItem = new CompoundTag();
        currentItem.putString("id", "minecraft:diamond_pickaxe");
        currentItem.putInt("count", 1);
        currentItem.putByte("Slot", (byte) 0);
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:damage", 17);
        currentItem.put("components", components);
        CompoundTag currentTile = tile(currentItem);
        currentTile.putString("id", "basepower:item_conveyor");
        ListTag tiles = new ListTag();
        tiles.add(currentTile);
        CompoundTag root = new CompoundTag();
        root.put("block_entities", tiles);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        CompoundTag expected = root.copy();
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        assertEquals(expected, root);
    }

    @Test public void repeatedPreparationDoesNotRepairAStoredTool() {
        CompoundTag root = chunk(tile(stack("minecraft:diamond_pickaxe", 17, 1)));
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        CompoundTag once = root.copy();
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        assertEquals(once, root);
        CompoundTag item = root.getCompound("Level").getList("TileEntities", 10).getCompound(0)
                .getList("Items", 10).getCompound(0);
        assertStack("minecraft:diamond_pickaxe", 1, item);
        assertEquals(17, item.getCompound("components").getInt("minecraft:damage"));
    }

    @Test public void namedBookEnchantmentsAreNotReinterpretedAsProtection() {
        CompoundTag book = stack("minecraft:enchanted_book", 0, 1);
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag named = new CompoundTag();
        named.putString("id", "steamadvantage:steam_capacity");
        named.putShort("lvl", (short) 3);
        list.add(named);
        tag.put("StoredEnchantments", list);
        book.put("tag", tag);
        CompoundTag actual = convertedTile(book).getList("Items", 10).getCompound(0);
        assertStack("minecraft:enchanted_book", 1, actual);
        assertEnchantment(actual, "minecraft:stored_enchantments", "steamadvantage:steam_capacity", 3);
    }

    @Test public void nativeNestedContainerItemsAreFixedExactlyOnce() {
        CompoundTag chest = stack("minecraft:chest", 0, 1);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "Chest");
        ListTag contents = new ListTag();
        contents.add(stack("minecraft:diamond_pickaxe", 17, 1));
        CompoundTag wool = stack("minecraft:wool", 14, 7);
        wool.putByte("Slot", (byte) 1);
        contents.add(wool);
        blockEntity.put("Items", contents);
        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", blockEntity);
        chest.put("tag", tag);
        CompoundTag actual = convertedTile(chest).getList("Items", 10).getCompound(0);
        assertStack("minecraft:chest", 1, actual);
        ListTag fixed = actual.getCompound("components").getList("minecraft:container", 10);
        assertEquals("Container slots: " + actual, 2, fixed.size());
        CompoundTag pick = itemInContainerSlot(fixed, 0);
        assertStack("minecraft:diamond_pickaxe", 1, pick);
        assertEquals(17, pick.getCompound("components").getInt("minecraft:damage"));
        assertStack("minecraft:red_wool", 7, itemInContainerSlot(fixed, 1));
    }

    @Test public void foreignItemPayloadInsideAnOwnedMachineRemainsVerbatim() {
        CompoundTag foreign = stack("other:component", 9, 1);
        CompoundTag tag = new CompoundTag();
        tag.put("Private", stack("minecraft:wool", 14, 7));
        foreign.put("tag", tag);
        assertEquals(foreign, convertedTile(foreign).getList("Items", 10).getCompound(0));
    }

    @Test public void nestedNamedEnchantmentsSurviveNativeContainerFixes() {
        CompoundTag book = stack("minecraft:enchanted_book", 0, 1);
        CompoundTag tag = new CompoundTag();
        ListTag enchantments = new ListTag();
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "steamadvantage:steam_capacity");
        enchantment.putShort("lvl", (short) 3);
        enchantments.add(enchantment);
        tag.put("StoredEnchantments", enchantments);
        book.put("tag", tag);
        CompoundTag root = chunk(tile(carrier(book)));
        root.putInt("DataVersion", 1343);
        CompoundTag actual = convert(root, 1343).getList("block_entities", 10)
                .getCompound(0).getList("Items", 10).getCompound(0);
        ListTag fixed = actual.getCompound("components").getList("minecraft:container", 10);
        assertEquals("Container slots: " + actual, 1, fixed.size());
        CompoundTag fixedBook = itemInContainerSlot(fixed, 0);
        assertStack("minecraft:enchanted_book", 1, fixedBook);
        assertEnchantment(fixedBook, "minecraft:stored_enchantments", "steamadvantage:steam_capacity", 3);
    }

    @Test public void nestedForeignItemsRetainTheirPrivateMetadata() {
        CompoundTag foreign = stack("other:component", 9, 3);
        foreign.putByte("Slot", (byte) 7);
        CompoundTag privateData = new CompoundTag();
        privateData.put("Private", stack("minecraft:wool", 14, 7));
        privateData.putIntArray("PrivateArray", new int[] {19, 23});
        foreign.put("tag", privateData);
        CompoundTag before = foreign.copy();

        CompoundTag chest = convertedTile(carrier(foreign)).getList("Items", 10).getCompound(0);
        assertStack("minecraft:chest", 1, chest);
        ListTag slots = chest.getCompound("components").getList("minecraft:container", 10);
        assertEquals("Container slots: " + chest, 1, slots.size());
        CompoundTag actual = itemInContainerSlot(slots, 7);
        assertStack("other:component", 3, actual);
        assertEquals(before.get("Damage"), actual.get("Damage"));
        assertEquals(before.getCompound("tag"), actual.getCompound("components").getCompound("minecraft:custom_data"));
    }

    @Test public void registeredForeignToolKeepsWearInDirectAndNestedMachineSlots() {
        String id = "advantageworks_unit:legacy_foreign_tool";
        net.minecraftforge.registries.GameData.unfreezeData();
        var tool = new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties().durability(250));
        net.minecraftforge.registries.ForgeRegistries.ITEMS.register(net.minecraft.resources.ResourceLocation.parse(id), tool);
        CompoundTag foreign = stack(id, 9, 1);
        CompoundTag payload = new CompoundTag();
        payload.putInt("Fixture", 31);
        foreign.put("tag", payload);
        CompoundTag original = foreign.copy();

        var direct = zone.moddev.mc.basepower.util.ItemData.readStack(registries(), foreign);
        CompoundTag chest = convertedTile(carrier(foreign)).getList("Items", 10).getCompound(0);
        var carried = ItemStack.parseOptional(registries(), chest)
                .get(net.minecraft.core.component.DataComponents.CONTAINER).nonEmptyItems().iterator().next();
        for (var loaded : List.of(direct, carried)) {
            assertSame(tool, loaded.getItem());
            assertEquals(1, loaded.getCount());
            assertEquals(9, loaded.getDamageValue());
            assertEquals(payload, zone.moddev.mc.basepower.util.ItemData.customData(loaded));
            assertEquals(9, ItemStack.parseOptional(registries(),
                    (CompoundTag) loaded.save(registries())).getDamageValue());
        }
        assertEquals(original, foreign);
    }

    @Test public void positiveNativeContainerConversionsDoNotEmitErrors() {
        try (ErrorCapture capture = new ErrorCapture()) {
            nativeNestedContainerItemsAreFixedExactlyOnce();
            nestedForeignItemsRetainTheirPrivateMetadata();
            assertTrue("Positive native conversions emitted ERROR+: " + capture.errors, capture.errors.isEmpty());
        }
    }

    @Test public void native704EnvelopeUsesACopiedSingletonAndThePassedFixer() {
        CompoundTag source = stack("minecraft:chest", 0, 1);
        source.putIntArray("PrivateArray", new int[] {1, 2});
        CompoundTag before = source.copy();
        CompoundTag result = LegacyMachineItemDataFixes.upgradeTo704(source, 512, new EnvelopeFixer(root -> {
            assertEquals(1, root.getAllKeys().size());
            assertEquals(1, root.getList("Inventory", 10).size());
            CompoundTag item = root.getList("Inventory", 10).getCompound(0);
            assertEquals(before, item);
            item.getIntArray("PrivateArray")[0] = 99;
            item.putString("NativeField", "retained");
        }));
        assertEquals(before, source);
        assertEquals("retained", result.getString("NativeField"));
        assertEquals(99, result.getIntArray("PrivateArray")[0]);
        result.putInt("AfterReturn", 1);
        assertEquals(before, source);
    }

    @Test public void native704EnvelopeRejectsMissingOrWrongInventoryTypes() {
        rejectedEnvelope(root -> root.remove("Inventory"));
        rejectedEnvelope(root -> root.putString("Inventory", "not a list"));
        rejectedEnvelope(root -> {
            ListTag items = new ListTag();
            items.add(StringTag.valueOf("not a stack"));
            root.put("Inventory", items);
        });
    }

    @Test public void native704EnvelopeRejectsNonSingletonInventories() {
        rejectedEnvelope(root -> root.getList("Inventory", 10).remove(0));
        rejectedEnvelope(root -> root.getList("Inventory", 10).add(stack("minecraft:stone", 0, 1)));
    }

    @Test public void native704EnvelopeRejectsCountAndSlotChanges() {
        rejectedEnvelope(root -> envelopeItem(root).remove("Count"));
        rejectedEnvelope(root -> envelopeItem(root).putByte("Count", (byte) 2));
        rejectedEnvelope(root -> envelopeItem(root).putInt("Count", 1));
        rejectedEnvelope(root -> envelopeItem(root).putByte("Slot", (byte) 1));
        rejectedEnvelope(root -> envelopeItem(root).remove("Slot"));
    }

    @Test public void native704EnvelopeRejectsMalformedItemIds() {
        rejectedEnvelope(root -> envelopeItem(root).remove("id"));
        rejectedEnvelope(root -> envelopeItem(root).putInt("id", 54));
        rejectedEnvelope(root -> envelopeItem(root).putString("id", ""));
        rejectedEnvelope(root -> envelopeItem(root).putString("id", "minecraft:"));
        rejectedEnvelope(root -> envelopeItem(root).putString("id", "not an item"));
    }

    @Test public void native704EnvelopeDoesNotAddASlotToAnUnslottedStack() {
        CompoundTag source = stack("minecraft:chest", 0, 1);
        source.remove("Slot");
        CompoundTag before = source.copy();
        try {
            LegacyMachineItemDataFixes.upgradeTo704(source, 512,
                    new EnvelopeFixer(root -> envelopeItem(root).putByte("Slot", (byte) 0)));
            fail("A new slot cannot be silently paired with an unslotted source");
        } catch (IllegalStateException expected) { assertEquals(before, source); }
    }

    @Test public void native704EnvelopeRejectsANonCompoundPlayerResult() {
        CompoundTag source = stack("minecraft:chest", 0, 1), before = source.copy();
        DataFixer fixer = new EnvelopeFixer(root -> {}) {
            @Override public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int from, int to) {
                return new Dynamic<>(input.getOps(), input.getOps().createList(Stream.empty()));
            }
        };
        try {
            LegacyMachineItemDataFixes.upgradeTo704(source, 512, fixer);
            fail("The native envelope must remain a compound");
        } catch (IllegalStateException expected) { assertEquals(before, source); }
    }

    private static CompoundTag envelopeItem(CompoundTag root) { return root.getList("Inventory", 10).getCompound(0); }

    private static void rejectedEnvelope(Consumer<CompoundTag> mutation) {
        CompoundTag source = stack("minecraft:chest", 0, 1), before = source.copy();
        try {
            LegacyMachineItemDataFixes.upgradeTo704(source, 512, new EnvelopeFixer(mutation));
            fail("An unpairable native envelope must fail closed");
        } catch (IllegalStateException expected) { assertEquals(before, source); }
    }

    private static class EnvelopeFixer implements DataFixer {
        private final Consumer<CompoundTag> mutation;
        EnvelopeFixer(Consumer<CompoundTag> mutation) { this.mutation = mutation; }
        @Override public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int from, int to) {
            assertSame(References.PLAYER, type);
            assertEquals(512, from);
            assertEquals(704, to);
            mutation.accept((CompoundTag) input.getValue());
            return input;
        }
        @Override public Schema getSchema(int version) { throw new AssertionError("Not used by the envelope"); }
    }

    private static final class ErrorCapture extends AbstractAppender implements AutoCloseable {
        private final Logger logger = (Logger) LogManager.getRootLogger();
        final List<String> errors = new ArrayList<>();
        ErrorCapture() {
            super("LegacyMachineItemsPositiveErrors", null, null, true);
            start();
            logger.addAppender(this);
        }
        @Override public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                errors.add(event.getLoggerName() + ": " + event.getMessage().getFormattedMessage());
            }
        }
        @Override public void close() { logger.removeAppender(this); stop(); }
    }

    private static CompoundTag carrier(CompoundTag item) {
        CompoundTag chest = stack("minecraft:chest", 0, 1);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        ListTag contents = new ListTag();
        contents.add(item);
        blockEntity.put("Items", contents);
        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", blockEntity);
        chest.put("tag", tag);
        return chest;
    }

    private static void assertStack(String id, int count, CompoundTag item) {
        assertEquals(id, item.getString("id"));
        assertEquals("Stack count: " + item, count, item.getInt("count"));
        assertFalse("Legacy count remains in " + item, item.contains("Count"));
    }

    private static void assertEnchantment(CompoundTag item, String component, String name, int level) {
        CompoundTag levels = item.getCompound("components").getCompound(component).getCompound("levels");
        assertEquals("Enchantment levels: " + item, java.util.Set.of(name), levels.getAllKeys());
        assertEquals(level, levels.getInt(name));
    }

    private static CompoundTag itemInContainerSlot(ListTag slots, int slot) {
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompound(i);
            if (entry.contains("slot", 3) && entry.getInt("slot") == slot) return entry.getCompound("item");
        }
        throw new AssertionError("Missing slot " + slot + " in " + slots);
    }

    private static CompoundTag convertedTile(CompoundTag item) {
        return convert(chunk(tile(item)), 512).getList("block_entities", 10).getCompound(0);
    }

    private static CompoundTag convert(CompoundTag root, int version) {
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        CompoundTag fixed = DataFixTypes.CHUNK.update(DataFixers.getDataFixer(),
                root, version, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        return LegacyWorldDataHook.finalizeLegacyChunk(fixed);
    }

    private static CompoundTag stack(String id, int damage, int count) {
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putByte("Count", (byte) count);
        item.putByte("Slot", (byte) 0);
        item.putShort("Damage", (short) damage);
        return item;
    }

    private static CompoundTag tile(CompoundTag item) {
        CompoundTag tile = new CompoundTag();
        tile.putString("id", "poweradvantage:item_conveyor");
        tile.putInt("x", 0); tile.putInt("y", 64); tile.putInt("z", 0);
        ListTag items = new ListTag();
        items.add(item);
        tile.put("Items", items);
        return tile;
    }

    private static CompoundTag chunk(CompoundTag tile) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 512);
        CompoundTag level = new CompoundTag();
        ListTag tiles = new ListTag();
        tiles.add(tile);
        level.put("TileEntities", tiles);
        root.put("Level", level);
        return root;
    }
}
