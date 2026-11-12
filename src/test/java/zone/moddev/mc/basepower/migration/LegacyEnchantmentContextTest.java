package zone.moddev.mc.basepower.migration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basemetals.api.migration.Legacy112MigrationExtension;
import zone.moddev.mc.basemetals.migration.Legacy112WorldMigrator;

import static org.junit.Assert.*;

public class LegacyEnchantmentContextTest {
    @BeforeClass public static void registerCompanion() throws Exception {
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Constructor<BaseMetalsLegacyMigration> constructor = BaseMetalsLegacyMigration.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Legacy112MigrationExtension.register(Identifier.fromNamespaceAndPath("basepower", "test_enchantment_context"),
                constructor.newInstance());
    }

    @Test public void baseMetalsFirstUsesSavedEnchantmentsForDroppedItemsAndCartContents() throws Exception {
        Map<Integer, String> map = savedIds();
        Map<Integer, String> previous = Map.copyOf(map);
        try {
            for (int version : new int[] {512, 1343}) {
                for (boolean cart : new boolean[] {false, true}) {
                    map.clear();
                    CompoundTag snapshot = registrySnapshot(version), before = snapshot.copy();
                    prepareWorld(snapshot);
                    assertEquals("The extension must not edit the saved registry snapshot", before, snapshot);
                    CompoundTag chunk = chunk(version, cart);
                    LegacyWorldDataHook.prepareLegacyChunk(chunk);
                    LegacyWorldDataHook.prepareLegacyEntities(chunk);
                    CompoundTag converted = DataFixTypes.CHUNK.update(DataFixers.getDataFixer(), chunk,
                            version, SharedConstants.getCurrentVersion().dataVersion().version());
                    converted = LegacyWorldDataHook.finalizeLegacyChunk(converted);
                    CompoundTag entity = converted.getListOrEmpty("entities").getCompoundOrEmpty(0);
                    CompoundTag item = cart ? entity.getListOrEmpty("Items").getCompoundOrEmpty(0)
                            : entity.getCompoundOrEmpty("Item");
                    CompoundTag enchantments = item.getCompoundOrEmpty("components")
                            .getCompoundOrEmpty("minecraft:enchantments");
                    assertEquals("Saved Forge IDs must not become vanilla curses: " + item, 4, enchantments.size());
                    for (int index = 0; index < 4; index++) {
                        assertEquals(item.toString(), index + 1, enchantments.getIntOr("example:musket_" + index, 0));
                    }
                }
            }
        } finally {
            map.clear();
            map.putAll(previous);
        }
    }

    @Test public void selectingAnotherWorldCannotReuseThePreviousEnchantmentIndex() throws Exception {
        Map<Integer, String> map = savedIds();
        Map<Integer, String> previous = Map.copyOf(map);
        try {
            prepareWorld(registrySnapshot(512));
            assertEquals("example:musket_0", map.get(10));
            CompoundTag modern = new CompoundTag(), data = new CompoundTag();
            data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            modern.put("Data", data);
            prepareWorld(modern);
            assertTrue("A world with no legacy index must clear the previous world's IDs", map.isEmpty());
        } finally {
            map.clear();
            map.putAll(previous);
        }
    }

    private static void prepareWorld(CompoundTag snapshot) throws Exception {
        Method method = Legacy112WorldMigrator.class.getDeclaredMethod("prepareWorld", Path.class, CompoundTag.class);
        method.setAccessible(true);
        method.invoke(null, Path.of("build", "enchantment-context-fixture", "level.dat"), snapshot);
    }

    @SuppressWarnings("unchecked") private static Map<Integer, String> savedIds() throws Exception {
        Field field = LegacyWorldDataHook.class.getDeclaredField("LEGACY_ENCHANTMENTS");
        field.setAccessible(true);
        return (Map<Integer, String>) field.get(null);
    }

    private static CompoundTag registrySnapshot(int version) {
        CompoundTag root = new CompoundTag(), data = new CompoundTag(), fml = new CompoundTag();
        CompoundTag registries = new CompoundTag(), enchantments = new CompoundTag();
        ListTag entries = new ListTag();
        data.putInt("DataVersion", version);
        for (int index = 0; index < 4; index++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("V", 10 + index);
            entry.putString("K", "example:musket_" + index);
            entries.add(entry);
        }
        enchantments.put("ids", entries);
        registries.put("minecraft:enchantments", enchantments);
        fml.put("Registries", registries);
        root.put("FML", fml);
        root.put("Data", data);
        return root;
    }

    private static CompoundTag chunk(int version, boolean cart) {
        CompoundTag root = new CompoundTag(), level = new CompoundTag(), entity = new CompoundTag();
        CompoundTag item = new CompoundTag(), tag = new CompoundTag();
        ListTag entities = new ListTag(), enchantments = new ListTag();
        for (int index = 0; index < 4; index++) {
            CompoundTag enchantment = new CompoundTag();
            enchantment.putShort("id", (short) (10 + index));
            enchantment.putShort("lvl", (short) (index + 1));
            enchantments.add(enchantment);
        }
        tag.put("ench", enchantments);
        item.putString("id", "minecraft:diamond_sword");
        item.putByte("Count", (byte) 1);
        item.putShort("Damage", (short) 5);
        item.put("tag", tag);
        entity.putString("id", cart ? (version < 704 ? "MinecartChest" : "minecraft:chest_minecart")
                : (version < 704 ? "Item" : "minecraft:item"));
        if (cart) {
            item.putByte("Slot", (byte) 0);
            ListTag items = new ListTag();
            items.add(item);
            entity.put("Items", items);
        } else entity.put("Item", item);
        entities.add(entity);
        level.put("Entities", entities);
        level.putInt("xPos", 0);
        level.putInt("zPos", 0);
        root.put("Level", level);
        root.putInt("DataVersion", version);
        return root;
    }
}
