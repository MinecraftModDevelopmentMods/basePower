package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Loaded non-player NBT at the normal stop boundary; never freezes or ticks anything. */
final class Native117StoppedEntitiesSnapshot {
    static final String FILE = "native117-stopped-entities.json";
    static final String WORLD = "AdvantageWorksMigration";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LogManager.getLogger();
    private static final Map<MinecraftServer, String> BOOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private Native117StoppedEntitiesSnapshot() {}

    static void register() {
        Native117StoppedEntitiesSnapshotV2.register();
    }

    static boolean marked(Path runtime, String name) {
        return WORLD.equals(name) && (Files.isRegularFile(runtime.resolve("advantage-migration.allow"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtime.resolve("advantage-machine-operation.allow"), LinkOption.NOFOLLOW_LINKS));
    }

    private static JsonObject header(MinecraftServer server, String boot, String phase) {
        JsonObject result = new JsonObject();
        result.addProperty("format", 1);
        result.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        result.addProperty("dataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        result.addProperty("worldName", server.getWorldData().getLevelName());
        result.addProperty("worldDirectory", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        result.addProperty("bootId", boot);
        result.addProperty("phase", phase);
        return result;
    }

    private static void starting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        Path runtime = Paths.get("").toAbsolutePath().normalize();
        if (!marked(runtime, server.getWorldData().getLevelName())) return;
        String boot = UUID.randomUUID().toString();
        BOOTS.put(server, boot);
        JsonObject pending = header(server, boot, "ServerStartingEvent");
        pending.addProperty("status", "PENDING_STOP");
        try {
            write(runtime, pending);
        } catch (IOException error) {
            LOG.error("Could not invalidate the previous Advantage Works stop snapshot", error);
        }
    }

    private static void stopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        Path runtime = Paths.get("").toAbsolutePath().normalize();
        if (!marked(runtime, server.getWorldData().getLevelName())) return;
        String boot = BOOTS.remove(server);
        JsonObject result = header(server, boot, "ServerStoppingEvent");
        try {
            if (boot == null) throw new IllegalStateException("No marked startup was observed for this server");
            JsonObject dimensions = new JsonObject();
            Set<UUID> seen = new HashSet<>();
            int count = 0;
            for (int id : new int[] {0, -1, 1}) {
                ResourceKey<Level> key = Native117Worlds.key(id);
                ServerLevel world = server.getLevel(key);
                if (world == null) throw new IllegalStateException("Missing vanilla dimension: " + key.location());
                JsonArray entities = entities(world, world.getAllEntities(), seen);
                JsonObject dimension = new JsonObject();
                dimension.addProperty("dimension", id);
                dimension.addProperty("worldDimensionKey", key.location().toString());
                dimension.addProperty("gameTime", world.getGameTime());
                dimension.addProperty("entityCount", entities.size());
                dimension.add("entities", entities);
                dimensions.add(key.location().toString(), dimension);
                count += entities.size();
            }
            result.add("dimensions", dimensions);
            result.addProperty("entityCount", count);
            result.addProperty("enumeration", "ServerLevel.getAllEntities");
            result.addProperty("status", "CAPTURED");
        } catch (Exception error) {
            result.addProperty("status", "FAILED");
            result.addProperty("error", error.toString());
            LOG.error("Advantage Works stopping entity capture failed", error);
        }
        try {
            write(runtime, result);
        } catch (IOException error) {
            // A failed observer must not suppress Minecraft's final save. Strict log gates reject this run.
            LOG.error("Could not retain the Advantage Works stopping entity snapshot", error);
        }
    }

    static JsonArray entities(ServerLevel world, Iterable<Entity> available, Set<UUID> seen) throws IOException {
        Native117Worlds.legacyId(world.dimension());
        JsonArray result = new JsonArray();
        for (Entity entity : available) {
            if (entity instanceof Player) continue;
            if (entity.level() != world) throw new IllegalStateException("Enumerated entity belongs to another world");
            if (!seen.add(entity.getUUID())) throw new IllegalStateException("Duplicate enumerated entity UUID: " + entity.getUUID());
            CompoundTag nbt = serialized(entity);
            if (!nbt.hasUUID("UUID") || !nbt.getUUID("UUID").equals(entity.getUUID())) {
                throw new IllegalStateException("Native serialization changed entity UUID");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(nbt, bytes);
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", entity.getUUID().toString());
            entry.addProperty("id", nbt.getString("id"));
            entry.addProperty("worldDimensionKey", world.dimension().location().toString());
            entry.addProperty("vehicleUuid", entity.getVehicle() == null ? null : entity.getVehicle().getUUID().toString());
            entry.addProperty("nbtBase64", Base64.getEncoder().encodeToString(bytes.toByteArray()));
            result.add(entry);
        }
        return result;
    }

    static CompoundTag serialized(Entity entity) {
        if (entity instanceof Player) throw new IllegalArgumentException("Players are not entity-region witnesses");
        if (entity.isPassenger()) {
            // The native root-only writer rejects riding entities. This is its unprojected
            // saveWithoutId payload plus the same native registry ID, also used by DimensionEntityProbe.
            CompoundTag nbt = entity.saveWithoutId(new CompoundTag());
            nbt.putString("id", EntityType.getKey(entity.getType()).toString());
            return nbt;
        }
        CompoundTag nbt = new CompoundTag();
        if (!entity.saveAsPassenger(nbt)) throw new IllegalStateException("Native entity writer refused " + entity.getUUID());
        return nbt;
    }

    static void write(Path runtime, JsonObject document) throws IOException {
        Path temporary = Files.createTempFile(runtime, "native117-stopped-entities-", ".tmp");
        try {
            Files.write(temporary, (JSON.toJson(document) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, runtime.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
