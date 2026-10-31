package zone.moddev.mc.poweradvantage.migration;

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.SharedConstants;
import java.util.UUID;
import org.junit.Test;

import static org.junit.Assert.*;

public class LegacyEntitiesTest {
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
        CompoundTag actual = root.getCompound("Level").getList("Entities", 10).getCompound(0);
        assertEquals("example:vehicle", actual.getString("id"));
        assertEquals("keep-verbatim", actual.getString("CustomState"));
        ListTag fixed = actual.getList("Passengers", 10);
        assertEquals(2, fixed.size());
        assertEquals("minecraft:wolf", fixed.getCompound(0).getString("id"));
        assertEquals(UUID.fromString(wolf.getString("OwnerUUID")), fixed.getCompound(0).getUUID("Owner"));
        assertEquals("example:seat", fixed.getCompound(1).getString("id"));
        assertEquals("minecraft:cow", fixed.getCompound(1).getList("Passengers", 10).getCompound(0).getString("id"));
    }

    @Test public void usesVanillaSubtypeFixersRatherThanLowercasingOldIds() {
        CompoundTag skeleton = entity("Skeleton");
        skeleton.putByte("SkeletonType", (byte) 1);
        CompoundTag root = chunk(skeleton, 512);
        root = convert(root, 512);
        assertEquals("minecraft:wither_skeleton", root.getCompound("Level").getList("Entities", 10).getCompound(0).getString("id"));
    }

    @Test public void leavesModernEntityAndUnrelatedCustomNbtUnchangedOnRepeatedPreparation() {
        CompoundTag wolf = entity("minecraft:wolf");
        wolf.putString("OwnerUUID", "a614e658-6270-33c3-9297-7ce80908964f");
        wolf.put("CustomData", entity("Wolf"));
        CompoundTag root = chunk(wolf, SharedConstants.getCurrentVersion().getWorldVersion());
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyChunk(root);
        assertEquals(wolf, root.getCompound("Level").getList("Entities", 10).getCompound(0));
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
        CompoundTag actual = fixed.getCompound("Level").getList("Entities", 10).getCompound(0);
        assertEquals("minecraft:wolf", actual.getString("id"));
        assertEquals(UUID.fromString(wolf.getString("OwnerUUID")), actual.getUUID("Owner"));
        assertTrue(actual.getBoolean("Sitting"));
        assertEquals(new UUID(123L, 456L), actual.getUUID("UUID"));
        assertEquals(customEntity ? 2 : 1, fixed.getCompound("Level").getList("Entities", 10).size());
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
            CompoundTag actual = converted.getCompound("Level").getList("Entities", 10).getCompound(0);
            assertEquals(new UUID(-9014291490481488382L, -5032249493117728499L), actual.getUUID("UUID"));
            assertEquals(privateData, actual.getCompound("PrivateData"));
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
            root.getCompound("Level").getList("Entities", 10).add(entity("example:turbine"));
            CompoundTag converted = convert(root, version);
            CompoundTag actual = converted.getCompound("Level").getList("Entities", 10).getCompound(0);
            assertEquals(uuid, actual.getUUID("UUID"));
            assertEquals(owner, actual.getUUID("Owner"));
            assertEquals(8F, actual.getFloat("Health"), 0F);
            assertTrue(actual.getBoolean("Sitting"));
            converted.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
            assertEquals(converted, convert(converted.copy(), SharedConstants.getCurrentVersion().getWorldVersion()));
        }
    }

    private static CompoundTag convert(CompoundTag root, int version) {
        LegacyWorldDataHook.prepareLegacyChunk(root);
        LegacyWorldDataHook.prepareLegacyEntities(root);
        CompoundTag fixed = NbtUtils.update(DataFixers.getDataFixer(), DataFixTypes.CHUNK,
                root, version, SharedConstants.getCurrentVersion().getWorldVersion());
        return LegacyWorldDataHook.finalizeLegacyChunk(fixed);
    }

    @Test public void retainsZeroHalfAndExistingNativeUuids() {
        for (UUID uuid : new UUID[] {new UUID(0L, 1L), new UUID(1L, 0L), new UUID(0L, 0L)}) {
            CompoundTag custom = entity("example:vehicle");
            custom.putLong("UUIDMost", uuid.getMostSignificantBits());
            custom.putLong("UUIDLeast", uuid.getLeastSignificantBits());
            assertEquals(uuid, convert(chunk(custom, 512), 512).getCompound("Level")
                    .getList("Entities", 10).getCompound(0).getUUID("UUID"));
        }
        CompoundTag custom = entity("example:vehicle");
        custom.putUUID("UUID", new UUID(17L, 19L));
        custom.putLong("UUIDMost", 88L);
        custom.putLong("UUIDLeast", 99L);
        assertEquals(new UUID(17L, 19L), convert(chunk(custom, 2230), 2230).getCompound("Level")
                .getList("Entities", 10).getCompound(0).getUUID("UUID"));
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
            root = NbtUtils.update(DataFixers.getDataFixer(), DataFixTypes.CHUNK,
                    root, 1343, SharedConstants.getCurrentVersion().getWorldVersion());
            root = LegacyWorldDataHook.finalizeLegacyChunk(root);
            CompoundTag actual = root.getCompound("Level").getList("Entities", 10).getCompound(0)
                    .getList("Items", 10).getCompound(0);
            assertEquals("basemetals:steel_pickaxe", actual.getString("id"));
            assertEquals(1, actual.getInt("Count"));
            assertEquals(17, actual.getCompound("tag").getInt("Damage"));
        }
    }

    @Test(expected = IllegalStateException.class) public void rejectsMalformedEntityListWithoutErasingIt() {
        CompoundTag root = chunk(entity("example:vehicle"), 512);
        ListTag wrong = new ListTag();
        wrong.add(StringTag.valueOf("keep"));
        root.getCompound("Level").put("Entities", wrong);
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
        CompoundTag pending = chunk(entity("example:vehicle"), SharedConstants.getCurrentVersion().getWorldVersion());
        pending.getCompound("Level").putIntArray("Biomes", new int[] {1, 2, 3});
        CompoundTag before = pending.copy();
        CompoundTag read = LegacyWorldDataHook.copyChunkForMigration(pending);
        assertNotSame(pending, read);
        assertNotSame(pending.getCompound("Level"), read.getCompound("Level"));
        read.getCompound("Level").putBoolean("TerrainPopulated", true);
        read.getCompound("Level").getList("Entities", 10).getCompound(0).putInt("test", 1);
        read.getCompound("Level").getIntArray("Biomes")[0] = 19;
        assertEquals(before, pending);
        assertNull(LegacyWorldDataHook.copyChunkForMigration(null));
    }
}
