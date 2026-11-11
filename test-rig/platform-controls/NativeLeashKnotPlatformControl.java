import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** No-Power, no-server, unticked native saveability and writer-eligibility control. */
public final class NativeLeashKnotPlatformControl {
    private static final UUID ID = UUID.fromString("ac980b6e-a321-4275-8d6e-47b2e2607794");

    public static void main(String[] args) throws Exception {
        NativePlatformSupport.requireIndependentClasspath();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerVanillaFluidTypes();
        var world = NativePlatformSupport.context(false);
        BlockPos attachment = new BlockPos(154, 100, 8);
        LeashFenceKnotEntity knot = new LeashFenceKnotEntity(world, attachment);
        knot.setUUID(ID);
        CompoundTag before = NativePlatformSupport.observe(knot).copy();
        var rootOutput = NativePlatformSupport.output(knot);
        boolean rootAccepted = knot.save(rootOutput);
        var passengerOutput = NativePlatformSupport.output(knot);
        boolean passengerAccepted = knot.saveAsPassenger(passengerOutput);
        CompoundTag observed = NativePlatformSupport.observe(knot);
        boolean selected = knot.shouldBeSaved();
        boolean encodable = knot.getEncodeId() != null;
        if (knot.getClass() != LeashFenceKnotEntity.class || knot.getType() != EntityType.LEASH_KNOT
                || knot.getType().canSerialize() || rootAccepted || passengerAccepted || !selected || encodable
                || !rootOutput.buildResult().isEmpty() || !passengerOutput.buildResult().isEmpty()
                || !ID.equals(before.read("UUID", UUIDUtil.CODEC).orElseThrow()) || !ID.equals(knot.getUUID())
                || !before.equals(observed) || knot.tickCount != 0 || knot.isRemoved() || knot.isPassenger()
                || !knot.getPassengers().isEmpty() || !attachment.equals(knot.getPos())
                || before.contains("id") || before.contains("Passengers") || before.contains("block_pos")
                || before.contains("TileX") || before.contains("TileY") || before.contains("TileZ")) {
            throw new AssertionError("Native noSave contract differs");
        }
        var position = observed.getList("Pos").orElseThrow();
        if (position.size() != 3 || position.getDouble(0).orElseThrow() != 154.5
                || position.getDouble(1).orElseThrow() != 100.375 || position.getDouble(2).orElseThrow() != 8.5) {
            throw new AssertionError("Native observational position does not identify the actual attachment");
        }

        // Corroborate both sides of eligibility and reject any reporter that swallows codec errors.
        ItemEntity persistent = new ItemEntity(world, 8.5, 80, 8.5, new ItemStack(Items.DIRT, 7));
        var persistentOutput = NativePlatformSupport.output(persistent);
        if (!persistent.getType().canSerialize() || !persistent.save(persistentOutput)
                || !"minecraft:item".equals(persistentOutput.buildResult().getString("id").orElseThrow())) {
            throw new AssertionError("Persistent native writer control failed");
        }
        LightningBolt otherNoSave = new LightningBolt(EntityType.LIGHTNING_BOLT, world);
        if (otherNoSave.getType().canSerialize() || otherNoSave.save(NativePlatformSupport.output(otherNoSave))) {
            throw new AssertionError("Unexpected native lightning writer eligibility");
        }
        try {
            NativePlatformSupport.output(knot).store("invalid", Codec.intRange(0, 1), 7);
            throw new AssertionError("Codec failure was discarded");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("Native control codec failure")) throw expected;
        }
        if (!before.equals(NativePlatformSupport.observe(knot))) throw new AssertionError("Control mutated native helper state");

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("format", 1);
        proof.put("policy", "native12111-leash-knot-nosave-v1");
        proof.put("minecraftVersion", SharedConstants.getCurrentVersion().name());
        proof.put("dataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        proof.put("forgeVersion", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        proof.put("nativeClass", knot.getClass().getName());
        proof.put("registryId", EntityType.getKey(knot.getType()).toString());
        proof.put("canSerialize", knot.getType().canSerialize());
        proof.put("storageSelectorAccepts", selected);
        proof.put("encodedIdPresent", encodable);
        proof.put("rootWriterAccepted", rootAccepted);
        proof.put("passengerWriterAccepted", passengerAccepted);
        proof.put("refusedWritersLeaveNoPayload", rootOutput.buildResult().isEmpty() && passengerOutput.buildResult().isEmpty());
        proof.put("observationalUuidPreserved", ID.equals(observed.read("UUID", UUIDUtil.CODEC).orElseThrow()));
        proof.put("observationalStateUnchanged", before.equals(observed));
        proof.put("observationalTagKeys", new TreeSet<>(observed.keySet()));
        proof.put("noPower", true);
        proof.put("powerAccessTransformerAbsent", true);
        proof.put("noHarness", true);
        proof.put("noServer", world.getServer() == null);
        proof.put("noTicks", knot.tickCount == 0);
        proof.put("nativeMethodsUnmodified", true);
        proof.put("nativeClassSha256", NativePlatformSupport.classHashes(Entity.class, EntityType.class, LeashFenceKnotEntity.class, BlockStateData.class));
        NativePlatformSupport.sourceEvidence(proof, Entity.class, EntityType.class, LeashFenceKnotEntity.class, BlockStateData.class);
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        NbtIo.writeCompressed(before, root.resolve("helper-before.dat"));
        NbtIo.writeCompressed(observed, root.resolve("helper-after.dat"));
        NbtIo.writeCompressed(persistentOutput.buildResult(), root.resolve("persistent-control.dat"));
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(proof) + "\n";
        Files.writeString(root.resolve("contract.json"), json);
        System.out.println("LEASH_KNOT_CONTROL " + json.replace('\n', ' '));
        System.out.println("LEASH_KNOT_CONTRACT_SHA256 " + NativePlatformSupport.sha(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        System.out.println("LEASH_KNOT_CONTROL_DONE no_server=true no_ticks=true native_entity_methods_unmodified=true");
    }
}
