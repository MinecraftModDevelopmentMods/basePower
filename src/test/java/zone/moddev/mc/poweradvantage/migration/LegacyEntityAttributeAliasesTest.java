package zone.moddev.mc.poweradvantage.migration;

import static org.junit.Assert.*;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.BeforeClass;
import org.junit.Test;

public class LegacyEntityAttributeAliasesTest {
    private static final UUID MODIFIER = new UUID(17L, 19L);

    @BeforeClass public static void bootstrap() { Bootstrap.bootStrap(); }

    @Test public void changesOnlyExactNameAndPreservesUnknownFieldsAndUuidModifiers() {
        CompoundTag entity = entity("example:vehicle", true);
        CompoundTag before = entity.copy();
        CompoundTag root = chunk(entity);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag actual = root.getCompound("Level").getList("Entities", 10).getCompound(0);
        CompoundTag expected = before.copy();
        expected.getList("Attributes", 10).getCompound(0).putString("Name", "forge:swim_speed");
        assertEquals(expected, actual);
        assertEquals(before, entity);
    }

    @Test public void aliasesVanillaEntityBeforeNativeDfuAndPreservesModifierIdentity() {
        CompoundTag root = chunk(entity("minecraft:wolf", true));
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        CompoundTag attribute = root.getCompound("Level").getList("Entities", 10).getCompound(0)
                .getList("Attributes", 10).getCompound(0);
        assertEquals("forge:swim_speed", attribute.getString("Name"));
        assertEquals(2D, attribute.getDouble("Base"), 0D);
        assertEquals("keep-attribute-field", attribute.getString("PrivateNote"));
        CompoundTag modifier = attribute.getList("Modifiers", 10).getCompound(0);
        assertEquals(MODIFIER, modifier.getUUID("UUID"));
        assertEquals("keep-modifier-field", modifier.getString("PrivateNote"));
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
        CompoundTag actual = root.getCompound("Level").getList("Entities", 10).getCompound(0);
        assertEquals("forge:swim_speed", actual.getList("Passengers", 10).getCompound(0)
                .getList("Attributes", 10).getCompound(0).getString("Name"));
        assertEquals(foreign, actual.getCompound("PrivateData"));
        assertEquals(foreign, actual.getCompound("ForgeData"));
        assertEquals(items, actual.getList("Items", 10));
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
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        CompoundTag before = root.copy();
        LegacyEntityDataFixes.prepare(root); assertEquals(before, root);
        CompoundTag player = player(entity("minecraft:wolf", true));
        CompoundTag result = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER,
                player, SharedConstants.getCurrentVersion().getWorldVersion());
        assertEquals(player, result);
    }

    @Test public void playerRootVehicleAndPassengersArePreparedBeforeNativePlayerDfu() {
        CompoundTag vehicle = entity("example:vehicle", true);
        ListTag passengers = new ListTag(); passengers.add(entity("example:seat", true)); vehicle.put("Passengers", passengers);
        CompoundTag source = player(vehicle), expected = source.copy();
        CompoundTag result = LegacyPlayerDataFixes.update(new RecordingFixer(value -> {
            CompoundTag rootVehicle = value.getCompound("RootVehicle").getCompound("Entity");
            assertEquals("forge:swim_speed", rootVehicle.getList("Attributes", 10).getCompound(0).getString("Name"));
            assertEquals("forge:swim_speed", rootVehicle.getList("Passengers", 10).getCompound(0)
                    .getList("Attributes", 10).getCompound(0).getString("Name"));
            return value;
        }), DataFixTypes.PLAYER, source, 512);
        assertEquals(expected, source);
        expected.getCompound("RootVehicle").getCompound("Entity").getList("Attributes", 10)
                .getCompound(0).putString("Name", "forge:swim_speed");
        expected.getCompound("RootVehicle").getCompound("Entity").getList("Passengers", 10).getCompound(0)
                .getList("Attributes", 10).getCompound(0).putString("Name", "forge:swim_speed");
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
        assertEquals(inventory, result.getList("Inventory", 10));
        assertEquals(before.getCompound("PrivateData"), result.getCompound("PrivateData"));
    }

    @Test public void rootVehiclePassengerCollisionFailsWithoutChangingPlayer() {
        CompoundTag vehicle = entity("example:vehicle", true);
        CompoundTag child = entity("example:seat", true);
        CompoundTag duplicate = attribute(false); duplicate.putString("Name", "forge:swim_speed");
        child.getList("Attributes", 10).add(duplicate);
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
        CompoundTag fixed = result.getCompound("RootVehicle").getCompound("Entity");
        for (CompoundTag actual : new CompoundTag[] {fixed, fixed.getList("Passengers", 10).getCompound(0)}) {
            CompoundTag attribute = actual.getList("Attributes", 10).getCompound(0);
            assertEquals("forge:swim_speed", attribute.getString("Name"));
            assertEquals(2D, attribute.getDouble("Base"), 0D);
            assertEquals(MODIFIER, attribute.getList("Modifiers", 10).getCompound(0).getUUID("UUID"));
        }
        assertEquals(before, player);
    }

    @Test public void ownPlayerAttributeBaseTwoAndModifierSurviveNativePlayerDfu() {
        CompoundTag player = player(entity("minecraft:wolf", true));
        player.put("Attributes", entity("minecraft:wolf", true).getList("Attributes", 10));
        CompoundTag foreign = entity("example:private", true);
        player.put("PrivateData", foreign.copy());
        CompoundTag before = player.copy();
        CompoundTag result = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(), DataFixTypes.PLAYER, player, 1343);
        CompoundTag attribute = result.getList("Attributes", 10).getCompound(0);
        assertEquals("forge:swim_speed", attribute.getString("Name"));
        assertEquals(2D, attribute.getDouble("Base"), 0D);
        assertEquals(MODIFIER, attribute.getList("Modifiers", 10).getCompound(0).getUUID("UUID"));
        assertEquals("keep-attribute-field", attribute.getString("PrivateNote"));
        assertEquals("keep-modifier-field", attribute.getList("Modifiers", 10).getCompound(0).getString("PrivateNote"));
        assertEquals(foreign, result.getCompound("PrivateData"));
        assertEquals(before, player);
    }

    @Test public void ownPlayerAttributeCollisionFailsWithoutMutatingCaller() {
        CompoundTag player = player(entity("example:vehicle", true));
        ListTag attributes = entity("example:attributes", true).getList("Attributes", 10);
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
        // Keep constructor-free test setup without linking restricted JDK types.
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field unsafeField = allocator.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        ServerLevel world = (ServerLevel) allocator.getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), ServerLevel.class);
        set(Level.class, world, "dimension", Level.NETHER);
        set(Level.class, world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        CompoundTag source = entity("minecraft:wolf", true);
        source.putFloat("Health", 8F);
        CompoundTag red = (CompoundTag) DataFixers.getDataFixer().update(References.ENTITY,
                new Dynamic<>(NbtOps.INSTANCE, source.copy()), 1343,
                SharedConstants.getCurrentVersion().getWorldVersion()).getValue();
        Wolf before = new Wolf(EntityType.WOLF, world); before.load(red);
        assertEquals(1D, before.getAttribute(ForgeMod.SWIM_SPEED.get()).getBaseValue(), 0D);
        CompoundTag root = chunk(source);
        LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
        Wolf after = new Wolf(EntityType.WOLF, world);
        after.load(root.getCompound("Level").getList("Entities", 10).getCompound(0));
        assertEquals(2D, after.getAttribute(ForgeMod.SWIM_SPEED.get()).getBaseValue(), 0D);
        assertNotNull(after.getAttribute(ForgeMod.SWIM_SPEED.get()).getModifier(MODIFIER));
        assertEquals(0.75D, after.getAttribute(ForgeMod.SWIM_SPEED.get()).getModifier(MODIFIER).getAmount(), 0D);
        assertEquals(2.75D, after.getAttributeValue(ForgeMod.SWIM_SPEED.get()), 0D);
        assertEquals(8F, after.getHealth(), 0F);
    }

    private static void assertCollision(String first, String second) {
        CompoundTag entity = entity("example:vehicle", true);
        entity.getList("Attributes", 10).getCompound(0).putString("Name", first);
        CompoundTag duplicate = attribute(false); duplicate.putString("Name", second);
        entity.getList("Attributes", 10).add(duplicate);
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
        vehicle.put("Entity", entity); vehicle.putUUID("Attach", new UUID(1L, 2L)); result.put("RootVehicle", vehicle); return result;
    }

    private static void registerForgeAttributes() throws Exception {
        if (ForgeMod.SWIM_SPEED.isPresent()) return;
        Field attributes = ForgeMod.class.getDeclaredField("ATTRIBUTES"); attributes.setAccessible(true);
        Method register = DeferredRegister.class.getDeclaredMethod("addEntries", RegistryEvent.Register.class); register.setAccessible(true);
        register.invoke(attributes.get(null), new RegistryEvent.Register<>(ForgeRegistries.ATTRIBUTES.getRegistryName(), ForgeRegistries.ATTRIBUTES));
    }

    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
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
