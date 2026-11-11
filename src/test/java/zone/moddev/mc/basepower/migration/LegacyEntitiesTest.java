package zone.moddev.mc.basepower.migration;

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.SharedConstants;
import java.util.UUID;
import org.junit.Test;

import static org.junit.Assert.*;

public class LegacyEntitiesTest {
    @org.junit.BeforeClass public static void detectVersion() {
        SharedConstants.tryDetectVersion();
    }

    @Test public void retainsWolfInDirect110Chunk() {
        assertWolf(false);
    }

    @Test public void retainsWolfBesideCustomEntityInDirect110Chunk() {
        assertWolf(true);
    }

    @Test public void fixesVanillaPassengersInsideUnknownModEntities() {
        CompoundTag vehicle = entity("example:vehicle");
        vehicle.putString("CustomState", "keep-verbatim");
        CompoundTag wolf = entity("Wolf");
        wolf.putString("OwnerUUID", "a614e658-6270-33c3-9297-7ce80908964f");
        CompoundTag nested = entity("example:seat");
        ListTag nestedPassengers = new ListTag();
        nestedPassengers.add(entity("Cow"));
        nested.put("Passengers", nestedPassengers);
        ListTag passengers = new ListTag();
        passengers.add(wolf);
        passengers.add(nested);
        vehicle.put("Passengers", passengers);
        CompoundTag root = chunk(vehicle, 512);
        root = convert(root, 512);
        CompoundTag actual = root.getListOrEmpty("entities").getCompoundOrEmpty(0);
        assertEquals("example:vehicle", actual.getStringOr("id", ""));
        assertEquals("keep-verbatim", actual.getStringOr("CustomState", ""));
        ListTag fixed = actual.getListOrEmpty("Passengers");
        assertEquals(2, fixed.size());
        assertEquals("minecraft:wolf", fixed.getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals(UUID.fromString(wolf.getStringOr("OwnerUUID", "")), fixed.getCompoundOrEmpty(0).read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        assertEquals("example:seat", fixed.getCompoundOrEmpty(1).getStringOr("id", ""));
        assertEquals("minecraft:cow", fixed.getCompoundOrEmpty(1).getListOrEmpty("Passengers").getCompoundOrEmpty(0).getStringOr("id", ""));
    }

    @Test public void usesVanillaSubtypeFixersRatherThanLowercasingOldIds() {
        CompoundTag skeleton = entity("Skeleton");
        skeleton.putByte("SkeletonType", (byte) 1);
        CompoundTag root = chunk(skeleton, 512);
        root = convert(root, 512);
        assertEquals("minecraft:wither_skeleton", root.getListOrEmpty("entities").getCompoundOrEmpty(0).getStringOr("id", ""));
    }

    @Test public void leavesModernEntityAndUnrelatedCustomNbtUnchangedOnRepeatedPreparation() {
        CompoundTag wolf = entity("minecraft:wolf");
        wolf.putString("OwnerUUID", "a614e658-6270-33c3-9297-7ce80908964f");
        wolf.put("CustomData", entity("Wolf"));
        CompoundTag root = chunk(wolf, SharedConstants.getCurrentVersion().dataVersion().version());
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyChunk(root);
        assertEquals(wolf, root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0));
    }

    @Test public void repeatPreparationDoesNotLoseConvertedVanillaState() {
        CompoundTag wolf = entity("Wolf");
        wolf.putString("OwnerUUID", "a614e658-6270-33c3-9297-7ce80908964f");
        CompoundTag root = chunk(wolf, 512);
        LegacyWorldDataHook.prepareLegacyChunk(root);
        CompoundTag once = root.copy();
        LegacyWorldDataHook.prepareLegacyChunk(root);
        assertEquals(once, root);
    }

    private static CompoundTag entity(String id) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", id);
        return entity;
    }

