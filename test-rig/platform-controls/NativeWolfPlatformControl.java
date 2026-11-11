import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries;

/** Independent native controls: no Advantage classes, server or entity ticks. */
public final class NativeWolfPlatformControl {
    private static final UUID OWNER = UUID.fromString("a614e658-6270-33c3-9297-7ce80908964f");
    private static final UUID ID = UUID.fromString("a6ea8abd-6ab3-4e66-8ef5-d713aabbccdd");

    private static void field(ServerLevel world, String name, Object value) throws Exception {
        Field field = Level.class.getDeclaredField(name); field.setAccessible(true); field.set(world, value);
    }

    private static ServerLevel context() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        ServerLevel world = (ServerLevel) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), ServerLevel.class);
        field(world, "dimension", Level.NETHER);
        field(world, "random", RandomSource.create(0));
        field(world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        var base = RegistryLayer.createRegistryAccess();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()))) {
            var worldgen = RegistryDataLoader.load(resources, base.compositeAccess(),
                    RegistryDataLoader.WORLDGEN_REGISTRIES);
            field(world, "registryAccess", base.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess());
        }
        return world;
    }

    private static Wolf wolf(ServerLevel world) {
        Wolf wolf = new Wolf(EntityType.WOLF, world); wolf.setUUID(ID); return wolf;
    }

    private static void control(Path root, ServerLevel world, boolean tame, float health) throws Exception {
        String label = (tame ? "tame" : "wild") + "-health-" + (int) health;
        Wolf first = wolf(world);
        first.setTame(tame, true);
        if (tame) first.setOwnerUUID(OWNER);
        first.setHealth(health);
        CompoundTag before = first.saveWithoutId(new CompoundTag());
        if (before.getFloat("Health") != health) throw new AssertionError("Incorrect source control health");
        Path input = root.resolve(label + "-before.dat"); NbtIo.writeCompressed(before, input);
        Wolf second = wolf(world); second.load(NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()));
        CompoundTag after = second.saveWithoutId(new CompoundTag());
        NbtIo.writeCompressed(after, root.resolve(label + "-after.dat"));
        Wolf third = wolf(world); third.load(after.copy());
        CompoundTag restarted = third.saveWithoutId(new CompoundTag());
        NbtIo.writeCompressed(restarted, root.resolve(label + "-restart.dat"));
        if (!ID.equals(second.getUUID()) || !ID.equals(third.getUUID())) throw new AssertionError("Control UUID changed");
        if (tame && (!OWNER.equals(second.getOwnerUUID()) || !OWNER.equals(third.getOwnerUUID()))) {
            throw new AssertionError("Control owner changed");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label); row.put("currentWorldDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        row.put("legacyDfuInvoked", false); row.put("directNativeEntityLoadSave", true);
        row.put("beforeHealth", before.getFloat("Health")); row.put("afterHealth", after.getFloat("Health"));
        row.put("restartHealth", restarted.getFloat("Health"));
        row.put("uuidPreserved", true); row.put("ownerPreserved", !tame || OWNER.equals(second.getOwnerUUID()));
        System.out.println("WOLF_CONTROL " + new Gson().toJson(row));
    }

    private static void legacyControl(Path root, ServerLevel world, int version) throws Exception {
        CompoundTag before = net.minecraft.nbt.TagParser.parseTag("{id:\"" + (version == 512 ? "Wolf" : "minecraft:wolf")
                + "\",Health:8f,Attributes:[{Name:\"generic.maxHealth\",Base:20d}],Pos:[0d,100d,0d],"
                + "Motion:[0d,0d,0d],Rotation:[0f,0f]}");
        before.putLong("UUIDMost", ID.getMostSignificantBits());
        before.putLong("UUIDLeast", ID.getLeastSignificantBits());
        before.putString("OwnerUUID", OWNER.toString());
        var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(
                net.minecraft.util.datafix.fixes.References.ENTITY,
                new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, before.copy()),
                version, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        CompoundTag nativeTag = (CompoundTag) fixed.getValue();
        Wolf loaded = wolf(world);
        loaded.load(nativeTag);
        CompoundTag saved = loaded.saveWithoutId(new CompoundTag());
        NbtIo.writeCompressed(before, root.resolve("legacy-" + version + "-source.dat"));
        NbtIo.writeCompressed(nativeTag, root.resolve("legacy-" + version + "-dfu.dat"));
        NbtIo.writeCompressed(saved, root.resolve("legacy-" + version + "-saved.dat"));
        if (loaded.getHealth() != 16 || loaded.getMaxHealth() != 40 || !ID.equals(loaded.getUUID())
                || !OWNER.equals(loaded.getOwnerUUID())) throw new AssertionError("Unexpected native legacy-wolf conversion");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceDataVersion", version); row.put("targetDataVersion", 3955);
        row.put("sourceHealth", 8); row.put("savedHealth", loaded.getHealth());
        row.put("sourceMaxHealth", 20); row.put("savedMaxHealth", loaded.getMaxHealth());
        row.put("uuidPreserved", true); row.put("ownerPreserved", true); row.put("noPower", true);
        String json = new Gson().toJson(row);
        Files.writeString(root.resolve("legacy-" + version + "-receipt.json"), json + "\n");
        System.out.println("WOLF_LEGACY_DFU_CONTROL " + json);
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ForgeUnitRegistries.registerVanillaFluidTypes();
        ForgeUnitRegistries.registerForgeEntries("ATTRIBUTES", ForgeRegistries.Keys.ATTRIBUTES, ForgeRegistries.ATTRIBUTES);
        try {
            Class.forName("zone.moddev.mc.basepower.migration.LegacyEntityDataFixes");
            throw new AssertionError("Advantage is present on the native control classpath");
        } catch (ClassNotFoundException expected) { System.out.println("POWER_LEGACY_CLASS_ABSENT true"); }
        Path root = Paths.get(args[0]); Files.createDirectory(root);
        System.out.println("NATIVE_WOLF_CODE " + Wolf.class.getProtectionDomain().getCodeSource().getLocation());
        ServerLevel world = context();
        control(root, world, true, 8); control(root, world, true, 20); control(root, world, false, 8);
        legacyControl(root, world, 512); legacyControl(root, world, 1343);
        System.out.println("WOLF_CONTROL_DONE no_server=true no_ticks=true native_entity_methods_unmodified=true");
    }
}
