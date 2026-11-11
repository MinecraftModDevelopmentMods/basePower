import com.google.gson.Gson;
import com.mojang.serialization.Dynamic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries;

/** Read-only real Forge61 DFU/codec control for the two retained vanilla-flat 1.13 worlds. */
public final class NativeWorldgenDfuPlatformControl {
    private static Dynamic<Tag> fixed(CompoundTag data, int version) {
        var fixer = DataFixers.getDataFixer();
        Dynamic<Tag> dynamic = DataFixTypes.LEVEL.updateToCurrentVersion(fixer, new Dynamic<>(NbtOps.INSTANCE, data.copy()), version);
        dynamic = dynamic.update("Player", value -> DataFixTypes.PLAYER.updateToCurrentVersion(fixer, value, version));
        return dynamic.update("WorldGenSettings", value -> DataFixTypes.WORLD_GEN_SETTINGS.updateToCurrentVersion(fixer, value, version));
    }

    public static void main(String[] args) throws Exception {
        NativePlatformSupport.requireIndependentClasspath();
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ForgeUnitRegistries.registerVanillaFluidTypes();
        if (SharedConstants.getCurrentVersion().dataVersion().version() != 4671 || args.length != 3) {
            throw new AssertionError("Expected native4671, new output directory and two source level.dat paths");
        }
        Path output = Path.of(args[0]); Files.createDirectory(output);
        Identifier wastes = Identifier.parse("minecraft:nether_wastes");
        boolean forgeContainsBefore = ForgeRegistries.BIOMES.containsKey(wastes);
        RegistryAccess registries = NativePlatformSupport.context(true).registryAccess();
        boolean datapackContains = registries.lookupOrThrow(Registries.BIOME).containsKey(wastes);
        boolean forgeContainsAfter = ForgeRegistries.BIOMES.containsKey(wastes);
        if (!datapackContains || forgeContainsBefore || forgeContainsAfter) {
            throw new AssertionError("Unexpected native static-Forge/data-pack biome registry boundary");
        }
        Tag renamed = DataFixers.getDataFixer().update(References.BIOME,
                new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf("minecraft:nether")), 1631, 4671).getValue();
        if (!renamed.equals(StringTag.valueOf("minecraft:nether_wastes"))) throw new AssertionError("Native Nether biome rename changed");
        Map<String, Object> observations = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index++) {
            Path path = Path.of(args[index]);
            String sourceSha = NativePlatformSupport.sha(Files.readAllBytes(path));
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompound("Data").orElseThrow();
            int version = data.getInt("DataVersion").orElseThrow();
            String generator = data.getString("generatorName").orElseThrow();
            if (version != 1631 || !generator.equals("flat")) throw new AssertionError("Not the reviewed 1.13 vanilla-flat source");
            var dynamic = fixed(data, version);
            Tag settings = dynamic.get("WorldGenSettings").get().getOrThrow().getValue();
            var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            WorldGenSettings decoded = WorldGenSettings.CODEC.parse(ops, settings).getOrThrow();
            if (decoded == null) throw new AssertionError("Missing native decoded world settings");
            CompoundTag worldgen = (CompoundTag) settings;
            CompoundTag dimensions = worldgen.getCompound("dimensions").orElseThrow();
            if (!dimensions.keySet().equals(java.util.Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))) {
                throw new AssertionError("Unexpected source dimension set");
            }
            String nativeGenerator = dimensions.getCompound("minecraft:overworld").orElseThrow()
                    .getCompound("generator").orElseThrow().getString("type").orElseThrow();
            if (!nativeGenerator.equals("minecraft:flat")) throw new AssertionError("Vanilla flat generator was lost");
            CompoundTag invalid = worldgen.copy();
            invalid.getCompound("dimensions").orElseThrow().getCompound("minecraft:overworld").orElseThrow()
                    .getCompound("generator").orElseThrow().putString("type", "fixture:unsupported_generator");
            boolean invalidRejected = false;
            try { WorldGenSettings.CODEC.parse(ops, invalid).getOrThrow(); }
            catch (IllegalStateException expected) { invalidRejected = true; }
            if (!invalidRejected) throw new AssertionError("Invalid generator negative control was accepted");
            CompoundTag evidence = new CompoundTag(); evidence.put("Data", dynamic.getValue());
            // Keep the untouched old Forge registry snapshot beside the independently fixed native Data.
            evidence.put("FML", root.getCompound("FML").orElseThrow().copy());
            Path fixedPath = output.resolve("source-" + index + "-fixed-level.dat"); NbtIo.writeCompressed(evidence, fixedPath);
            if (!sourceSha.equals(NativePlatformSupport.sha(Files.readAllBytes(path)))) throw new AssertionError("Protected source level.dat changed");
            observations.put(path.toString(), Map.of("sourceSha256", sourceSha, "sourceDataVersion", version,
                    "sourceGenerator", generator, "nativeGenerator", nativeGenerator, "nativeWorldGenCodec", "PASS",
                    "invalidGeneratorRejected", true, "fixedEvidenceSha256", NativePlatformSupport.sha(Files.readAllBytes(fixedPath)),
                    "sourceUnchanged", true));
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("status", "PASS"); receipt.put("scope", "READ_ONLY_NATIVE_DFU_AND_WORLDGEN_CODEC_DIAGNOSIS");
        receipt.put("noPower", true); receipt.put("noHarness", true); receipt.put("noServerStartedOrTicks", true);
        receipt.put("nativeDfuBiomeRename", "minecraft:nether -> minecraft:nether_wastes");
        receipt.put("forgeBiomeRegistryContainsBeforeDatapacks", forgeContainsBefore);
        receipt.put("forgeBiomeRegistryContainsAfterDatapacks", forgeContainsAfter);
        receipt.put("nativeDatapackBiomeRegistryContainsTarget", datapackContains);
        receipt.put("sourceWorlds", observations);
        NativePlatformSupport.sourceEvidence(receipt, net.minecraft.util.datafix.DataFixers.class,
                net.minecraft.world.level.storage.LevelStorageSource.class, ForgeRegistries.class, WorldGenSettings.class);
        receipt.put("controlSourceSha256", NativePlatformSupport.sha(Files.readAllBytes(Path.of("platform-controls/NativeWorldgenDfuPlatformControl.java"))));
        Files.writeString(output.resolve("receipt.json"), new Gson().toJson(receipt) + "\n");
        System.out.println("TWO_REAL_SOURCE_LEVELS_NATIVE_DFU_AND_CODEC_PASS static_forge_biome_missing=true native_datapack_biome_present=true");
    }
}
