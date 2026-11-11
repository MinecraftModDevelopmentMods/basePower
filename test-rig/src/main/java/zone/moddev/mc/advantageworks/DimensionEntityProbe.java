package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.PacketSendListener;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;

/** Native additional-dimension player and entity observations, without client acceptance claims. */
final class DimensionEntityProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final File MARKER = new File("advantage-migration.allow");
    private static final File DEFINITION = new File("dimension-fixture.json");
    private static final String REQUEST_PROTOCOL = "native117-respawn-request-v1";
    private static final Set<String> REQUEST_KEYS = new HashSet<>(Arrays.asList(
            "protocol", "id", "baseFixtureSha256", "keepInventory", "bedMode"));
    private static final String WITNESS = "AdvWorksDimensionEntity";
    private static final BlockPos SPAWN = new BlockPos(160, 100, 24);
    private final Set<Level> live = Collections.newSetFromMap(new IdentityHashMap<Level, Boolean>());
    private static final Set<Level> PINNED = Collections.newSetFromMap(new IdentityHashMap<Level, Boolean>());
    private final Map<Level, NativeEntityHold> held = new IdentityHashMap<>();

    static void register() {
        if (!MARKER.isFile()) return;
        DimensionEntityProbe probe = new DimensionEntityProbe();
        MinecraftForge.EVENT_BUS.addListener(probe::load);
        MinecraftForge.EVENT_BUS.addListener(probe::tick);
        MinecraftForge.EVENT_BUS.addListener(probe::entityJoining);
        MinecraftForge.EVENT_BUS.addListener(probe::starting);
    }

    private static boolean marked(Level world) {
        return MARKER.isFile() && !world.isClientSide
                && "AdvantageWorksMigration".equals(world.getServer().getWorldData().getLevelName());
    }

    private static boolean nativeOnLoad(JsonObject definition) {
        return definition.has("nativeOnLoad") && definition.get("nativeOnLoad").getAsBoolean();
    }

    private boolean frozen(Level world, JsonObject definition) {
        return marked(world) && DEFINITION.isFile() && !nativeOnLoad(definition) && !live.contains(world);
    }

    private static boolean witnessed(Entity entity) { return NativeEntityData.containsString(entity, WITNESS); }

    private void load(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        ServerLevel world = (ServerLevel) event.getLevel();
        if (!marked(world) || !DEFINITION.isFile()) return;
        try {
            JsonObject definition = definition();
            if (Native117Worlds.legacyId(world.dimension()) == dimension(definition)) prepare(world);
        } catch (Exception ex) { throw new IllegalStateException("Dimension load preparation failed", ex); }
    }

    private void tick(TickEvent.LevelTickEvent event) {
        if (!marked(event.level) || !DEFINITION.isFile()) return;
        if (event.phase == TickEvent.Phase.START) {
            try {
                JsonObject definition = definition();
                int selected = dimension(definition);
                if (event.level.dimension().equals(Level.OVERWORLD) && nativeOnLoad(definition)) {
                    ServerLevel selectedWorld = event.level.getServer().getLevel(Native117Worlds.key(selected));
                    if (selectedWorld == null || !marked(selectedWorld)) throw new IllegalStateException("Dimension unavailable");
                    prepare(selectedWorld);
                }
                if (Native117Worlds.legacyId(event.level.dimension()) != selected) return;
                // Pin/load before either frozen observation or normal native initialization.
                prepare((ServerLevel) event.level);
                if (!frozen(event.level, definition)) return;
                NativeEntityHold hold = held.computeIfAbsent(event.level, key -> new NativeEntityHold());
                for (Entity entity : witnesses((ServerLevel) event.level)) hold.hold(entity);
                held.put(event.level, hold);
            } catch (Exception ex) { throw new IllegalStateException("Dimension tick preparation failed", ex); }
        } else if (event.phase == TickEvent.Phase.END) {
            NativeEntityHold hold = held.remove(event.level);
            if (hold != null) hold.restore();
        }
    }

    private void entityJoining(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !marked(event.getLevel())
                || !DEFINITION.isFile() || !witnessed(event.getEntity())) return;
        try {
            if (frozen(event.getLevel(), definition())) {
                held.computeIfAbsent(event.getLevel(), key -> new NativeEntityHold()).hold(event.getEntity());
            }
        } catch (Exception error) { throw new IllegalStateException("Dimension join hold failed", error); }
    }

    private void starting(RegisterCommandsEvent event) {
        for (String operation : new String[] {"read", "snapshot", "operate", "respawn"}) {
            event.getDispatcher().register(Commands.literal("advdimensions").requires(source -> source.hasPermission(4))
                    .then(Commands.literal(operation).then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                        try {
                            execute(context.getSource().getServer(), operation, StringArgumentType.getString(context, "label"));
                            return 1;
                        } catch (Exception ex) { throw new IllegalStateException("Dimension probe failed", ex); }
                    }))));
        }
    }

    private void execute(MinecraftServer server, String operation, String label) throws Exception {
        if (!MARKER.isFile() || !label.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Unmarked runtime or unsafe label");
        JsonObject request = operation.equals("respawn") ? respawnRequest(DEFINITION.toPath(), label) : null;
        JsonObject definition = request == null ? definition() : request.getAsJsonObject("definition");
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null || !marked(overworld)) throw new IllegalStateException("Unexpected overworld");
        ServerLevel world = server.getLevel(Native117Worlds.key(dimension(definition)));
        if (world == null || !marked(world) || Native117Worlds.legacyId(world.dimension()) != dimension(definition)) {
            throw new IllegalStateException("Dimension unavailable");
        }
        prepare(world);
        if (operation.equals("operate")) {
            live.add(world);
            for (Entity entity : witnesses(world)) entity.canUpdate(true);
        }
        JsonObject result = snapshot(server, world, definition, operation);
        if (request != null) result.add("respawnRequest", request.getAsJsonObject("evidence"));
        Files.write(new File("dimensions-" + label + ".json").toPath(), JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
        System.out.println("ADVDIMENSIONS COMPLETE " + label);
    }

    private static JsonObject definition() throws Exception {
        JsonObject result = JSON.fromJson(new String(Files.readAllBytes(DEFINITION.toPath()), StandardCharsets.UTF_8), JsonObject.class);
        return definition(result);
    }

    private static JsonObject definition(JsonObject result) {
        dimension(result);
        String name = result.has("playerName") ? result.get("playerName").getAsString() : "AdvWorksDimensionPlayer";
        UUID uuid = result.has("playerUuid") ? UUID.fromString(result.get("playerUuid").getAsString())
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        result.addProperty("playerName", name); result.addProperty("playerUuid", uuid.toString());
        return result;
    }

    // The tick reader's root is immutable. Each command consumes one closed file.
    static JsonObject respawnRequest(Path fixture, String label) throws Exception {
        java.util.regex.Matcher options = java.util.regex.Pattern.compile(
                "respawn(?:-restart)?-(keep|drop)-(forced|bed|missing)").matcher(label);
        if (!options.matches()) throw new IllegalArgumentException("Unsafe respawn request label");
        Path root = fixture.toAbsolutePath().normalize();
        Path directory = root.resolveSibling("respawn-requests");
        Path path = directory.resolve(label + ".json");
        if (!Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().getParent().equals(root.getParent().toRealPath())) {
            throw new IllegalArgumentException("Respawn request and root must be regular runtime files");
        }
        if (Files.size(path) > 4096) throw new IllegalArgumentException("Respawn request exceeds size limit");
        byte[] baseBytes = Files.readAllBytes(root), requestBytes = Files.readAllBytes(path);
        if (requestBytes.length > 4096) throw new IllegalArgumentException("Respawn request exceeds size limit");
        JsonObject value = new JsonObject();
        Set<String> keys = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(new String(requestBytes, StandardCharsets.UTF_8)))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!REQUEST_KEYS.contains(key) || !keys.add(key)) {
                    throw new IllegalArgumentException("Unknown or duplicate respawn request key");
                }
                if (key.equals("keepInventory")) {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IllegalArgumentException("keepInventory must be a boolean");
                    value.addProperty(key, reader.nextBoolean());
                } else {
                    if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException("Respawn request field must be a string");
                    value.addProperty(key, reader.nextString());
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing respawn request data");
        } catch (java.io.IOException | IllegalStateException error) {
            throw new IllegalArgumentException("Malformed respawn request", error);
        }
        boolean keep = options.group(1).equals("keep");
        String mode = options.group(2), baseHash = sha256(baseBytes);
        if (!keys.equals(REQUEST_KEYS) || !REQUEST_PROTOCOL.equals(value.get("protocol").getAsString())
                || !label.equals(value.get("id").getAsString())
                || !baseHash.equals(value.get("baseFixtureSha256").getAsString())
                || value.get("keepInventory").getAsBoolean() != keep || !mode.equals(value.get("bedMode").getAsString())) {
            throw new IllegalArgumentException("Respawn request protocol, identity, base hash or options differ");
        }
        JsonObject base = definition(JSON.fromJson(new String(baseBytes, StandardCharsets.UTF_8), JsonObject.class));
        JsonObject merged = base.has("respawn") ? base.getAsJsonObject("respawn") : new JsonObject();
        merged.addProperty("keepInventory", keep);
        merged.addProperty("bedMode", mode);
        base.add("respawn", merged);
        value.addProperty("path", "respawn-requests/" + label + ".json");
        value.addProperty("sha256", sha256(requestBytes));
        JsonObject result = new JsonObject(); result.add("definition", base); result.add("evidence", value);
        return result;
    }

    private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(java.util.Locale.ROOT, "%02X", value & 255));
        }
        return result.toString();
    }

    private static int dimension(JsonObject definition) {
        int result = definition.get("dimension").getAsInt();
        if (result != -1 && result != 1) throw new IllegalArgumentException("Expected Nether (-1) or End (1)");
        return result;
    }

    private static void prepare(ServerLevel world) {
        if (!PINNED.contains(world)) {
            for (int x = 6; x <= 13; x++) for (int z = -2; z <= 2; z++) {
                world.setChunkForced(x, z, true);
                world.getChunk(x, z);
            }
            PINNED.add(world);
        }
        WorksOfflinePlayer sentinel = WorksOfflinePlayer.sentinel(world);
        if (sentinel.connection == null) new OfflineConnection(world.getServer(), sentinel);
        sentinel.setPos(144.5, 100, 8.5);
        if (!world.players().contains(sentinel)) world.players().add(sentinel);
        for (int x = 6; x <= 13; x++) for (int z = -2; z <= 2; z++) world.getChunk(x, z);
    }

    private static List<Entity> witnesses(ServerLevel world) {
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        List<Entity> result = new ArrayList<>();
        Native117Worlds.entities(world).forEach(entity -> collect(entity, seen, result));
        result.sort((a, b) -> NativeEntityData.string(a, WITNESS).compareTo(NativeEntityData.string(b, WITNESS)));
        return result;
    }

    private static void collect(Entity entity, Set<Entity> seen, List<Entity> result) {
        if (!seen.add(entity)) return;
        if (witnessed(entity)) result.add(entity);
        for (Entity passenger : entity.getPassengers()) collect(passenger, seen, result);
    }

    private static ServerPlayer readPlayer(MinecraftServer server, ServerLevel world, JsonObject definition) {
        ServerPlayer player = new ServerPlayer(server, world,
                new GameProfile(UUID.fromString(definition.get("playerUuid").getAsString()), definition.get("playerName").getAsString()), net.minecraft.server.level.ClientInformation.createDefault());
        if (server.getPlayerList().load(player).isEmpty()) throw new IllegalStateException("No player file loaded");
        if (Native117Worlds.legacyId(player.level().dimension()) != dimension(definition) || player.level() != world) {
            throw new IllegalStateException("Player loaded in wrong dimension");
        }
        return player;
    }

    private JsonObject snapshot(MinecraftServer server, ServerLevel world, JsonObject definition, String operation) throws Exception {
        JsonObject result = new JsonObject(); result.add("definition", definition);
        result.addProperty("operation", operation); result.addProperty("dimension", Native117Worlds.legacyId(world.dimension()));
        result.addProperty("worldDimensionKey", world.dimension().location().toString());
        result.addProperty("nativeEntityContextVersion", 1);
        result.addProperty("gameTime", world.getGameTime()); result.addProperty("frozen", frozen(world, definition));
        result.addProperty("nativeOnLoad", nativeOnLoad(definition));
        boolean read = operation.equals("read") || operation.equals("respawn");
        result.addProperty("playerAdapter", read); result.add("playerWorldDimension", JsonNull.INSTANCE);
        if (read) {
            ServerPlayer player = readPlayer(server, world, definition);
            result.addProperty("playerWorldDimension", Native117Worlds.legacyId(player.level().dimension()));
            result.add("player", encode(player.saveWithoutId(new CompoundTag())));
            if (operation.equals("respawn")) result.add("respawn", respawn(server, world, definition, player));
            else Native117PlayerIo.save(server.getPlayerList(), player);
        }
        JsonArray entities = new JsonArray();
        for (Entity entity : witnesses(world)) {
            JsonObject context = Native117EntityContext.observe(entity, world);
            // This native writer includes type ID and works for both roots and riding passengers.
            CompoundTag nbt = entityNbt(entity);
            JsonObject entry = encode(nbt); entry.addProperty("witness", nbt.getCompound("ForgeData").getString(WITNESS));
            entry.add("worldDimensionKey", context.get("worldDimensionKey"));
            entry.add("sameWorld", context.get("sameWorld"));
            entry.add("entityWorlds", context.get("entityWorlds"));
            entry.addProperty("uuid", entity.getUUID().toString()); entry.addProperty("ticksExisted", entity.tickCount);
            entry.addProperty("entityId", entity.getId());
            entry.addProperty("instanceId", System.identityHashCode(entity));
            entry.add("vehicleUuid", entity.getVehicle() == null ? JsonNull.INSTANCE : JSON.toJsonTree(entity.getVehicle().getUUID().toString()));
            JsonArray passengers = new JsonArray(); for (Entity passenger : entity.getPassengers()) passengers.add(passenger.getUUID().toString());
            entry.add("passengerUuids", passengers);
            Entity leash = entity instanceof Mob ? ((Mob) entity).getLeashHolder() : null;
            entry.add("leashHolderUuid", leash == null ? JsonNull.INSTANCE : JSON.toJsonTree(leash.getUUID().toString()));
            entry.add("leashHolderType", leash == null ? JsonNull.INSTANCE : JSON.toJsonTree(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(leash.getType()).toString()));
            BlockPos leashPos = leash instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity attached
                    ? attached.getPos() : null;
            entry.add("leashHolderPosition", leashPos == null ? JsonNull.INSTANCE
                    : JSON.toJsonTree(new int[] {leashPos.getX(), leashPos.getY(), leashPos.getZ()}));
            entities.add(entry);
        }
        result.add("entities", entities);
        BlockPos pos = new BlockPos(176, 101, 8); JsonObject generator = new JsonObject();
        generator.addProperty("actualName", net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(world.getBlockState(pos).getBlock()).toString());
        BlockEntity tile = world.getBlockEntity(pos); if (tile != null) generator.add("tile", encode(WorksBlockData.save(tile)));
        result.add("generator", generator);
        return result;
    }

    private static void platform(ServerLevel world) {
        for (int x = 156; x <= 166; x++) for (int z = 20; z <= 29; z++) {
            world.setBlock(new BlockPos(x, 99, z), Blocks.BEDROCK.defaultBlockState(), 2);
            world.setBlock(new BlockPos(x, 106, z), Blocks.BEDROCK.defaultBlockState(), 2);
            for (int y = 100; y < 106; y++) world.setBlock(new BlockPos(x, y, z),
                    (x == 156 || x == 166 || z == 20 || z == 29 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    private static JsonObject respawn(MinecraftServer server, ServerLevel world, JsonObject definition, ServerPlayer player) throws Exception {
        JsonObject options = definition.has("respawn") ? definition.getAsJsonObject("respawn") : new JsonObject();
        boolean keep = !options.has("keepInventory") || options.get("keepInventory").getAsBoolean();
        String bedMode = options.has("bedMode") ? options.get("bedMode").getAsString() : "forced";
        if (!bedMode.matches("forced|bed|missing")) throw new IllegalArgumentException("Invalid bed mode");
        ServerLevel destination = server.getLevel(Level.OVERWORLD);
        platform(destination);
        if (bedMode.equals("bed")) {
            destination.setBlock(SPAWN, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                    .setValue(BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2);
            destination.setBlock(SPAWN.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                    .setValue(BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2);
        }
        player.setRespawnPosition(Level.OVERWORLD, SPAWN, 0.0F, bedMode.equals("forced"), false);
        JsonObject result = new JsonObject(); result.addProperty("scope", "server-only-dummy-network");
        result.addProperty("handler", "ServerPlayNetHandler.PERFORM_RESPAWN");
        result.addProperty("keepInventory", keep); result.addProperty("bedMode", bedMode);
        CompoundTag before = player.saveWithoutId(new CompoundTag());
        result.addProperty("fromDimension", Native117Worlds.legacyId(player.level().dimension())); result.add("before", encode(before));
        JsonObject diagnostics = new JsonObject(); result.add("spawnDiagnostics", diagnostics);
        diagnostics.add("before", spawnDiagnostics(player, before));
        Set<Entity> existing = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>()); Native117Worlds.entities(world).forEach(existing::add);
        boolean oldSource = world.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY),
                oldTarget = destination.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        ServerPlayer replacement = null;
        try {
            world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(keep, server);
            destination.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(keep, server);
            OfflineConnection connection = new OfflineConnection(server, player);
            player.setHealth(0); player.die(player.damageSources().generic());
            CompoundTag afterDeath = player.saveWithoutId(new CompoundTag());
            result.add("afterDeath", encode(afterDeath));
            diagnostics.add("afterDeath", spawnDiagnostics(player, afterDeath));
            if (player.wonGame) throw new IllegalStateException("Expected ordinary death respawn, not queued End exit");
            connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            replacement = server.getPlayerList().getPlayer(player.getUUID());
            if (replacement == null || replacement == player) throw new IllegalStateException("Native respawn handler did not replace player");
            new OfflineConnection(server, replacement);
            CompoundTag after = replacement.saveWithoutId(new CompoundTag());
            result.add("after", encode(after));
            diagnostics.add("after", spawnDiagnostics(replacement, after));
            result.addProperty("toDimension", Native117Worlds.legacyId(replacement.level().dimension()));
            BlockPos bed = replacement.getRespawnPosition();
            result.add("bedLocation", bed == null ? JsonNull.INSTANCE : JSON.toJsonTree(new int[] {bed.getX(), bed.getY(), bed.getZ()}));
            JsonArray drops = new JsonArray();
            for (Entity entity : Native117Worlds.entities(world).collect(java.util.stream.Collectors.toList())) if (entity instanceof ItemEntity && !existing.contains(entity)) {
                CompoundTag nbt = new CompoundTag(); if (!entity.saveAsPassenger(nbt)) throw new IllegalStateException("Could not serialize death drop");
                drops.add(encode(nbt));
            }
            result.add("drops", drops);
            return result;
        } finally {
            try {
                if (replacement != null) server.getPlayerList().remove(replacement);
            } finally {
                world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(oldSource, server);
                destination.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(oldTarget, server);
            }
        }
    }

    private static JsonObject spawnDiagnostics(ServerPlayer player, CompoundTag nbt) {
        JsonObject result = new JsonObject(); result.addProperty("queryDimension", 0);
        result.addProperty("playerWorldDimension", Native117Worlds.legacyId(player.level().dimension()));
        result.addProperty("spawnDimension", Native117Worlds.legacyId(player.getRespawnDimension()));
        BlockPos bed = player.getRespawnPosition();
        result.add("bedLocation", bed == null ? JsonNull.INSTANCE : JSON.toJsonTree(new int[] {bed.getX(), bed.getY(), bed.getZ()}));
        result.addProperty("spawnForced", player.isRespawnForced());
        JsonObject spawn = new JsonObject();
        for (String key : new String[] {"SpawnX", "SpawnY", "SpawnZ"}) {
            spawn.add(key, nbt.contains(key, 99) ? JSON.toJsonTree(nbt.getInt(key)) : JsonNull.INSTANCE);
        }
        spawn.add("SpawnForced", nbt.contains("SpawnForced", 99) ? JSON.toJsonTree(nbt.getBoolean("SpawnForced")) : JsonNull.INSTANCE);
        spawn.add("SpawnDimension", nbt.contains("SpawnDimension", 8) ? JSON.toJsonTree(nbt.getString("SpawnDimension"))
                : nbt.contains("SpawnDimension", 99) ? JSON.toJsonTree(nbt.getInt("SpawnDimension")) : JsonNull.INSTANCE);
        result.add("serializedSpawn", spawn);
        return result;
    }

    private static final class OfflineConnection extends ServerGamePacketListenerImpl {
        OfflineConnection(MinecraftServer server, ServerPlayer player) { super(server, new Connection(PacketFlow.SERVERBOUND), player, WorksOfflinePlayer.cookie(player)); }
        @Override public void send(Packet<?> packet) { /* Deliberately no socket or client login. */ }
        @Override public void send(Packet<?> packet, PacketSendListener listener) {
            // Death-message packets use this overload; no native network work is queued.
        }
    }

    private static CompoundTag entityNbt(Entity entity) {
        if (entity instanceof LeashFenceKnotEntity) {
            CompoundTag nbt = entity.saveWithoutId(new CompoundTag());
            nbt.putString("id", net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());
            return nbt;
        }
        CompoundTag nbt = new CompoundTag();
        if (!entity.saveAsPassenger(nbt)) throw new IllegalStateException("Could not serialize dimension witness");
        return nbt;
    }

    private static JsonObject encode(CompoundTag nbt) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(nbt, bytes);
        JsonObject result = new JsonObject(); result.addProperty("nbtBase64", Base64.getEncoder().encodeToString(bytes.toByteArray())); return result;
    }
}
