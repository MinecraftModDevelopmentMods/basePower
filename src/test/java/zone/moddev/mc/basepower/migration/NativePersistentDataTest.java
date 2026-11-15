package zone.moddev.mc.basepower.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePersistentDataTest {
    @Test public void chunkEntitiesTilesPassengersAndNestedItemsRetainTheirPayload() throws Exception {
        CompoundTag source = TagParser.parseTag("{Level:{Entities:[{ForgeData:{Witness:1},Passengers:[{ForgeData:{Witness:2}}]}],"
                + "TileEntities:[{ForgeData:{Witness:3},Items:[{id:'minecraft:chest',Count:1b,tag:{BlockEntityTag:{ForgeData:{Witness:4}}}}]}]}}");
        CompoundTag copy = source.copy();
        assertEquals(4, NativePersistentData.prepare(copy, false));
        assertTrue(source.toString().contains("ForgeData"));
        CompoundTag level = copy.getCompound("Level");
        assertEquals(1, level.getList("Entities", 10).getCompound(0).getCompound("NeoForgeData").getInt("Witness"));
        assertEquals(2, level.getList("Entities", 10).getCompound(0).getList("Passengers", 10).getCompound(0)
                .getCompound("NeoForgeData").getInt("Witness"));
        assertEquals(3, level.getList("TileEntities", 10).getCompound(0).getCompound("NeoForgeData").getInt("Witness"));
        assertEquals(4, level.getList("TileEntities", 10).getCompound(0).getList("Items", 10).getCompound(0)
                .getCompound("tag").getCompound("BlockEntityTag").getCompound("NeoForgeData").getInt("Witness"));
        assertEquals(0, NativePersistentData.prepare(copy, false));
    }

    @Test public void playerAndNativeItemContainersMigrateButOpaqueCustomDataDoesNot() throws Exception {
        CompoundTag player = TagParser.parseTag("{ForgeData:{Witness:7},Inventory:[{id:'minecraft:chest',count:1,"
                + "components:{'minecraft:block_entity_data':{ForgeData:{Witness:8}},"
                + "'minecraft:custom_data':{ForgeData:{Opaque:9}}}}]}");
        assertEquals(2, NativePersistentData.prepare(player, true));
        assertEquals(7, player.getCompound("NeoForgeData").getInt("Witness"));
        CompoundTag components = player.getList("Inventory", 10).getCompound(0).getCompound("components");
        assertEquals(8, components.getCompound("minecraft:block_entity_data").getCompound("NeoForgeData").getInt("Witness"));
        assertEquals(9, components.getCompound("minecraft:custom_data").getCompound("ForgeData").getInt("Opaque"));
    }

    @Test public void conflictingPayloadsFailWithoutOverwritingEitherValue() throws Exception {
        CompoundTag player = TagParser.parseTag("{ForgeData:{Witness:1},NeoForgeData:{Witness:2}}");
        CompoundTag before = player.copy();
        try { NativePersistentData.prepare(player, true); fail("Conflict was accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        assertEquals(before, player);
    }
}
