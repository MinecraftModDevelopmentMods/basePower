package zone.moddev.mc.advantageworks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.versions.forge.ForgeVersion;

/** Independent registry authority for compact and partially specified native palettes. */
final class Native263BlockStateCatalog {
    static final String FILE = "native263-block-state-catalog.json";

    static void register() {
        if (new File("advantage-works.allow").isFile())
            ServerStartedEvent.BUS.addListener(Native263BlockStateCatalog::started);
    }

    private static void started(ServerStartedEvent event) {
        if (!event.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration"))
            throw new IllegalStateException("Refusing a block catalog outside the marked disposable world");
        try {
            JsonObject result = new JsonObject();
            result.addProperty("format", 1);
            result.addProperty("engineProtocol", "NATIVE66");
            result.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
            result.addProperty("forge", ForgeVersion.getVersion());
            result.addProperty("dataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            result.addProperty("javaRuntimeVersion", Runtime.version().toString());
            JsonObject mods = new JsonObject();
            TreeMap<String, String> versions = new TreeMap<>();
            for (var mod : ModList.getMods()) {
                if (versions.put(mod.getModId(), mod.getVersion().toString()) != null)
                    throw new IllegalStateException("Duplicate catalog mod: " + mod.getModId());
            }
            versions.forEach(mods::addProperty);
            result.add("mods", mods);
            JsonObject jars = new JsonObject();
            try (var paths = Files.list(Path.of("mods"))) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (!path.getFileName().toString().endsWith(".jar") || Files.isSymbolicLink(path))
                        throw new IllegalStateException("Unexpected catalog mod input: " + path);
                    jars.addProperty(path.getFileName().toString(), HexFormat.of().withUpperCase().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                }
            }
            result.add("modJars", jars);
            JsonObject blocks = new JsonObject();
            TreeMap<String, Block> registered = new TreeMap<>();
            for (Block block : BuiltInRegistries.BLOCK)
                registered.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block);
            int states = 0;
            for (var block : registered.entrySet()) {
                blocks.add(block.getKey(), entry(block.getValue()));
                states += block.getValue().getStateDefinition().getPossibleStates().size();
            }
            result.addProperty("blockCount", registered.size());
            result.addProperty("stateCount", states);
            result.add("blocks", blocks);
            retain(Path.of(FILE), result);
            System.out.println("NATIVE263 BLOCK STATE CATALOG VERIFIED blocks=" + registered.size() + " states=" + states);
        } catch (Exception error) {
            throw new IllegalStateException("Independent native block-state catalog failed", error);
        }
    }

    static void retain(Path output, JsonObject catalog) throws Exception {
        String bytes = new GsonBuilder().setPrettyPrinting().create().toJson(catalog) + "\n";
        if (Files.exists(output)) {
            if (Files.isSymbolicLink(output) || !Files.readString(output, StandardCharsets.UTF_8).equals(bytes))
                throw new IllegalStateException("Native block-state catalog changed across the full restart");
        } else {
            Files.writeString(output, bytes, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    static JsonObject entry(Block block) {
        BlockState state = block.defaultBlockState();
        var encoded = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        BlockState decoded = BlockState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        if (decoded != state) throw new AssertionError("Native default block-state CODEC did not round-trip exactly");
        JsonObject result = new JsonObject();
        result.add("defaults", properties(state));
        JsonObject legal = new JsonObject();
        for (Property<?> property : block.getStateDefinition().getProperties()) legal.add(property.getName(), values(property));
        result.add("legalValues", legal);
        result.addProperty("stateCount", block.getStateDefinition().getPossibleStates().size());
        result.add("defaultEncoded", encoded);
        JsonObject full = new JsonObject();
        full.addProperty("id", BuiltInRegistries.BLOCK.getKey(decoded.getBlock()).toString());
        full.add("properties", properties(decoded));
        result.add("defaultDecoded", full);
        return result;
    }

    static JsonObject properties(BlockState state) {
        JsonObject result = new JsonObject();
        for (Property<?> property : state.getProperties()) result.addProperty(property.getName(), value(state, property));
        return result;
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> JsonArray values(Property<T> property) {
        JsonArray result = new JsonArray();
        property.getPossibleValues().stream().map(property::getName).sorted().forEach(result::add);
        return result;
    }
}
