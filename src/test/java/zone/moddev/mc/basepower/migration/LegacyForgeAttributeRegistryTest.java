package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.*;

import com.mojang.serialization.Dynamic;
import java.util.UUID;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.ForgeMod;
import net.minecraft.resources.Identifier;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries;

public class LegacyForgeAttributeRegistryTest {
    private static final String[][] ALIASES = {
        {"forge.swimSpeed", "forge:swim_speed", "forge:swim_speed"},
        {"forge.nameTagDistance", "forge:nametag_distance", "forge:nametag_distance"},
        {"forge.entity_gravity", "minecraft:generic.gravity", "minecraft:gravity"},
        {"generic.reachDistance", "minecraft:player.block_interaction_range", "minecraft:block_interaction_range"}
    };

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerForgeAttributes();
    }

    static void registerForgeAttributes() throws ReflectiveOperationException {
        ForgeUnitRegistries.registerVanillaFluidTypes();
        if (ForgeMod.SWIM_SPEED.isPresent()) return;
        ForgeUnitRegistries.registerForgeEntries("ATTRIBUTES", ForgeRegistries.Keys.ATTRIBUTES, ForgeRegistries.ATTRIBUTES);
    }


    @Test public void retainsEveryForgeAliasAndForeignPayloadBeforeNativeDfu() {
        for (String[] alias : ALIASES) {
            CompoundTag source = entity(alias[0], 7.5D), before = source.copy();
            CompoundTag root = chunk(source);
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
            CompoundTag expected = before.copy();
            expected.getListOrEmpty("Attributes").getCompoundOrEmpty(0).putString("Name", alias[1]);
            expected = nativeAttributeRecord(expected);
            assertEquals(alias[0], expected, fixedEntity(root));
            assertEquals(before, source);
        }
    }

    @Test public void nativeLivingEntitiesLoadCustomizedValuesAndModifiersForAllThreeAttributes() throws Exception {
        double[] values = {2D, 12.5D, 0.15D};
        ServerLevel world = NativeWolfTestWorld.create();
        for (int index = 0; index < 3; index++) {
            CompoundTag source = entity(ALIASES[index][0], values[index]); source.putString("id", "minecraft:wolf");
            CompoundTag raw = (CompoundTag) DataFixers.getDataFixer().update(References.ENTITY,
                    new Dynamic<>(NbtOps.INSTANCE, source.copy()), 1343,
                    SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
            Wolf negative = new Wolf(EntityType.WOLF, world); NativeWolfTestWorld.load(negative, raw);
            AttributeInstance old = negative.getAttribute(ForgeRegistries.ATTRIBUTES.getHolder(
                    Identifier.parse(ALIASES[index][2])).orElseThrow());
            assertNotEquals(ALIASES[index][0], values[index], old.getBaseValue(), 0D);
            CompoundTag root = chunk(source);
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
            Wolf positive = new Wolf(EntityType.WOLF, world); NativeWolfTestWorld.load(positive, fixedEntity(root));
            AttributeInstance actual = positive.getAttribute(old.getAttribute());
            assertEquals(ALIASES[index][0], values[index], actual.getBaseValue(), 0D);
            assertEquals(0.125D, actual.getModifier(modifierId()).amount(), 0D);
            assertEquals(values[index] + 0.125D, actual.getValue(), 0D);
        }
    }

    @Test public void playerReachSurvivesNativePlayerFixingAndNativeAttributeLoading() {
        CompoundTag source = entity("generic.reachDistance", 7.5D), before = source.copy();
        CompoundTag actual = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(),
                DataFixTypes.PLAYER, source, 1343);
        CompoundTag value = actual.getListOrEmpty("attributes").getCompoundOrEmpty(0);
        assertEquals("minecraft:block_interaction_range", value.getStringOr("id", ""));
        var reach = net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE;
        assertSame(reach.value(), ForgeRegistries.ATTRIBUTES.getValue(
                Identifier.parse(value.getStringOr("id", ""))));
        AttributeInstance nativeAttribute = new AttributeInstance(reach, unused -> {});
        nativeAttribute.apply(AttributeInstance.Packed.CODEC.parse(NbtOps.INSTANCE, value).getOrThrow());
        assertEquals(7.5D, nativeAttribute.getBaseValue(), 0D);
        assertEquals(7.625D, nativeAttribute.getValue(), 0D);
        assertEquals(0.125D, nativeAttribute.getModifier(modifierId()).amount(), 0D);
        assertEquals(before, source);
    }

    @Test public void allAliasCollisionsFailClosedWithoutChangingCaller() {
        for (String[] alias : ALIASES) for (String[] pair : new String[][] {
                {alias[0], alias[1]}, {alias[0], alias[0]}, {alias[1], alias[1]}}) {
            CompoundTag source = entity(pair[0], 2D);
            source.getListOrEmpty("Attributes").add(attribute(pair[1], 3D));
            CompoundTag root = chunk(source), before = root.copy();
            try { LegacyEntityDataFixes.prepare(root); fail("Ambiguous attribute accepted: " + alias[0]); }
            catch (IllegalStateException expected) { assertEquals(before, root); }
        }
    }

    @Test public void everyAliasSurvivesPlayerMountsAndPassengersWithoutWalkingPrivateData() {
        for (String[] alias : ALIASES) {
            CompoundTag source = entity(alias[0], 7.5D);
            CompoundTag vehicle = entity(alias[0], 6.5D); vehicle.putString("id", "minecraft:wolf");
            CompoundTag passenger = entity(alias[0], 5.5D); passenger.putString("id", "minecraft:wolf");
            ListTag passengers = new ListTag(); passengers.add(passenger); vehicle.put("Passengers", passengers);
            CompoundTag rootVehicle = new CompoundTag(); rootVehicle.put("Entity", vehicle);
            rootVehicle.store("Attach", net.minecraft.core.UUIDUtil.CODEC, new UUID(23L, 29L)); source.put("RootVehicle", rootVehicle);
            source.put("PrivateData", entity(alias[0], 4.5D));
            CompoundTag before = source.copy();
            CompoundTag actual = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(),
                    DataFixTypes.PLAYER, source, 1343);
            CompoundTag fixedVehicle = actual.getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity");
            for (CompoundTag item : new CompoundTag[] {actual, fixedVehicle,
                    fixedVehicle.getListOrEmpty("Passengers").getCompoundOrEmpty(0)}) {
                assertEquals(alias[2], item.getListOrEmpty("attributes").getCompoundOrEmpty(0).getStringOr("id", ""));
                assertEquals(0.125D, item.getListOrEmpty("attributes").getCompoundOrEmpty(0)
                        .getListOrEmpty("modifiers").getCompoundOrEmpty(0).getDoubleOr("amount", 0.0D), 0D);
            }
            assertEquals(before.getCompoundOrEmpty("PrivateData"), actual.getCompoundOrEmpty("PrivateData"));
            assertEquals(before, source);
        }
    }

    @Test public void everyAliasIsIdempotentWithSavedValuesIntact() {
        for (String[] alias : ALIASES) {
            CompoundTag root = chunk(entity(alias[0], 7.5D));
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
            CompoundTag before = root.copy();
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
            assertEquals(before, root);
        }
    }

    @Test public void modernChunksAndSimilarUnknownNamesRemainUntouched() {
        for (String[] alias : ALIASES) {
            CompoundTag root = chunk(entity(alias[0], 7D));
            root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            CompoundTag before = root.copy(); LegacyEntityDataFixes.prepare(root); assertEquals(before, root);
            root = chunk(entity(alias[0] + ".private", 7D)); before = root.copy();
            before.getCompoundOrEmpty("Level").getListOrEmpty("Entities").set(0,
                    nativeAttributeRecord(before.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0)));
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root); assertEquals(before, root);
        }
    }

    private static Identifier modifierId() {
        return Identifier.fromNamespaceAndPath("minecraft", new UUID(17L, 19L).toString());
    }

    static CompoundTag nativeAttributeRecord(CompoundTag source) {
        CompoundTag fields = new CompoundTag();
        fields.put("Attributes", source.get("Attributes").copy());
        CompoundTag nativeFields = DataFixTypes.PLAYER.updateToCurrentVersion(
                DataFixers.getDataFixer(), fields, 1343);
        CompoundTag expected = source.copy();
        expected.remove("Attributes");
        expected.put("attributes", nativeFields.get("attributes").copy());
        return expected;
    }

    @Test public void unknownModEntityRetainsItsAttributesInTheNativeSaveFormat() throws Exception {
        CompoundTag source = entity("forge.swimSpeed", 2D), original = source.copy();
        source.put("PrivateData", original.copy());
        CompoundTag root = chunk(source);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag converted = fixedEntity(root);
        Wolf nativeReader = new Wolf(EntityType.WOLF, NativeWolfTestWorld.create());
        NativeWolfTestWorld.load(nativeReader, converted);
        AttributeInstance speed = nativeReader.getAttribute(ForgeMod.SWIM_SPEED.getHolder().orElseThrow());
        assertEquals(2D, speed.getBaseValue(), 0D);
        assertNotNull(speed.getModifier(modifierId()));
        assertEquals(2.125D, speed.getValue(), 0D);
        assertEquals(original, converted.getCompoundOrEmpty("PrivateData"));
    }

    private static CompoundTag entity(String name, double base) {
        CompoundTag value = new CompoundTag(); value.putString("id", "example:vehicle");
        ListTag attributes = new ListTag(); attributes.add(attribute(name, base)); value.put("Attributes", attributes);
        return value;
    }

    private static CompoundTag attribute(String name, double base) {
        CompoundTag value = new CompoundTag(); value.putString("Name", name); value.putDouble("Base", base);
        value.putString("PrivateNote", "preserve-attribute-payload");
        CompoundTag modifier = new CompoundTag(); modifier.putString("Name", "customized-attribute");
        modifier.putLong("UUIDMost", 17L); modifier.putLong("UUIDLeast", 19L);
        modifier.putDouble("Amount", 0.125D); modifier.putInt("Operation", 0);
        modifier.putString("PrivateNote", "preserve-modifier-payload");
        ListTag modifiers = new ListTag(); modifiers.add(modifier); value.put("Modifiers", modifiers);
        return value;
    }

    private static CompoundTag chunk(CompoundTag entity) {
        CompoundTag value = new CompoundTag(); value.putInt("DataVersion", 1343);
        CompoundTag level = new CompoundTag(); ListTag entities = new ListTag(); entities.add(entity);
        level.put("Entities", entities); value.put("Level", level); return value;
    }

    private static CompoundTag fixedEntity(CompoundTag root) { return root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0); }

}
