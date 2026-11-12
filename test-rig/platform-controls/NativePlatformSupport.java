import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Standalone controls deliberately share no harness persistence or world fixtures. */
final class NativePlatformSupport {
    private NativePlatformSupport() {}

    static void requireIndependentClasspath() throws Exception {
        for (String name : List.of("zone.moddev.mc.basepower.migration.LegacyEntityDataFixes",
                "zone.moddev.mc.advantageworks.NativeActorIo")) {
            try {
                Class.forName(name);
                throw new AssertionError("Advantage/harness must be absent from the native control: " + name);
            } catch (ClassNotFoundException expected) { }
        }
        Field nativeMap = net.minecraft.util.datafix.fixes.BlockStateData.class.getDeclaredField("MAP");
        if (nativeMap.getModifiers() != (Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL)) {
            throw new AssertionError("Power's BlockStateData.MAP access transformer must be absent from the native control");
        }
        System.out.println("POWER_AND_HARNESS_CLASSES_ABSENT true");
        System.out.println("POWER_ACCESS_TRANSFORMER_ABSENT true");
    }

    static ServerLevel context(boolean worldgen) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        ServerLevel world = (ServerLevel) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), ServerLevel.class);
        zone.moddev.mc.advantageworks.unit.NativeEmptyEntityTracker.install(world);
        field(world, "dimension", Level.NETHER);
        field(world, "random", RandomSource.create(0));
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        if (worldgen) {
            var base = RegistryLayer.createRegistryAccess();
            try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                    List.of(ServerPacksSource.createVanillaPackSource()))) {
                var tags = TagLoader.loadTagsForExistingRegistries(resources, base.getLayer(RegistryLayer.STATIC));
                var lookups = TagLoader.buildUpdatedLookups(base.getAccessForLoading(RegistryLayer.WORLDGEN), tags);
                var loaded = RegistryDataLoader.load(resources, lookups,
                        RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
                registries = base.replaceFrom(RegistryLayer.WORLDGEN, loaded).compositeAccess();
            }
        }
        field(world, "registryAccess", registries);
        return world;
    }

    private static void field(ServerLevel world, String name, Object value) throws Exception {
        Field field = Level.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(world, value);
    }

    static ProblemReporter reporter(String path) {
        return new ProblemReporter() {
            @Override public ProblemReporter forChild(PathElement child) { return reporter(path + child.get()); }
            @Override public void report(Problem problem) {
                throw new IllegalStateException("Native control codec failure at " + path + ": " + problem.description());
            }
        };
    }

    static TagValueOutput output(Entity entity) {
        return TagValueOutput.createWithContext(reporter(entity.getClass().getName()), entity.registryAccess());
    }

    static CompoundTag observe(Entity entity) {
        TagValueOutput output = output(entity);
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    static void load(Entity entity, CompoundTag nbt) {
        entity.load(TagValueInput.create(reporter(entity.getClass().getName()), entity.registryAccess(), nbt));
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String classHash(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new AssertionError("No native class bytes: " + type.getName());
            return sha(input.readAllBytes());
        }
    }

    static Map<String, String> classHashes(Class<?>... types) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Class<?> type : types) result.put(type.getName(), classHash(type));
        return result;
    }

    static void sourceEvidence(Map<String, Object> proof, Class<?>... types) throws Exception {
        Path engine = Path.of(Entity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(engine)) throw new AssertionError("Native engine is not a jar: " + engine);
        String name = engine.getFileName().toString();
        Path sources = engine.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
        proof.put("engineJarSha256", sha(Files.readAllBytes(engine)));
        proof.put("engineSourcesSha256", sha(Files.readAllBytes(sources)));
        Map<String, String> hashes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(sources.toFile())) {
            for (Class<?> type : types) {
                String path = type.getName().replace('.', '/') + ".java";
                var entry = zip.getEntry(path);
                if (entry == null) throw new AssertionError("Missing pinned native source: " + path);
                try (InputStream input = zip.getInputStream(entry)) { hashes.put(path, sha(input.readAllBytes())); }
            }
        }
        proof.put("nativeSourceSha256", hashes);
        Map<String, String> controls = new LinkedHashMap<>();
        for (String source : List.of("NativePlatformSupport.java", "NativeLeashKnotPlatformControl.java")) {
            controls.put(source, sha(Files.readAllBytes(Path.of("platform-controls", source))));
        }
        controls.put("NativeEmptyEntityTracker.java", sha(Files.readAllBytes(Path.of(
                "unit-support/zone/moddev/mc/advantageworks/unit/NativeEmptyEntityTracker.java"))));
        proof.put("controlSourceSha256", controls);
    }
}
