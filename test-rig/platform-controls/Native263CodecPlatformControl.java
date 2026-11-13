import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exercises actual native codecs and pack metadata, without Power or a game world. */
public final class Native263CodecPlatformControl {
    public static void main(String[] args) throws Exception {
        NativePlatformSupport.requireIndependentClasspath();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var version = SharedConstants.getCurrentVersion();
        if (!"26.3".equals(version.name()) || version.dataVersion().version() != 5023
                || version.packVersion(PackType.SERVER_DATA).major() != 121
                || version.packVersion(PackType.SERVER_DATA).minor() != 0
                || version.packVersion(PackType.CLIENT_RESOURCES).major() != 97
                || version.packVersion(PackType.CLIENT_RESOURCES).minor() != 1) {
            throw new AssertionError("Wrong actual SharedConstants pack/data version");
        }
        var changed = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X);
        CompoundTag full = NbtUtils.writeBlockState(changed);
        if (!full.keySet().equals(java.util.Set.of("id", "properties"))
                || !"minecraft:oak_log".equals(full.getString("id").orElseThrow())
                || !"x".equals(full.getCompound("properties").orElseThrow().getString("axis").orElseThrow())
                || NbtUtils.readBlockState(BuiltInRegistries.BLOCK, full) != changed) {
            throw new AssertionError("Native NbtUtils id/properties round trip differs");
        }
        var compact = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, Blocks.STONE.defaultBlockState()).getOrThrow();
        var nonDefault = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, changed).getOrThrow();
        if (!(compact instanceof StringTag) || !"minecraft:stone".equals(compact.asString().orElseThrow())
                || !(nonDefault instanceof CompoundTag)
                || BlockState.CODEC.parse(NbtOps.INSTANCE, compact).getOrThrow() != Blocks.STONE.defaultBlockState()
                || BlockState.CODEC.parse(NbtOps.INSTANCE, nonDefault).getOrThrow() != changed) {
            throw new AssertionError("Native compact/full BlockState.CODEC differs");
        }
        CompoundTag historical = new CompoundTag();
        historical.putString("Name", "minecraft:stone");
        if (BlockState.CODEC.parse(NbtOps.INSTANCE, historical).isSuccess()
                || BlockState.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf("example:missing_block")).isSuccess()) {
            throw new AssertionError("Historical/unknown target block-state negative controls failed");
        }
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("status", "PASS_NATIVE_CODEC_CONTRACT_NOT_GAMEPLAY");
        proof.put("minecraftVersion", version.name());
        proof.put("dataVersion", version.dataVersion().version());
        proof.put("serverPack", "121.0");
        proof.put("clientPack", "97.1");
        proof.put("compact", compact.toString());
        proof.put("full", nonDefault.toString());
        proof.put("negativeControlsRejected", 2);
        proof.put("noPower", true);
        proof.put("noHarness", true);
        proof.put("noServer", true);
        proof.put("noTicks", true);
        proof.put("nativeClassSha256", NativePlatformSupport.classHashes(SharedConstants.class, NbtUtils.class, BlockState.class));
        NativePlatformSupport.sourceEvidence(proof, SharedConstants.class, NbtUtils.class, BlockState.class,
                net.minecraft.world.level.block.state.StateHolder.class);
        proof.put("codecControlSourceSha256", NativePlatformSupport.sha(Files.readAllBytes(
                Path.of("platform-controls/Native263CodecPlatformControl.java"))));
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        net.minecraft.nbt.ListTag mixed = new net.minecraft.nbt.ListTag();
        mixed.add(compact);
        mixed.add(nonDefault);
        CompoundTag paletteFixture = new CompoundTag();
        paletteFixture.putInt("DataVersion", 5023);
        paletteFixture.put("palette", mixed);
        net.minecraft.nbt.NbtIo.writeCompressed(paletteFixture, root.resolve("mixed-palette.dat"));
        var reread = net.minecraft.nbt.NbtIo.readCompressed(root.resolve("mixed-palette.dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        if (!reread.equals(paletteFixture)) throw new AssertionError("Native heterogeneous palette did not round trip");
        Files.writeString(root.resolve("receipt.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof) + "\n");
        System.out.println("NATIVE66_CODEC_CONTROL_PASS actual_data=5023 actual_server_pack=121.0 actual_client_pack=97.1");
    }
}
