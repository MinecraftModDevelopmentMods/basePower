package zone.moddev.mc.basepower.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.jarhandling.SecureJar;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.resources.Identifier;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.loading.targets.ForgeDevLocator;
import net.minecraftforge.resource.ResourcePackLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Runs native Forge resource loading in an isolated VM, not dependency or gameplay qualification. */
public final class NativePackResourceProof {
    private static JsonObject receipt;

    private NativePackResourceProof() {}

    public static synchronized JsonObject read() throws Exception {
        if (receipt != null) return receipt.deepCopy();
        Path evidenceRoot = Path.of("build/native-pack-resources").toAbsolutePath();
        Files.createDirectories(evidenceRoot);
        Path evidence = Files.createTempDirectory(evidenceRoot, "forge64-");
        String classpath = Arrays.stream(System.getProperty("basepower.nativePackClasspath",
                System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(entry -> new java.io.File(entry).getAbsolutePath())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(javaExecutable)) javaExecutable = javaExecutable.resolveSibling("java.exe");
        Path log = evidence.resolve("forge-resources.log");
        Process process = proofProcess(javaExecutable, classpath,
                Path.of("build/resources/main").toAbsolutePath(), evidence, log).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new AssertionError("Native Forge resource proof timed out: " + log);
        }
        String output = Files.readString(log);
        if (process.exitValue() != 0) throw new AssertionError("Native Forge resource proof failed: " + log + "\n" + output);
        requireCleanOutput(output, log);
        receipt = JsonParser.parseString(Files.readString(evidence.resolve("receipt.json"))).getAsJsonObject();
        return receipt.deepCopy();
    }

