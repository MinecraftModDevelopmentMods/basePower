package zone.moddev.mc.basepower.migration;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Forge 1.21.1 already uses native lowercase attribute records and the current data version. */
public class LegacyEntityDataFixesTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void namespacedSwimAliasIsRenamedBeforeTheCurrentChunkVersionEarlyReturn() {
        assertCurrentChunkAlias("forge:swim_speed", "neoforge:swim_speed", 2.5D);
    }

    @Test public void namespacedNametagAliasIsRenamedBeforeTheCurrentChunkVersionEarlyReturn() {
        assertCurrentChunkAlias("forge:nametag_distance", "neoforge:nametag_distance", 12.5D);
    }

    @Test public void bothNamespacedAliasesAreIdempotentInCurrentNativeEntityRecords() {
        CompoundTag root = chunk(entity("forge:swim_speed", 2.5D));
        attributes(firstEntity(root)).add(attribute("forge:nametag_distance", 12.5D));
        CompoundTag expected = root.copy();
        attributes(firstEntity(expected)).getCompound(0).putString("id", "neoforge:swim_speed");
        attributes(firstEntity(expected)).getCompound(1).putString("id", "neoforge:nametag_distance");
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        assertEquals(expected, root);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        assertEquals("Repeated native upgrades must not duplicate or alter attributes", expected, root);
    }

    @Test public void currentPlayerRootVehicleAndPassengersKeepNativeModifiersAndPrivatePayload() {
        CompoundTag player = entity("forge:swim_speed", 2.5D);
        player.putInt("DataVersion", currentVersion());
        CompoundTag vehicle = new CompoundTag();
        vehicle.putString("Attach", "native-root-vehicle-witness");
        CompoundTag mount = entity("forge:nametag_distance", 12.5D);
        ListTag passengers = new ListTag();
        passengers.add(entity("forge:swim_speed", 3.5D));
        mount.put("Passengers", passengers);
        vehicle.put("Entity", mount);
        player.put("RootVehicle", vehicle);
        CompoundTag expected = player.copy();
        attributes(expected).getCompound(0).putString("id", "neoforge:swim_speed");
        CompoundTag expectedMount = expected.getCompound("RootVehicle").getCompound("Entity");
        attributes(expectedMount).getCompound(0).putString("id", "neoforge:nametag_distance");
        attributes(expectedMount.getList("Passengers", Tag.TAG_COMPOUND).getCompound(0))
                .getCompound(0).putString("id", "neoforge:swim_speed");
        LegacyEntityDataFixes.preparePlayerAttributes(player);
        assertEquals("Only the three namespaced attribute identities may change", expected, player);
        LegacyEntityDataFixes.preparePlayerAttributes(player);
        assertEquals(expected, player);
    }

    private static void assertCurrentChunkAlias(String oldId, String newId, double base) {
        CompoundTag root = chunk(entity(oldId, base));
        CompoundTag expected = root.copy();
        attributes(firstEntity(expected)).getCompound(0).putString("id", newId);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        assertEquals("Current-version migration must retain every other native NBT field", expected, root);
        assertEquals(currentVersion(), root.getInt("DataVersion"));
    }

    private static int currentVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    private static CompoundTag chunk(CompoundTag entity) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", currentVersion());
        root.putIntArray("Position", new int[] {68, 68});
        ListTag entities = new ListTag();
        entities.add(entity);
        root.put("Entities", entities);
        root.putString("PrivateChunkNote", "keep-current-chunk");
        return root;
    }

    private static CompoundTag firstEntity(CompoundTag chunk) {
        return chunk.getList("Entities", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static ListTag attributes(CompoundTag entity) {
        return entity.getList("attributes", Tag.TAG_COMPOUND);
    }

    private static CompoundTag entity(String attributeId, double base) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "example:native-attribute-fixture");
        entity.putIntArray("UUID", new int[] {0, 17, 0, 19});
        entity.putString("CustomName", "{\"text\":\"Keep native name\"}");
        CompoundTag persistent = new CompoundTag();
        persistent.putString("Owner", "keep-persistent-data");
        persistent.putByteArray("Payload", new byte[] {1, 3, 5});
        entity.put("NeoForgeData", persistent);
        ListTag attributes = new ListTag();
        attributes.add(attribute(attributeId, base));
        entity.put("attributes", attributes);
        return entity;
    }

    private static CompoundTag attribute(String id, double base) {
        CompoundTag attribute = new CompoundTag();
        attribute.putString("id", id);
        attribute.putDouble("base", base);
        attribute.putString("PrivateNote", "keep-native-attribute");
        CompoundTag modifier = new CompoundTag();
        modifier.putString("id", "example:stable_modifier");
        modifier.putDouble("amount", 0.125D);
        modifier.putString("operation", "add_value");
        modifier.putString("PrivateNote", "keep-native-modifier");
        ListTag modifiers = new ListTag();
        modifiers.add(modifier);
        attribute.put("modifiers", modifiers);
        return attribute;
    }
}
