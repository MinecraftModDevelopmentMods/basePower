import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries;

/** Independent native controls: no Advantage classes, running server or entity ticks. */
public final class NativeWolfPlatformControl {
    private static final UUID OWNER = UUID.fromString("a614e658-6270-33c3-9297-7ce80908964f");
    private static final UUID ID = UUID.fromString("a6ea8abd-6ab3-4e66-8ef5-d713aabbccdd");

    private static void runtimeEvidence(Path root) throws Exception {
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            if (!System.getenv().getOrDefault(key, "").isBlank())
                throw new AssertionError("Native control inherited " + key);
        }
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("scope", "WORLDLESS_INDEPENDENT_NATIVE_WOLF_CONTROL_NOT_GAMEPLAY");
        proof.put("minecraftVersion", SharedConstants.getCurrentVersion().name());
        proof.put("targetDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(java)) java = java.resolveSibling("java");
        proof.put("java", java.toString());
        proof.put("javaSha256", NativePlatformSupport.sha(Files.readAllBytes(java)));
        proof.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        proof.put("mainClass", "NativeWolfPlatformControl");
        proof.put("inheritedJvmEnvironmentAbsent", true);
        List<Map<String, Object>> paths = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path.toString());
            if (Files.isRegularFile(path)) row.put("sha256", NativePlatformSupport.sha(Files.readAllBytes(path)));
            else if (Files.isDirectory(path)) {
                Map<String, String> files = new LinkedHashMap<>();
                try (var stream = Files.walk(path)) {
                    for (Path file : stream.filter(Files::isRegularFile).sorted().toList())
                        files.put(path.relativize(file).toString(), NativePlatformSupport.sha(Files.readAllBytes(file)));
                }
                row.put("files", files);
            } else throw new AssertionError("Missing control classpath: " + path);
            paths.add(row);
        }
        proof.put("classpath", paths);
        proof.put("nativeClassSha256", NativePlatformSupport.classHashes(Wolf.class, net.minecraft.world.entity.Entity.class));
        Map<String, String> source = new LinkedHashMap<>();
        for (String name : List.of("platform-controls/NativeWolfPlatformControl.java",
                "platform-controls/NativePlatformSupport.java",
                "unit-support/zone/moddev/mc/advantageworks/unit/NativeEmptyEntityTracker.java"))
            source.put(name, NativePlatformSupport.sha(Files.readAllBytes(Path.of(name))));
        proof.put("controlSourceSha256", source);
        Files.writeString(root.resolve("control-runtime.json"), new Gson().toJson(proof) + "\n");
    }

    private static ServerLevel context() throws Exception {
        ServerLevel world = NativePlatformSupport.context(true);
        // Native navigation now consults debug subscribers even before any entity ticks.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        var server = (net.minecraft.server.MinecraftServer) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), net.minecraft.server.dedicated.DedicatedServer.class);
        Field debug = net.minecraft.server.MinecraftServer.class.getDeclaredField("debugSubscribers");
        debug.setAccessible(true);
        debug.set(server, new net.minecraft.util.debug.ServerDebugSubscribers(server));
        Field host = ServerLevel.class.getDeclaredField("server"); host.setAccessible(true); host.set(world, server);
        return world;
    }

    private static Wolf wolf(ServerLevel world) {
        Wolf wolf = new Wolf(net.minecraft.world.entity.EntityTypes.WOLF, world); wolf.setUUID(ID); return wolf;
    }

    private static void control(Path root, ServerLevel world, boolean tame, float health) throws Exception {
        String label = (tame ? "tame" : "wild") + "-health-" + (int) health;
        Wolf first = wolf(world);
        first.setTame(tame, true);
        if (tame) first.setOwnerReference(EntityReference.of(OWNER));
        first.setHealth(health);
        CompoundTag before = NativePlatformSupport.observe(first);
        if (before.getFloat("Health").orElseThrow() != health) throw new AssertionError("Incorrect source control health");
        Path input = root.resolve(label + "-before.dat"); NbtIo.writeCompressed(before, input);
        Wolf second = wolf(world); NativePlatformSupport.load(second, NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()));
        CompoundTag after = NativePlatformSupport.observe(second);
        NbtIo.writeCompressed(after, root.resolve(label + "-after.dat"));
        Wolf third = wolf(world); NativePlatformSupport.load(third, after.copy());
        CompoundTag restarted = NativePlatformSupport.observe(third);
        NbtIo.writeCompressed(restarted, root.resolve(label + "-restart.dat"));
        if (second.getHealth() != health || third.getHealth() != health || second.isTame() != tame || third.isTame() != tame
                || second.getMaxHealth() != (tame ? 40 : 8) || third.getMaxHealth() != (tame ? 40 : 8)
                || first.tickCount != 0 || second.tickCount != 0 || third.tickCount != 0 || world.getServer().isRunning()
                || !before.equals(after) || !after.equals(restarted)) {
            throw new AssertionError("Native current-wolf round trip drifted");
        }
        if (!ID.equals(second.getUUID()) || !ID.equals(third.getUUID())) throw new AssertionError("Control UUID changed");
        if (tame && (second.getOwnerReference() == null || third.getOwnerReference() == null
                || !OWNER.equals(second.getOwnerReference().getUUID()) || !OWNER.equals(third.getOwnerReference().getUUID()))) {
            throw new AssertionError("Control owner changed");
        }
        if (!tame && (second.getOwnerReference() != null || third.getOwnerReference() != null))
            throw new AssertionError("Native wild-wolf control acquired an owner");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label); row.put("currentWorldDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        row.put("legacyDfuInvoked", false); row.put("directNativeEntityLoadSave", true);
        row.put("beforeHealth", before.getFloat("Health").orElseThrow()); row.put("afterHealth", after.getFloat("Health").orElseThrow());
        row.put("restartHealth", restarted.getFloat("Health").orElseThrow());
        row.put("uuidPreserved", true); row.put("ownerPreserved", !tame || OWNER.equals(second.getOwnerReference().getUUID()));
        row.put("serverContext", "unstarted-native-debug-subscriber-fixture"); row.put("noServerStarted", true);
        System.out.println("WOLF_CONTROL " + new Gson().toJson(row));
    }

    private static void legacyControl(Path root, ServerLevel world, int version) throws Exception {
        CompoundTag before = net.minecraft.nbt.TagParser.parseCompoundFully("{id:\"" + (version == 512 ? "Wolf" : "minecraft:wolf")
                + "\",Health:8f,Attributes:[{Name:\"generic.maxHealth\",Base:20d}],Pos:[0d,100d,0d],"
                + "Motion:[0d,0d,0d],Rotation:[0f,0f]}");
        before.putLong("UUIDMost", ID.getMostSignificantBits());
        before.putLong("UUIDLeast", ID.getLeastSignificantBits());
        before.putString("OwnerUUID", OWNER.toString());
        var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(
                net.minecraft.util.datafix.fixes.References.ENTITY,
                new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, before.copy()),
                version, SharedConstants.getCurrentVersion().dataVersion().version());
        CompoundTag nativeTag = (CompoundTag) fixed.getValue();
        Wolf loaded = wolf(world);
        NativePlatformSupport.load(loaded, nativeTag);
        CompoundTag saved = NativePlatformSupport.observe(loaded);
        NbtIo.writeCompressed(before, root.resolve("legacy-" + version + "-source.dat"));
        NbtIo.writeCompressed(nativeTag, root.resolve("legacy-" + version + "-dfu.dat"));
        NbtIo.writeCompressed(saved, root.resolve("legacy-" + version + "-saved.dat"));
        if (loaded.getHealth() != 16 || loaded.getMaxHealth() != 40 || !ID.equals(loaded.getUUID())
                || loaded.getOwnerReference() == null || !OWNER.equals(loaded.getOwnerReference().getUUID()))
            throw new AssertionError("Unexpected native legacy-wolf conversion");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceDataVersion", version); row.put("targetDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        row.put("sourceHealth", 8); row.put("savedHealth", loaded.getHealth());
        row.put("sourceMaxHealth", 20); row.put("savedMaxHealth", loaded.getMaxHealth());
        row.put("uuidPreserved", true); row.put("ownerPreserved", true); row.put("noPower", true);
        String json = new Gson().toJson(row);
        Files.writeString(root.resolve("legacy-" + version + "-receipt.json"), json + "\n");
        System.out.println("WOLF_LEGACY_DFU_CONTROL " + json);
    }

    public static void main(String[] args) throws Exception {
        NativePlatformSupport.requireIndependentClasspath();
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ForgeUnitRegistries.registerVanillaFluidTypes();
        ForgeUnitRegistries.registerForgeEntries("ATTRIBUTES", net.minecraft.core.registries.Registries.ATTRIBUTE, net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES);
        try {
            Class.forName("zone.moddev.mc.basepower.migration.LegacyEntityDataFixes");
            throw new AssertionError("Advantage is present on the native control classpath");
        } catch (ClassNotFoundException expected) { System.out.println("POWER_LEGACY_CLASS_ABSENT true"); }
        Path root = Paths.get(args[0]); Files.createDirectory(root);
        System.out.println("NATIVE_WOLF_CODE " + Wolf.class.getProtectionDomain().getCodeSource().getLocation());
        ServerLevel world = context();
        control(root, world, true, 8); control(root, world, true, 20); control(root, world, false, 8);
        legacyControl(root, world, 512); legacyControl(root, world, 1343);
        runtimeEvidence(root);
        System.out.println("WOLF_CONTROL_DONE no_server_started=true no_ticks=true native_entity_methods_unmodified=true");
    }
}
