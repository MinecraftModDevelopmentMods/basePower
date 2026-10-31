package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fmlserverevents.FMLServerStartingEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;

/** Versioned stop-boundary observation. Native no-save helpers are never saved-state witnesses. */
final class Native117StoppedEntitiesSnapshotV2 {
    static final String CONTRACT = "native117-helper-contract.json";
    static final String POLICY = "native117-leash-knot-nosave-v1";
    private static final Map<MinecraftServer, String> BOOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private Native117StoppedEntitiesSnapshotV2() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, Native117StoppedEntitiesSnapshotV2::starting);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, Native117StoppedEntitiesSnapshotV2::stopping);
    }

    static byte[] contract() throws IOException {
        try (InputStream input = Native117StoppedEntitiesSnapshotV2.class.getResourceAsStream(
                "/assets/advantageworkstest/" + CONTRACT)) {
            if (input == null) throw new IOException("Missing native helper control evidence");
            return input.readAllBytes();
        }
    }

    static String sha(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02X", value & 255));
        return out.toString();
    }

    private static JsonObject header(MinecraftServer server, String boot, String phase) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("format", 2);
        result.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        result.addProperty("dataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        result.addProperty("worldName", server.getWorldData().getLevelName());
        result.addProperty("worldDirectory", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        result.addProperty("bootId", boot);
        result.addProperty("phase", phase);
        result.addProperty("helperPolicy", POLICY);
        result.addProperty("helperContractSha256", sha(contract()));
        return result;
    }

    private static void starting(FMLServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        Path runtime = Path.of("").toAbsolutePath().normalize();
        if (!Native117StoppedEntitiesSnapshot.marked(runtime, server.getWorldData().getLevelName())) return;
        String boot = UUID.randomUUID().toString();
        BOOTS.put(server, boot);
        try {
            Files.write(runtime.resolve(CONTRACT), contract());
            JsonObject pending = header(server, boot, "FMLServerStartingEvent");
            pending.addProperty("status", "PENDING_STOP");
            Native117StoppedEntitiesSnapshot.write(runtime, pending);
        } catch (Exception error) {
            LogManager.getLogger().error("Could not initialize versioned stopping observation", error);
        }
    }

    private static void stopping(FMLServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        Path runtime = Path.of("").toAbsolutePath().normalize();
        if (!Native117StoppedEntitiesSnapshot.marked(runtime, server.getWorldData().getLevelName())) return;
        JsonObject result = new JsonObject();
        try {
            String boot = BOOTS.remove(server);
            result = header(server, boot, "FMLServerStoppingEvent");
            if (boot == null) throw new IllegalStateException("No marked startup for this server");
            JsonObject dimensions = new JsonObject();
            Set<UUID> seen = new HashSet<>();
            int persistent = 0, helpers = 0;
            for (int id : new int[] {0, -1, 1}) {
                ServerLevel world = server.getLevel(Native117Worlds.key(id));
                if (world == null) throw new IllegalStateException("Missing vanilla dimension: " + id);
                JsonObject dimension = partition(world, world.getAllEntities(), seen);
                dimension.addProperty("gameTime", world.getGameTime());
                dimensions.add(world.dimension().location().toString(), dimension);
                persistent += dimension.get("entityCount").getAsInt();
                helpers += dimension.get("nonpersistentHelperCount").getAsInt();
            }
            result.add("dimensions", dimensions);
            result.addProperty("entityCount", persistent);
            result.addProperty("nonpersistentHelperCount", helpers);
            result.addProperty("observedEntityCount", persistent + helpers);
            result.addProperty("enumeration", "ServerLevel.getAllEntities");
            result.addProperty("status", "CAPTURED");
        } catch (Exception error) {
            result.addProperty("status", "FAILED");
            result.addProperty("error", error.toString());
            LogManager.getLogger().error("Versioned stopping entity capture failed", error);
        }
        try {
            Native117StoppedEntitiesSnapshot.write(runtime, result);
        } catch (IOException error) {
            LogManager.getLogger().error("Could not retain versioned stopping entity capture", error);
        }
    }

    static String diagnostic(Entity entity) {
        return "uuid=" + entity.getUUID() + " class=" + entity.getClass().getName()
                + " id=" + EntityType.getKey(entity.getType()) + " canSerialize=" + entity.getType().canSerialize()
                + " removed=" + entity.isRemoved() + " passenger=" + entity.isPassenger();
    }

    static JsonObject partition(ServerLevel world, Iterable<Entity> available, Set<UUID> seen) throws Exception {
        JsonArray persistent = new JsonArray(), helpers = new JsonArray();
        for (Entity entity : available) {
            if (entity instanceof Player) continue;
            if (entity.level != world) throw new IllegalStateException("Entity belongs to another world: " + diagnostic(entity));
            if (entity.getClass() == LeashFenceKnotEntity.class && entity.getType() == EntityType.LEASH_KNOT
                    && !entity.getType().canSerialize() && !entity.isRemoved() && !entity.isPassenger()
                    && entity.getPassengers().isEmpty()) {
                if (!seen.add(entity.getUUID())) throw new IllegalStateException("Duplicate enumerated UUID: " + diagnostic(entity));
                CompoundTag nbt = entity.saveWithoutId(new CompoundTag());
                nbt.putString("id", EntityType.getKey(entity.getType()).toString());
                if (!nbt.hasUUID("UUID") || !entity.getUUID().equals(nbt.getUUID("UUID")))
                    throw new IllegalStateException("Helper writer changed UUID: " + diagnostic(entity));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(nbt, bytes);
                JsonObject helper = new JsonObject();
                helper.addProperty("uuid", entity.getUUID().toString());
                helper.addProperty("id", "minecraft:leash_knot");
                helper.addProperty("nativeClass", entity.getClass().getName());
                helper.addProperty("worldDimensionKey", world.dimension().location().toString());
                helper.addProperty("canSerialize", false);
                helper.addProperty("removed", false);
                helper.addProperty("passenger", false);
                helper.addProperty("passengerCount", 0);
                helper.addProperty("policy", POLICY);
                BlockPos pos = ((LeashFenceKnotEntity) entity).getPos();
                JsonArray attachment = new JsonArray();
                attachment.add(pos.getX()); attachment.add(pos.getY()); attachment.add(pos.getZ());
                helper.add("attachmentPosition", attachment);
                helper.addProperty("nbtBase64", Base64.getEncoder().encodeToString(bytes.toByteArray()));
                helpers.add(helper);
            } else {
                if (!entity.getType().canSerialize() || entity.isRemoved())
                    throw new IllegalStateException("Unconfirmed nonpersistent entity: " + diagnostic(entity));
                try {
                    persistent.add(Native117StoppedEntitiesSnapshot.entities(world, List.of(entity), seen).get(0));
                } catch (Exception error) {
                    throw new IllegalStateException("Persistent writer failed: " + diagnostic(entity), error);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("dimension", Native117Worlds.legacyId(world.dimension()));
        result.addProperty("worldDimensionKey", world.dimension().location().toString());
        result.addProperty("entityCount", persistent.size());
        result.addProperty("nonpersistentHelperCount", helpers.size());
        result.addProperty("observedEntityCount", persistent.size() + helpers.size());
        result.add("entities", persistent);
        result.add("nonpersistentHelpers", helpers);
        return result;
    }
}
