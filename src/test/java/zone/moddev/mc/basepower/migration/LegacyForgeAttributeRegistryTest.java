package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.*;

import com.mojang.serialization.Dynamic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries;

public class LegacyForgeAttributeRegistryTest {
    private static final String[][] ALIASES = {
        {"forge.swimSpeed", "forge:swim_speed"},
        {"forge.nameTagDistance", "forge:nametag_distance"},
        {"forge.entity_gravity", "forge:entity_gravity"},
        {"generic.reachDistance", "forge:reach_distance"}
    };

    @BeforeClass public static void bootstrap() throws Exception {
        Bootstrap.bootStrap();
        registerForgeAttributes();
    }

    static void registerForgeAttributes() throws ReflectiveOperationException {
        ForgeUnitRegistries.registerVanillaFluidTypes();
        if (ForgeMod.SWIM_SPEED.isPresent()) return;
        ForgeUnitRegistries.registerForgeEntries("ATTRIBUTES", ForgeRegistries.Keys.ATTRIBUTES, ForgeRegistries.ATTRIBUTES);
        Method alias = ForgeMod.class.getDeclaredMethod("addAlias", IForgeRegistry.class,
                ResourceLocation.class, ResourceLocation.class);
        alias.setAccessible(true);
        alias.invoke(null, ForgeRegistries.ATTRIBUTES,
                new ResourceLocation("forge:reach_distance"), new ResourceLocation("forge:block_reach"));
    }


    @Test public void retainsEveryForgeAliasAndForeignPayloadBeforeNativeDfu() {
        for (String[] alias : ALIASES) {
            CompoundTag source = entity(alias[0], 7.5D), before = source.copy();
            CompoundTag root = chunk(source);
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
            CompoundTag expected = before.copy();
            expected.getList("Attributes", 10).getCompound(0).putString("Name", alias[1]);
            assertEquals(alias[0], expected, fixedEntity(root));
            assertEquals(before, source);
        }
    }

    @Test public void nativeLivingEntitiesLoadCustomizedValuesAndModifiersForAllThreeAttributes() throws Exception {
        double[] values = {2D, 12.5D, 0.15D};
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field unsafe = allocator.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
        ServerLevel world = (ServerLevel) allocator.getMethod("allocateInstance", Class.class)
                .invoke(unsafe.get(null), ServerLevel.class);
        set(Level.class, world, "dimension", Level.NETHER);
        set(Level.class, world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        for (int index = 0; index < 3; index++) {
            CompoundTag source = entity(ALIASES[index][0], values[index]); source.putString("id", "minecraft:wolf");
            CompoundTag raw = (CompoundTag) DataFixers.getDataFixer().update(References.ENTITY,
                    new Dynamic<>(NbtOps.INSTANCE, source.copy()), 1343,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
            Wolf negative = new Wolf(EntityType.WOLF, world); negative.load(raw);
            AttributeInstance old = negative.getAttribute(ForgeRegistries.ATTRIBUTES.getValue(
                    new net.minecraft.resources.ResourceLocation(ALIASES[index][1])));
            assertNotEquals(ALIASES[index][0], values[index], old.getBaseValue(), 0D);
            CompoundTag root = chunk(source);
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root);
            Wolf positive = new Wolf(EntityType.WOLF, world); positive.load(fixedEntity(root));
            AttributeInstance actual = positive.getAttribute(old.getAttribute());
            assertEquals(ALIASES[index][0], values[index], actual.getBaseValue(), 0D);
            assertEquals(0.125D, actual.getModifier(new UUID(17L, 19L)).getAmount(), 0D);
            assertEquals(values[index] + 0.125D, actual.getValue(), 0D);
        }
    }

    @Test public void playerReachSurvivesNativePlayerFixingAndNativeAttributeLoading() {
        CompoundTag source = entity("generic.reachDistance", 7.5D), before = source.copy();
        CompoundTag actual = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(),
                DataFixTypes.PLAYER, source, 1343);
        CompoundTag value = actual.getList("Attributes", 10).getCompound(0);
        assertEquals("forge:reach_distance", value.getString("Name"));
        assertSame(ForgeMod.REACH_DISTANCE.get(), ForgeRegistries.ATTRIBUTES.getValue(
                new net.minecraft.resources.ResourceLocation(value.getString("Name"))));
        AttributeInstance nativeAttribute = new AttributeInstance(ForgeMod.REACH_DISTANCE.get(), unused -> {});
        nativeAttribute.load(value);
        assertEquals(7.5D, nativeAttribute.getBaseValue(), 0D);
        assertEquals(7.625D, nativeAttribute.getValue(), 0D);
        assertEquals(0.125D, nativeAttribute.getModifier(new UUID(17L, 19L)).getAmount(), 0D);
        assertEquals(before, source);
    }

    @Test public void allAliasCollisionsFailClosedWithoutChangingCaller() {
        for (String[] alias : ALIASES) for (String[] pair : new String[][] {
                {alias[0], alias[1]}, {alias[0], alias[0]}, {alias[1], alias[1]}}) {
            CompoundTag source = entity(pair[0], 2D);
            source.getList("Attributes", 10).add(attribute(pair[1], 3D));
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
            rootVehicle.putUUID("Attach", new UUID(23L, 29L)); source.put("RootVehicle", rootVehicle);
            source.put("PrivateData", entity(alias[0], 4.5D));
            CompoundTag before = source.copy();
            CompoundTag actual = LegacyPlayerDataFixes.update(DataFixers.getDataFixer(),
                    DataFixTypes.PLAYER, source, 1343);
            CompoundTag fixedVehicle = actual.getCompound("RootVehicle").getCompound("Entity");
            for (CompoundTag item : new CompoundTag[] {actual, fixedVehicle,
                    fixedVehicle.getList("Passengers", 10).getCompound(0)}) {
                assertEquals(alias[1], item.getList("Attributes", 10).getCompound(0).getString("Name"));
                assertEquals(0.125D, item.getList("Attributes", 10).getCompound(0)
                        .getList("Modifiers", 10).getCompound(0).getDouble("Amount"), 0D);
            }
            assertEquals(before.getCompound("PrivateData"), actual.getCompound("PrivateData"));
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
            root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            CompoundTag before = root.copy(); LegacyEntityDataFixes.prepare(root); assertEquals(before, root);
            root = chunk(entity(alias[0] + ".private", 7D)); before = root.copy();
            LegacyEntityDataFixes.prepare(root); LegacyEntityDataFixes.finish(root); assertEquals(before, root);
        }
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

    private static CompoundTag fixedEntity(CompoundTag root) { return root.getCompound("Level").getList("Entities", 10).getCompound(0); }

    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
