package zone.moddev.mc.basepower.migration;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.ResourceLocation;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LegacyVanillaInventoriesTest {
    @BeforeClass public static void register() {
        LegacyMigrationApi.registerNamespaceAlias("electricadvantage", "baseelectric");
        LegacyMigrationApi.registerTileEntityAliases(new ResourceLocation("baseelectric", "growth_chamber"));
    }

    @Test public void flattensVanillaItemsInModInventoriesWithSlotsAndTagsIntact() {
        CompoundNBT machine = container("minecraft:electricadvantage.growth_chamber");
        CompoundNBT chest = container("minecraft:chest");
        LegacyWorldDataHook.flattenVanillaInventories(chunk(1343, machine, chest));

        ListNBT contents = machine.getList("Items", 10);
        CompoundNBT cane = contents.getCompound(0);
        assertEquals("minecraft:sugar_cane", cane.getString("id"));
        assertEquals(7, cane.getByte("Count"));
        assertEquals(3, cane.getByte("Slot"));
        assertEquals("keep me", cane.getCompound("tag").getString("Note"));
        assertEquals("minecraft:red_wool", contents.getCompound(1).getString("id"));
        assertEquals("minecraft:reeds", chest.getList("Items", 10).getCompound(0).getString("id"));
    }

    @Test public void leavesAlreadyFlattenedChunksForTheNormalReader() {
        CompoundNBT machine = container("baseelectric:growth_chamber");
        LegacyWorldDataHook.flattenVanillaInventories(chunk(1631, machine));
        assertEquals("minecraft:reeds", machine.getList("Items", 10).getCompound(0).getString("id"));
    }

    private static CompoundNBT chunk(int version, CompoundNBT... tiles) {
        CompoundNBT root = new CompoundNBT();
        CompoundNBT level = new CompoundNBT();
        ListNBT entries = new ListNBT();
        for (CompoundNBT tile : tiles) entries.add(tile);
        level.put("TileEntities", entries);
        root.put("Level", level);
        root.putInt("DataVersion", version);
        return root;
    }

    private static CompoundNBT container(String id) {
        CompoundNBT tile = new CompoundNBT();
        ListNBT items = new ListNBT();
        items.add(stack("minecraft:reeds", 0));
        items.add(stack("minecraft:wool", 14));
        tile.putString("id", id);
        tile.put("Items", items);
        return tile;
    }

    private static CompoundNBT stack(String id, int metadata) {
        CompoundNBT value = new CompoundNBT();
        CompoundNBT tag = new CompoundNBT();
        value.putString("id", id);
        value.putByte("Slot", (byte) 3);
        value.putByte("Count", (byte) 7);
        value.putShort("Damage", (short) metadata);
        tag.putString("Note", "keep me");
        value.put("tag", tag);
        return value;
    }
}
