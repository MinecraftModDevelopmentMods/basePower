import com.google.gson.Gson;
import com.mojang.serialization.Dynamic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

/** Real native DFU and respawn codec against retained player files, without mods or a server. */
public final class NativePlayerFormatPlatformControl {
    public static void main(String[] args) throws Exception {
        NativePlatformSupport.requireIndependentClasspath();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int targetVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (targetVersion != 4671 || args.length < 4 || args.length % 3 != 1) {
            throw new AssertionError("Expected an output directory and label/source/saved triples for native4671");
        }
        Path output = Path.of(args[0]);
        Files.createDirectory(output);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("targetDataVersion", targetVersion);
        proof.put("noPower", true);
        proof.put("noHarness", true);
        proof.put("noServerOrTicks", true);
        NativePlatformSupport.sourceEvidence(proof,
                net.minecraft.util.datafix.fixes.PlayerEquipmentFix.class,
                net.minecraft.util.datafix.fixes.InlineBlockPosFormatFix.class,
                net.minecraft.util.datafix.fixes.PlayerRespawnDataFix.class,
                net.minecraft.world.entity.LivingEntity.class,
                net.minecraft.server.level.ServerPlayer.class);
        proof.put("nativeClassSha256", NativePlatformSupport.classHashes(
                net.minecraft.util.datafix.fixes.PlayerEquipmentFix.class,
                net.minecraft.util.datafix.fixes.InlineBlockPosFormatFix.class,
                net.minecraft.util.datafix.fixes.PlayerRespawnDataFix.class));
        proof.put("controlSourceSha256", NativePlatformSupport.sha(Files.readAllBytes(
                Path.of("platform-controls/NativePlayerFormatPlatformControl.java"))));
        Map<String, Object> observations = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 3) {
            String label = args[index];
            Path sourcePath = Path.of(args[index + 1]);
            Path savedPath = Path.of(args[index + 2]);
            CompoundTag source = NbtIo.readCompressed(sourcePath, NbtAccounter.unlimitedHeap());
            CompoundTag saved = NbtIo.readCompressed(savedPath, NbtAccounter.unlimitedHeap());
            int sourceVersion = source.getInt("DataVersion").orElseThrow();
            if (sourceVersion >= targetVersion || saved.getInt("DataVersion").orElseThrow() != targetVersion) {
                throw new AssertionError("Wrong source or actual saved player version: " + label);
            }
            CompoundTag fixed = (CompoundTag) DataFixers.getDataFixer().update(References.PLAYER,
                    new Dynamic<>(NbtOps.INSTANCE, source.copy()), sourceVersion, targetVersion).getValue();
            for (String field : List.of("Inventory", "EnderItems", "equipment", "respawn")) {
                if (!Objects.equals(fixed.get(field), saved.get(field))) {
                    throw new AssertionError(label + ": independent native DFU differs from actual saved " + field
                            + " expected=" + fixed.get(field) + " saved=" + saved.get(field));
                }
            }
            if (!Arrays.equals(fixed.getIntArray("UUID").orElseThrow(), saved.getIntArray("UUID").orElseThrow())) {
                throw new AssertionError("Player UUID changed: " + label);
            }
            if (fixed.contains("respawn")) {
                CompoundTag respawn = fixed.getCompound("respawn").orElseThrow();
                var decoded = ServerPlayer.RespawnConfig.CODEC.parse(NbtOps.INSTANCE, respawn).getOrThrow();
                var encoded = ServerPlayer.RespawnConfig.CODEC.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow();
                if (!respawn.equals(encoded)) throw new AssertionError("Native respawn codec changed fields: " + label);
            }
            NbtIo.writeCompressed(fixed, output.resolve(label + "-dfu.dat"));
            observations.put(label, Map.of("source", sourcePath.toString(), "sourceSha256", NativePlatformSupport.sha(Files.readAllBytes(sourcePath)),
                    "saved", savedPath.toString(), "savedSha256", NativePlatformSupport.sha(Files.readAllBytes(savedPath)),
                    "sourceDataVersion", sourceVersion, "fullInventoryEquipmentRespawnExactMatch", true, "uuidPreserved", true));
            System.out.println("NATIVE_PLAYER_FORMAT_MATCH " + label);
        }
        proof.put("observations", observations);
        Files.writeString(output.resolve("receipt.json"), new Gson().toJson(proof) + "\n");
        System.out.println("NATIVE_PLAYER_FORMAT_DONE no_power=true no_server=true no_ticks=true");
    }
}
