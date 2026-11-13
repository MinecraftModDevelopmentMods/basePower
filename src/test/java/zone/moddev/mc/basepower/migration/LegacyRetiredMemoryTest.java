package zone.moddev.mc.basepower.migration;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRetiredMemoryTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void archivesExactPayloadAndRetainsOtherBrainMemoriesThroughNativeCodec() throws Exception {
        CompoundTag entity = entity();
        CompoundTag original = entity.copy();
        assertTrue(Brain.Packed.CODEC.parse(NbtOps.INSTANCE, entity.get("Brain")).error().isPresent());
        LegacyEntityDataFixes.archiveRetiredMemories(entity);
        assertEquals(original.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories").get("minecraft:is_tempted"),
                archive(entity).get("minecraft:is_tempted"));
        var brain = Brain.Packed.CODEC.parse(NbtOps.INSTANCE, entity.get("Brain")).getOrThrow();
        assertEquals(Integer.valueOf(75), brain.memories().get(MemoryModuleType.TEMPTATION_COOLDOWN_TICKS).value());
        assertEquals("keep", entity.getCompoundOrEmpty("ForgeData").getStringOr("private_note", ""));
        assertEquals("keep-root", entity.getStringOr("private_note", ""));
        CompoundTag once = entity.copy();
        LegacyEntityDataFixes.archiveRetiredMemories(entity);
        assertEquals(once, entity);
    }

    @Test public void migratesStoppedEntityChunksAndPassengersWithoutChangingTheInputEntities() throws Exception {
        CompoundTag parent = entity(), child = entity();
        ListTag passengers = new ListTag(); passengers.add(child); parent.put("Passengers", passengers);
        CompoundTag original = parent.copy();
        CompoundTag root = new CompoundTag(); root.putInt("DataVersion", 4903);
        root.putIntArray("Position", new int[] {0, 0});
        ListTag entities = new ListTag(); entities.add(parent); root.put("Entities", entities);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag actual = root.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        assertEquals(original, parent);
        assertEquals(archive(originalAfterArchive(original)), archive(actual));
        CompoundTag actualChild = actual.getListOrEmpty("Passengers").getCompoundOrEmpty(0);
        assertTrue(archive(actualChild).contains("minecraft:is_tempted"));
        assertFalse(actualChild.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories").contains("minecraft:is_tempted"));
    }

    @Test public void alsoArchivesARootVehicleWithoutScanningPrivateInventoryCompounds() throws Exception {
        CompoundTag vehicle = entity(), privateCopy = entity();
        vehicle.put("PrivateData", privateCopy.copy());
        CompoundTag player = new CompoundTag(), rootVehicle = new CompoundTag();
        rootVehicle.put("Entity", vehicle); player.put("RootVehicle", rootVehicle);
        LegacyEntityDataFixes.preparePlayerAttributes(player);
        assertTrue(archive(vehicle).contains("minecraft:is_tempted"));
        assertEquals(privateCopy, vehicle.getCompoundOrEmpty("PrivateData"));
    }

    @Test public void refusesConflictingArchivesWithoutChangingAnyInput() throws Exception {
        CompoundTag entity = entity();
        entity.getCompoundOrEmpty("ForgeData").put(LegacyEntityDataFixes.RETIRED_MEMORIES,
                TagParser.parseCompoundFully("{\"minecraft:is_tempted\":{value:0b}}"));
        CompoundTag before = entity.copy();
        assertThrows(IllegalStateException.class, () -> LegacyEntityDataFixes.archiveRetiredMemories(entity));
        assertEquals(before, entity);
    }

    @Test public void refusesMalformedPersistentDataWithoutChangingTheBrain() throws Exception {
        for (String payload : new String[] {"{ForgeData:3}",
                "{ForgeData:{\"basepower:retired_brain_memories\":3}}"}) {
            CompoundTag entity = entity();
            entity.put("ForgeData", TagParser.parseCompoundFully(payload).get("ForgeData"));
            CompoundTag before = entity.copy();
            assertThrows(IllegalStateException.class, () -> LegacyEntityDataFixes.archiveRetiredMemories(entity));
            assertEquals(before, entity);
        }
    }

    @Test public void leavesUnknownMemoriesForTheNativeCodecToReject() throws Exception {
        CompoundTag entity = entity();
        CompoundTag memories = entity.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories");
        memories.remove("minecraft:is_tempted");
        memories.put("othermod:is_tempted", TagParser.parseCompoundFully("{value:1b}"));
        CompoundTag before = entity.copy();
        LegacyEntityDataFixes.archiveRetiredMemories(entity);
        assertEquals(before, entity);
        assertTrue(Brain.Packed.CODEC.parse(NbtOps.INSTANCE, entity.get("Brain")).error().isPresent());
    }

    private static CompoundTag originalAfterArchive(CompoundTag entity) {
        CompoundTag result = entity.copy();
        LegacyEntityDataFixes.archiveRetiredMemories(result);
        return result;
    }

    private static CompoundTag archive(CompoundTag entity) {
        return entity.getCompoundOrEmpty("ForgeData").getCompoundOrEmpty(LegacyEntityDataFixes.RETIRED_MEMORIES);
    }

    private static CompoundTag entity() throws Exception {
        return TagParser.parseCompoundFully("{id:\"minecraft:goat\",private_note:\"keep-root\","
                + "Brain:{memories:{\"minecraft:is_tempted\":{value:1b,ttl:120L},"
                + "\"minecraft:temptation_cooldown_ticks\":{value:75}}},ForgeData:{private_note:\"keep\"}}");
    }
}
