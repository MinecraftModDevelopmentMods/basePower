package zone.moddev.mc.basepower.migration;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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
        NBTTagCompound machine = container("minecraft:electricadvantage.growth_chamber");
        NBTTagCompound chest = container("minecraft:chest");
        LegacyWorldDataHook.flattenVanillaInventories(chunk(1343, machine, chest));

        NBTTagList contents = machine.getList("Items", 10);
        NBTTagCompound cane = contents.getCompound(0);
        assertEquals("minecraft:sugar_cane", cane.getString("id"));
        assertEquals(7, cane.getByte("Count"));
        assertEquals(3, cane.getByte("Slot"));
        assertEquals("keep me", cane.getCompound("tag").getString("Note"));
        assertEquals("minecraft:red_wool", contents.getCompound(1).getString("id"));
        assertEquals("minecraft:reeds", chest.getList("Items", 10).getCompound(0).getString("id"));
    }

    @Test public void leavesAlreadyFlattenedChunksForTheNormalReader() {
        NBTTagCompound machine = container("baseelectric:growth_chamber");
        LegacyWorldDataHook.flattenVanillaInventories(chunk(1631, machine));
        assertEquals("minecraft:reeds", machine.getList("Items", 10).getCompound(0).getString("id"));
    }

    private static NBTTagCompound chunk(int version, NBTTagCompound... tiles) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound level = new NBTTagCompound();
        NBTTagList entries = new NBTTagList();
        for (NBTTagCompound tile : tiles) entries.add(tile);
        level.setTag("TileEntities", entries);
        root.setTag("Level", level);
        root.setInt("DataVersion", version);
        return root;
    }

    private static NBTTagCompound container(String id) {
        NBTTagCompound tile = new NBTTagCompound();
        NBTTagList items = new NBTTagList();
        items.add(stack("minecraft:reeds", 0));
        items.add(stack("minecraft:wool", 14));
        tile.setString("id", id);
        tile.setTag("Items", items);
        return tile;
    }

    private static NBTTagCompound stack(String id, int metadata) {
        NBTTagCompound value = new NBTTagCompound();
        NBTTagCompound tag = new NBTTagCompound();
        value.setString("id", id);
        value.setByte("Slot", (byte) 3);
        value.setByte("Count", (byte) 7);
        value.setShort("Damage", (short) metadata);
        tag.setString("Note", "keep me");
        value.setTag("tag", tag);
        return value;
    }
}