    static ProcessBuilder proofProcess(Path javaExecutable, String classpath, Path resources, Path evidence, Path log) {
        // The child has redirected output, not an interactive terminal. Disable only JLine, never diagnostics.
        ProcessBuilder builder = new ProcessBuilder(javaExecutable.toString(), "-XX:ActiveProcessorCount=4", "-Xmx512m",
                "-Dterminal.jline=false", "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED", "-cp", classpath,
                NativePackResourceProof.class.getName(), resources.toString(), evidence.toString())
                .directory(evidence.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("TERM", "dumb");
        return builder;
    }

    static void requireCleanOutput(String output, Path log) {
        if (java.util.regex.Pattern.compile("(?m)(?:\\[[^\\r\\n]*\\b(?:WARN|ERROR|FATAL)\\b[^\\r\\n]*\\]|\\bmain (?:WARN|ERROR|FATAL)\\b)")
                .matcher(output).find()) {
            throw new AssertionError("Native Forge resource diagnostic in " + log + "\n" + output);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!"false".equals(System.getProperty("terminal.jline"))) {
            throw new AssertionError("The redirected proof child must not initialise an interactive terminal");
        }
        SharedConstants.tryDetectVersion();
        if (Runtime.version().feature() != 25
                || !"26.2".equals(SharedConstants.getCurrentVersion().name())
                || !"65.1.0".equals(net.minecraftforge.versions.forge.ForgeVersion.getVersion())
                || !net.minecraft.server.packs.metadata.pack.PackFormat.of(88, 0).equals(
                        SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES))
                || !net.minecraft.server.packs.metadata.pack.PackFormat.of(107, 1).equals(
                        SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA))) {
            throw new AssertionError("Resource proof requires actual Forge 65.1.0 / Minecraft 26.2 on Java 25");
        }
        Bootstrap.bootStrap();
        Path resources = Path.of(args[0]);
        Path evidence = Path.of(args[1]);
        Path jar = evidence.resolve("basepower-resource-contract.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "basepower");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
                var files = Files.walk(resources)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(resources.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        ModFile mod = new ModFile(SecureJar.from(jar), new ForgeDevLocator(), ModFileParser::modsTomlParser);
        if (mod.getModFileInfo().showAsResourcePack()) throw new AssertionError("Power must use Forge's hidden client pack");
        initializeResourceDiscovery(mod);
        List<String> diagnostics = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("PowerPackContract", null,
                PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.WARN)) diagnostics.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        var logger = (org.apache.logging.log4j.core.Logger)LogManager.getRootLogger();
        logger.addAppender(appender);
        JsonObject result = new JsonObject();
        result.addProperty("interactiveTerminalDisabled", true);
        result.addProperty("discoveryScope", "RESOURCE_PACK_ONLY");
        result.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
        result.addProperty("forge", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        result.addProperty("javaFeature", Runtime.version().feature());
        JsonArray dependencies = new JsonArray();
        for (var dependency : mod.getModInfos().getFirst().getDependencies()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("modId", dependency.getModId());
            entry.addProperty("mandatory", dependency.isMandatory());
            entry.addProperty("versionRange", dependency.getVersionRange().toString());
            entry.addProperty("ordering", dependency.getOrdering().name());
            entry.addProperty("side", dependency.getSide().name());
            dependencies.add(entry);
        }
        result.add("dependencies", dependencies);
        try {
            for (PackType type : PackType.values()) {
                PackRepository repository = new PackRepository();
                ResourcePackLoader.loadResourcePacks(repository, type == PackType.CLIENT_RESOURCES);
                repository.reload();
                String id = type == PackType.CLIENT_RESOURCES ? "mod_resources" : "mod:basepower";
                if (repository.getAvailableIds().size() != 1 || !repository.isAvailable(id)) {
                    throw new AssertionError("Unexpected native packs: " + repository.getAvailableIds());
                }
                Pack pack = repository.getPack(id);
                if (pack == null) throw new AssertionError("Forge did not discover " + id);
                JsonObject side = new JsonObject();
                side.addProperty("id", id);
                side.addProperty("loaderCompatibility", pack.getCompatibility().name());
                try (var opened = pack.open()) {
                    PackMetadataSection metadata = opened.getMetadataSection(PackMetadataSection.forPackType(type));
                    if (metadata == null) throw new AssertionError("Missing native " + type + " metadata");
                    var formats = metadata.supportedFormats();
                    var codec = PackMetadataSection.forPackType(type).codec();
                    var encoded = codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, metadata).getOrThrow();
                    var decoded = codec.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
                    // Native JSON widens numeric translation arguments; compare the wire value and exact range.
                    if (!formats.equals(decoded.supportedFormats())
                            || !encoded.equals(codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, decoded).getOrThrow())) {
                        throw new AssertionError("Native metadata round-trip changed " + type);
                    }
                    side.addProperty("resourcesClass", opened.getClass().getName());
                    side.addProperty("acceptsClient", formats.isValueInRange(SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES)));
                    side.addProperty("acceptsServer", formats.isValueInRange(SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)));
                    side.addProperty("accepts83", formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(83)));
                    side.addProperty("accepts102", formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(102)));
                    side.addProperty("acceptsPreviousClient", formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(75, 0)));
                    side.addProperty("acceptsPreviousServer", formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(94, 1)));
                    side.add("nativeMetadata", encoded);
                    if (type == PackType.CLIENT_RESOURCES) {
                        if (opened.getChildren() == null || opened.getChildren().size() != 1) {
                            throw new AssertionError("Expected the actual Forge client delegate");
                        }
                        side.addProperty("currentCompatibility", pack.getCompatibility().name());
                    } else {
                        var supplier = new PathPackResources.PathResourcesSupplier(mod.findResource(""));
                        var current = Pack.readPackMetadata(pack.location(), supplier,
                                SharedConstants.getCurrentVersion().packVersion(type), type);
                        if (current == null) throw new AssertionError("Native current-version server metadata was rejected");
                        side.addProperty("currentCompatibility", current.compatibility().name());
                    }
                }
                repository.setSelected(List.of(id));
                int checked = 0;
                try (MultiPackResourceManager manager = new MultiPackResourceManager(type, repository.openAllSelected());
                        var files = Files.walk(resources.resolve(type.getDirectory()))) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        Path relative = resources.resolve(type.getDirectory()).relativize(file);
                        String namespace = relative.getName(0).toString();
                        String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                        var resource = manager.getResource(Identifier.fromNamespaceAndPath(namespace, path)).orElseThrow();
                        try (var input = resource.open()) {
                            if (!Arrays.equals(Files.readAllBytes(file), input.readAllBytes())) {
                                throw new AssertionError("Forge changed " + type + " resource " + relative);
                            }
                        }
                        checked++;
                    }
                }
                if (checked == 0) throw new AssertionError("No native " + type + " resources were read");
                side.addProperty("resourcesVerified", checked);
                result.add(type.name(), side);
            }
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        JsonArray messages = new JsonArray();
        diagnostics.forEach(messages::add);
        result.add("warningsAndErrors", messages);
        Files.writeString(evidence.resolve("receipt.json"), result.toString(), StandardCharsets.UTF_8);
        if (!diagnostics.isEmpty()) throw new AssertionError("Native Forge resource diagnostics: " + diagnostics);
        System.out.println("BASEPOWER_NATIVE_PACK_RESOURCES_PASS " + result);
    }

    private static void initializeResourceDiscovery(ModFile mod) throws Exception {
        if (net.minecraftforge.fml.loading.FMLLoader.getGamePath() != null) {
            throw new AssertionError("Resource-only discovery must not run inside Forge");
        }
        // Forge64 removed ModList.of. Seed its native lazy discovery record without claiming
        // dependency sorting, access-transformer application, or loading production mod classes.
        Class<?> stateType = Class.forName("net.minecraftforge.fml.loading.ModSorter$State");
        var constructor = stateType.getDeclaredConstructor(List.class, List.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(List.of(mod),
                mod.getModInfos().stream().map(ModInfo.class::cast).toList());
        Class<?> discovery = Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl");
        var temporaryState = discovery.getDeclaredField("temp");
        temporaryState.setAccessible(true);
        if (temporaryState.get(null) != null) throw new AssertionError("Discovery is already initializing");
        temporaryState.set(null, state);
        try {
            ModList.init();
        } finally {
            temporaryState.set(null, null);
        }
        if (ModList.getMods().size() != 1 || !ModList.getModFiles().equals(List.of(mod.getModFileInfo()))
                || !ModList.getMods().equals(mod.getModInfos())
                || !net.minecraftforge.fml.loading.LoadingModList.getErrors().isEmpty()
                || !net.minecraftforge.fml.loading.LoadingModList.getBrokenFiles().isEmpty()) {
            throw new AssertionError("Native resource-only discovery did not retain the exact parsed mod");
        }
    }
}
