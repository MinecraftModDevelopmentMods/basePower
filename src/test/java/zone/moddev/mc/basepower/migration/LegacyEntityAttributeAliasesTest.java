package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.*;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.ForgeMod;
import org.junit.BeforeClass;
import org.junit.Test;

public class LegacyEntityAttributeAliasesTest {
    private static final UUID MODIFIER = new UUID(17L, 19L);

    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void changesOnlyExactNameAndPreservesUnknownFieldsAndUuidModifiers() {
        CompoundTag entity = entity("example:vehicle", true);
        CompoundTag before = entity.copy();
        CompoundTag root = chunk(entity);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag actual = root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0);
        CompoundTag expected = before.copy();
        expected.getListOrEmpty("Attributes").getCompoundOrEmpty(0).putString("Name", "forge:swim_speed");
        expected = LegacyForgeAttributeRegistryTest.nativeAttributeRecord(expected);
        assertEquals(expected, actual);
        assertEquals(before, entity);
    }

    @Test public void aliasesVanillaEntityBeforeNativeDfuAndPreservesModifierIdentity() {
        CompoundTag root = chunk(entity("minecraft:wolf", true));
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag attribute = root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0)
                .getListOrEmpty("attributes").getCompoundOrEmpty(0);
        assertEquals("forge:swim_speed", attribute.getStringOr("id", ""));
        assertEquals(2D, attribute.getDoubleOr("base", 0.0D), 0D);
        assertEquals("keep-attribute-field", attribute.getStringOr("PrivateNote", ""));
        CompoundTag modifier = attribute.getListOrEmpty("modifiers").getCompoundOrEmpty(0);
        assertEquals("minecraft:" + MODIFIER, modifier.getStringOr("id", ""));
        assertEquals("keep-modifier-field", modifier.getStringOr("PrivateNote", ""));
    }

    @Test public void aliasesOnlyEntityPassengersNotInventoryOrPrivateCompounds() {
        CompoundTag parent = entity("example:vehicle", false);
        CompoundTag child = entity("example:seat", true);
        ListTag passengers = new ListTag(); passengers.add(child);
        parent.put("Passengers", passengers);
        CompoundTag foreign = entity("example:private", true);
        parent.put("PrivateData", foreign.copy());
        parent.put("ForgeData", foreign.copy());
        ListTag items = new ListTag(); items.add(foreign.copy()); parent.put("Items", items);
        CompoundTag root = chunk(parent);
        LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
        CompoundTag actual = root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0);
        assertEquals("forge:swim_speed", actual.getListOrEmpty("Passengers").getCompoundOrEmpty(0)
                .getListOrEmpty("attributes").getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals(foreign, actual.getCompoundOrEmpty("PrivateData"));
        assertEquals(foreign, actual.getCompoundOrEmpty("ForgeData"));
        assertEquals(items, actual.getListOrEmpty("Items"));
    }

    @Test public void rejectsLegacyNativeCollisionWithoutChangingChunk() {
        assertCollision("forge.swimSpeed", "forge:swim_speed");
    }

    @Test public void rejectsDuplicateLegacyOrNativeAliasWithoutChangingChunk() {
        assertCollision("forge.swimSpeed", "forge.swimSpeed");
        assertCollision("forge:swim_speed", "forge:swim_speed");
    }

    @Test public void exactAliasDoesNotRenameOtherForgeOrUnknownAttributes() {
        CompoundTag entity = entity("example:vehicle", false);
        ListTag list = new ListTag();
        for (String name : new String[] {"forge.SwimSpeed", "example:swimSpeed", "forge:other", "forge.swimSpeed.extra"}) {
            CompoundTag attribute = attribute(false); attribute.putString("Name", name); list.add(attribute);
        }
        entity.put("Attributes", list);
        CompoundTag root = chunk(entity), before = root.copy();
        before.getCompoundOrEmpty("Level").getListOrEmpty("Entities").set(0,
                LegacyForgeAttributeRegistryTest.nativeAttributeRecord(entity));
        LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
        assertEquals(before, root);
    }

    @Test public void repeatedAliasPreparationIsIdempotent() {
        CompoundTag root = chunk(entity("example:vehicle", true));
        LegacyEntityDataFixes.prepare(root);
        CompoundTag staged = root.copy();
        LegacyEntityDataFixes.prepare(root); assertEquals(staged, root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag once = root.copy();
        LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
        assertEquals(once, root);
    }

    @Test public void modernChunksAndPlayersRemainUnchanged() {
        CompoundTag root = chunk(entity("minecraft:wolf", true));
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        CompoundTag before = root.copy();
        LegacyEntityDataFixes.prepare(root); assertEquals(before, root);
        CompoundTag player = player(entity("minecraft:wolf", true));
        CompoundTag result = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER,
                player, SharedConstants.getCurrentVersion().dataVersion().version());
        assertEquals(player, result);
    }

    @Test public void playerRootVehicleAndPassengersArePreparedBeforeNativePlayerDfu() {
        CompoundTag vehicle = entity("example:vehicle", true);
        ListTag passengers = new ListTag(); passengers.add(entity("example:seat", true)); vehicle.put("Passengers", passengers);
        CompoundTag source = player(vehicle), expected = source.copy();
        CompoundTag result = LegacyPlayerDataFixes.update(new RecordingFixer(value -> {
            CompoundTag rootVehicle = value.getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity");
            assertEquals("forge:swim_speed", rootVehicle.getListOrEmpty("Attributes").getCompoundOrEmpty(0).getStringOr("Name", ""));
            assertEquals("forge:swim_speed", rootVehicle.getListOrEmpty("Passengers").getCompoundOrEmpty(0)
                    .getListOrEmpty("Attributes").getCompoundOrEmpty(0).getStringOr("Name", ""));
            return value;
        }), DataFixTypes.PLAYER, source, 512);
        assertEquals(expected, source);
        expected.getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity").getListOrEmpty("Attributes")
                .getCompoundOrEmpty(0).putString("Name", "forge:swim_speed");
        expected.getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity").getListOrEmpty("Passengers").getCompoundOrEmpty(0)
                .getListOrEmpty("Attributes").getCompoundOrEmpty(0).putString("Name", "forge:swim_speed");
        assertEquals(expected, result);
    }

    @Test public void playerInventoryAndForeignRootVehicleLookalikesAreNotWalked() {
        CompoundTag player = player(entity("example:vehicle", true));
        CompoundTag foreign = entity("example:private", true);
        foreign.putByte("Count", (byte) 3);
        ListTag inventory = new ListTag(); inventory.add(foreign.copy()); player.put("Inventory", inventory);
        player.put("PrivateData", player(entity("example:foreign", true)));
        CompoundTag before = player.copy();
        CompoundTag result = LegacyPlayerDataFixes.update(new RecordingFixer(value -> value),
                DataFixTypes.PLAYER, player, 1343);
        assertEquals(before, player);
        assertEquals(inventory, result.getListOrEmpty("Inventory"));
        assertEquals(before.getCompoundOrEmpty("PrivateData"), result.getCompoundOrEmpty("PrivateData"));
    }

    @Test public void rootVehiclePassengerCollisionFailsWithoutChangingPlayer() {
        CompoundTag vehicle = entity("example:vehicle", true);
        CompoundTag child = entity("example:seat", true);
        CompoundTag duplicate = attribute(false); duplicate.putString("Name", "forge:swim_speed");
        child.getListOrEmpty("Attributes").add(duplicate);
        ListTag passengers = new ListTag(); passengers.add(child); vehicle.put("Passengers", passengers);
        CompoundTag player = player(vehicle), before = player.copy();
        try {
            LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER, player, 1343);
            fail("Alias collision accepted");
        } catch (IllegalStateException expected) { assertEquals(before, player); }
    }

    @Test public void nativePlayerDfuRetainsVehicleAndPassengerCustomizedAttributes() {
        CompoundTag vehicle = entity("minecraft:wolf", true);
        ListTag passengers = new ListTag(); passengers.add(entity("minecraft:wolf", true)); vehicle.put("Passengers", passengers);
        CompoundTag player = player(vehicle), before = player.copy();
        CompoundTag result = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER, player, 1343);
        CompoundTag fixed = result.getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity");
        for (CompoundTag actual : new CompoundTag[] {fixed, fixed.getListOrEmpty("Passengers").getCompoundOrEmpty(0)}) {
            CompoundTag attribute = actual.getListOrEmpty("attributes").getCompoundOrEmpty(0);
            assertEquals("forge:swim_speed", attribute.getStringOr("id", ""));
            assertEquals(2D, attribute.getDoubleOr("base", 0.0D), 0D);
            assertEquals("minecraft:" + MODIFIER, attribute.getListOrEmpty("modifiers").getCompoundOrEmpty(0).getStringOr("id", ""));
        }
        assertEquals(before, player);
    }

    @Test public void ownPlayerAttributeBaseTwoAndModifierSurviveNativePlayerDfu() {
        CompoundTag player = player(entity("minecraft:wolf", true));
        player.put("Attributes", entity("minecraft:wolf", true).getListOrEmpty("Attributes"));
        CompoundTag foreign = entity("example:private", true);
        player.put("PrivateData", foreign.copy());
        CompoundTag before = player.copy();
        CompoundTag result = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER, player, 1343);
        CompoundTag attribute = result.getListOrEmpty("attributes").getCompoundOrEmpty(0);
        assertEquals("forge:swim_speed", attribute.getStringOr("id", ""));
        assertEquals(2D, attribute.getDoubleOr("base", 0.0D), 0D);
        assertEquals("minecraft:" + MODIFIER, attribute.getListOrEmpty("modifiers").getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals("keep-attribute-field", attribute.getStringOr("PrivateNote", ""));
        assertEquals("keep-modifier-field", attribute.getListOrEmpty("modifiers").getCompoundOrEmpty(0).getStringOr("PrivateNote", ""));
        assertEquals(foreign, result.getCompoundOrEmpty("PrivateData"));
        assertEquals(before, player);
    }

    @Test public void ownPlayerAttributeCollisionFailsWithoutMutatingCaller() {
        CompoundTag player = player(entity("example:vehicle", true));
        ListTag attributes = entity("example:attributes", true).getListOrEmpty("Attributes");
        CompoundTag duplicate = attribute(false); duplicate.putString("Name", "forge:swim_speed"); attributes.add(duplicate);
        player.put("Attributes", attributes);
        CompoundTag before = player.copy();
        try {
            LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER, player, 1343);
            fail("Own player attribute collision accepted");
        } catch (IllegalStateException expected) { assertEquals(before, player); }
    }

    @Test public void malformedAttributesFailClosedWithoutChangingSource() {
        CompoundTag entity = entity("example:vehicle", false);
        ListTag malformed = new ListTag(); malformed.add(StringTag.valueOf("keep")); entity.put("Attributes", malformed);
        CompoundTag root = chunk(entity), before = root.copy();
        try { LegacyEntityDataFixes.prepare(root); fail("Malformed attributes accepted"); }
        catch (IllegalStateException expected) { assertEquals(before, root); }
    }

    @Test public void actualNativeWolfRetainsCustomizedBaseTwoAndNonemptyModifier() throws Exception {
        registerForgeAttributes();
        ServerLevel world = NativeWolfTestWorld.create();
        CompoundTag source = entity("minecraft:wolf", true);
        source.putFloat("Health", 8F);
        CompoundTag red = (CompoundTag) DataFixers.getDataFixer().update(References.ENTITY,
                new Dynamic<>(NbtOps.INSTANCE, source.copy()), 1343,
                SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
        Wolf before = new Wolf(EntityType.WOLF, world); NativeWolfTestWorld.load(before, red);
        var swimSpeed = ForgeMod.SWIM_SPEED.getHolder().orElseThrow();
        assertEquals(1D, before.getAttribute(swimSpeed).getBaseValue(), 0D);
        CompoundTag root = chunk(source);
        LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
        Wolf after = new Wolf(EntityType.WOLF, world);
        NativeWolfTestWorld.load(after, root.getCompoundOrEmpty("Level").getListOrEmpty("Entities").getCompoundOrEmpty(0));
        assertEquals(2D, after.getAttribute(swimSpeed).getBaseValue(), 0D);
        var modifierId = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", MODIFIER.toString());
        assertNotNull(after.getAttribute(swimSpeed).getModifier(modifierId));
        assertEquals(0.75D, after.getAttribute(swimSpeed).getModifier(modifierId).amount(), 0D);
        assertEquals(2.75D, after.getAttributeValue(swimSpeed), 0D);
        assertEquals(8F, after.getHealth(), 0F);
    }

    private static void assertCollision(String first, String second) {
        CompoundTag entity = entity("example:vehicle", true);
        entity.getListOrEmpty("Attributes").getCompoundOrEmpty(0).putString("Name", first);
        CompoundTag duplicate = attribute(false); duplicate.putString("Name", second);
        entity.getListOrEmpty("Attributes").add(duplicate);
        CompoundTag root = chunk(entity), before = root.copy();
        try { LegacyEntityDataFixes.prepare(root); fail("Alias collision accepted"); }
        catch (IllegalStateException expected) { assertEquals(before, root); }
    }

    private static CompoundTag attribute(boolean modifier) {
        CompoundTag attribute = new CompoundTag(); attribute.putString("Name", "forge.swimSpeed");
        attribute.putDouble("Base", 2D); attribute.putString("PrivateNote", "keep-attribute-field");
        if (modifier) {
            CompoundTag entry = new CompoundTag(); entry.putString("Name", "customized-swim");
            entry.putLong("UUIDMost", MODIFIER.getMostSignificantBits()); entry.putLong("UUIDLeast", MODIFIER.getLeastSignificantBits());
            entry.putDouble("Amount", 0.75D); entry.putInt("Operation", 0); entry.putString("PrivateNote", "keep-modifier-field");
            ListTag modifiers = new ListTag(); modifiers.add(entry); attribute.put("Modifiers", modifiers);
        }
        return attribute;
    }

    private static CompoundTag entity(String id, boolean attributes) {
        CompoundTag result = new CompoundTag(); result.putString("id", id);
        if (attributes) { ListTag list = new ListTag(); list.add(attribute(true)); result.put("Attributes", list); }
        return result;
    }

    private static CompoundTag chunk(CompoundTag entity) {
        CompoundTag root = new CompoundTag(); root.putInt("DataVersion", 1343);
        CompoundTag level = new CompoundTag(); ListTag entities = new ListTag(); entities.add(entity); level.put("Entities", entities);
        root.put("Level", level); return root;
    }

    private static CompoundTag player(CompoundTag entity) {
        CompoundTag result = new CompoundTag(); CompoundTag vehicle = new CompoundTag();
        vehicle.put("Entity", entity); vehicle.store("Attach", net.minecraft.core.UUIDUtil.CODEC, new UUID(1L, 2L)); result.put("RootVehicle", vehicle); return result;
    }

    private static void registerForgeAttributes() throws Exception {
        LegacyForgeAttributeRegistryTest.registerForgeAttributes();
    }


    private static final class RecordingFixer implements DataFixer {
        private final UnaryOperator<CompoundTag> update;
        RecordingFixer(UnaryOperator<CompoundTag> update) { this.update = update; }
        @Override public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int from, int to) {
            update.apply((CompoundTag) input.getValue());
            return input;
        }
        @Override public Schema getSchema(int version) { throw new AssertionError("Not used by the boundary"); }
    }
}