    private static CompoundTag chunk(CompoundTag entity, int version) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", version);
        CompoundTag level = new CompoundTag();
        level.putInt("xPos", 0);
        level.putInt("zPos", 0);
        ListTag entities = new ListTag();
        entities.add(entity);
        level.put("Entities", entities);
        root.put("Level", level);
        return root;
    }

    private static void assertWolf(boolean customEntity) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 512);
        CompoundTag level = new CompoundTag();
        level.putInt("xPos", 0);
        level.putInt("zPos", 0);
        ListTag entities = new ListTag();
        CompoundTag wolf = new CompoundTag();
        wolf.putString("id", "Wolf");
        wolf.putString("OwnerUUID", "a614e658-6270-33c3-9297-7ce80908964f");
        wolf.putBoolean("Sitting", true);
        wolf.putLong("UUIDMost", 123L);
        wolf.putLong("UUIDLeast", 456L);
        entities.add(wolf);
        if (customEntity) {
            CompoundTag turbine = new CompoundTag();
            turbine.putString("id", "electricadvantage.electricadvantage.Hydroturbine");
            turbine.putIntArray("parentPos", new int[] {164, 81, 8});
            entities.add(turbine);
        }
        level.put("Entities", entities);
        root.put("Level", level);
        CompoundTag fixed = convert(root, 512);
        CompoundTag actual = fixed.getListOrEmpty("entities").getCompoundOrEmpty(0);
        assertEquals("minecraft:wolf", actual.getStringOr("id", ""));
        assertEquals(UUID.fromString(wolf.getStringOr("OwnerUUID", "")), actual.read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        assertTrue(actual.getBooleanOr("Sitting", false));
        assertEquals(new UUID(123L, 456L), actual.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        assertEquals(customEntity ? 2 : 1, fixed.getListOrEmpty("entities").size());
    }

    @Test public void preservesCustomEntityUuidAndPrivateNbtThroughWholeChunkFix() {
        CompoundTag custom = entity("example:turbine");
        custom.putLong("UUIDMost", -9014291490481488382L);
        custom.putLong("UUIDLeast", -5032249493117728499L);
        CompoundTag privateData = entity("Wolf");
        privateData.putLong("UUIDMost", 88L);
        privateData.putLong("UUIDLeast", 99L);
        custom.put("PrivateData", privateData);
        for (int version : new int[] {512, 1343, 1631, 1976, 2230}) {
            CompoundTag converted = convert(chunk(custom.copy(), version), version);
            CompoundTag actual = converted.getListOrEmpty("entities").getCompoundOrEmpty(0);
            assertEquals(new UUID(-9014291490481488382L, -5032249493117728499L), actual.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            assertEquals(privateData, actual.getCompoundOrEmpty("PrivateData"));
            assertFalse(converted.contains("AdvantageLegacyEntityDataFix"));
        }
    }

    @Test public void preservesOwnerHealthAndUuidInPostFlatteningMixedChunks() {
        for (int version : new int[] {1343, 1631, 1976, 2230}) {
            CompoundTag wolf = entity("minecraft:wolf");
            UUID uuid = new UUID(-1767247258114444277L, -6740529603542625373L);
            UUID owner = UUID.fromString("a614e658-6270-33c3-9297-7ce80908964f");
            wolf.putLong("UUIDMost", uuid.getMostSignificantBits());
            wolf.putLong("UUIDLeast", uuid.getLeastSignificantBits());
            wolf.putString("OwnerUUID", owner.toString());
            wolf.putFloat("Health", 8F);
            wolf.putBoolean("Sitting", true);
            CompoundTag root = chunk(wolf, version);
            root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").add(entity("example:turbine"));
            CompoundTag converted = convert(root, version);
            CompoundTag actual = converted.getListOrEmpty("entities").getCompoundOrEmpty(0);
            assertEquals(uuid, actual.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            assertEquals(owner, actual.read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            assertEquals(8F, actual.getFloatOr("Health", 0.0F), 0F);
            assertTrue(actual.getBooleanOr("Sitting", false));
            converted.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            assertEquals(converted, convert(converted.copy(), SharedConstants.getCurrentVersion().dataVersion().version()));
        }
    }

    private static CompoundTag convert(CompoundTag root, int version) {
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        CompoundTag fixed = DataFixTypes.CHUNK.update(DataFixers.getDataFixer(),
                root, version, SharedConstants.getCurrentVersion().dataVersion().version());
        return LegacyWorldDataHook.finalizeLegacyChunk(fixed);
    }

    @Test public void retainsZeroHalfAndExistingNativeUuids() {
        for (UUID uuid : new UUID[] {new UUID(0L, 1L), new UUID(1L, 0L), new UUID(0L, 0L)}) {
            CompoundTag custom = entity("example:vehicle");
            custom.putLong("UUIDMost", uuid.getMostSignificantBits());
            custom.putLong("UUIDLeast", uuid.getLeastSignificantBits());
            assertEquals(uuid, convert(chunk(custom, 512), 512)
                    .getListOrEmpty("entities").getCompoundOrEmpty(0).read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        }
        CompoundTag custom = entity("example:vehicle");
        custom.store("UUID", net.minecraft.core.UUIDUtil.CODEC, new UUID(17L, 19L));
        custom.putLong("UUIDMost", 88L);
        custom.putLong("UUIDLeast", 99L);
        assertEquals(new UUID(17L, 19L), convert(chunk(custom, 2230), 2230)
                .getListOrEmpty("entities").getCompoundOrEmpty(0).read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
    }

    @Test public void baseMetalsDurabilitySurvivesBothEntryHookOrders() throws Exception {
        java.lang.reflect.Method basePrepare = Class.forName("zone.moddev.mc.basemetals.migration.LegacyWorldDataHook")
                .getMethod("prepareLegacyChunk", CompoundTag.class);
        for (boolean baseFirst : new boolean[] {false, true}) {
            CompoundTag cart = entity("minecraft:chest_minecart");
            CompoundTag pick = entity("basemetals:steel_pickaxe");
            pick.putByte("Count", (byte) 1);
            pick.putShort("Damage", (short) 17);
            pick.putByte("Slot", (byte) 0);
            ListTag items = new ListTag();
            items.add(pick);
            cart.put("Items", items);
            CompoundTag root = chunk(cart, 1343);
            if (baseFirst) basePrepare.invoke(null, root);
            LegacyWorldDataHook.prepareLegacyChunk(root);
            if (!baseFirst) basePrepare.invoke(null, root);
            LegacyWorldDataHook.prepareLegacyEntities(root);
            root = DataFixTypes.CHUNK.update(DataFixers.getDataFixer(),
                    root, 1343, SharedConstants.getCurrentVersion().dataVersion().version());
            root = LegacyWorldDataHook.finalizeLegacyChunk(root);
            CompoundTag actual = root.getListOrEmpty("entities").getCompoundOrEmpty(0)
                    .getListOrEmpty("Items").getCompoundOrEmpty(0);
            String order = (baseFirst ? "Base Metals first: " : "basePower first: ") + actual;
            assertEquals(order, "basemetals:steel_pickaxe", actual.getStringOr("id", ""));
            assertEquals(order, 1, actual.getIntOr("count", 0));
            assertFalse(order, actual.contains("Count"));
            assertTrue(order, zone.moddev.mc.basepower.util.NbtCompat.containsTag(actual, "Slot", 1));
            assertEquals(order, 0, actual.getByteOr("Slot", (byte)0));
            assertEquals(order, 17, actual.getCompoundOrEmpty("components").getIntOr("minecraft:damage", 0));
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            root.putInt("DataVersion", current);
            assertEquals(order, root, convert(root.copy(), current));
        }
    }

    @Test(expected = IllegalStateException.class) public void rejectsMalformedEntityListWithoutErasingIt() {
        CompoundTag root = chunk(entity("example:vehicle"), 512);
        ListTag wrong = new ListTag();
        wrong.add(StringTag.valueOf("keep"));
        root.getCompoundOrEmpty("Level").put("Entities", wrong);
        LegacyWorldDataHook.prepareLegacyEntities(root);
    }

    @Test(expected = IllegalStateException.class) public void rejectsMalformedPassengerList() {
        CompoundTag custom = entity("example:vehicle");
        ListTag wrong = new ListTag();
        wrong.add(StringTag.valueOf("keep"));
        custom.put("Passengers", wrong);
        LegacyWorldDataHook.prepareLegacyEntities(chunk(custom, 512));
    }

    @Test(expected = IllegalStateException.class) public void rejectsMalformedStagedList() {
        CompoundTag root = chunk(entity("example:vehicle"), 512);
        ListTag wrong = new ListTag();
        wrong.add(StringTag.valueOf("keep"));
        root.put("AdvantageLegacyEntityDataFix", wrong);
        LegacyWorldDataHook.finalizeLegacyChunk(root);
    }

    @Test public void detachesPendingChunkNbtIncludingNestedMapsAndArrays() {
        CompoundTag pending = chunk(entity("example:vehicle"), SharedConstants.getCurrentVersion().dataVersion().version());
        pending.getCompoundOrEmpty("Level").putIntArray("Biomes", new int[] {1, 2, 3});
        CompoundTag before = pending.copy();
        CompoundTag read = LegacyWorldDataHook.copyChunkForMigration(pending);
        assertNotSame(pending, read);
        assertNotSame(pending.getCompoundOrEmpty("Level"), read.getCompoundOrEmpty("Level"));
        read.getCompoundOrEmpty("Level").putBoolean("TerrainPopulated", true);
        read.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0).putInt("test", 1);
        read.getCompoundOrEmpty("Level").getIntArray("Biomes").orElseThrow()[0] = 19;
        assertEquals(before, pending);
        assertNull(LegacyWorldDataHook.copyChunkForMigration(null));
    }
}
