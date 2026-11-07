package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.fluid.FluidNetworkApi;
import cyano.poweradvantage.api.fluid.FluidStack;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.commands.Commands;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.event.RegisterCommandsEvent;
import zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry;

/** Observes real tile movement and chunk lifecycles only in marked disposable worlds. */
final class LifecycleProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Fixture> fixtures = new LinkedHashMap<>();
    private final Map<Long, Integer> unloads = new LinkedHashMap<>();
    private final Map<Long, Integer> loads = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> before = new LinkedHashMap<>();
    private final List<Map<String, Object>> movements = new ArrayList<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private final Map<String, Integer> routeSteps = new LinkedHashMap<>();
    private final List<Map<String, Object>> chunkEvents = new ArrayList<>();
    private boolean watching;
    private boolean tracingLoads;
    private RoundTripRequest roundTrip;

    private static final class RoundTripRequest {
        final ServerLevel world;
        final String operation, label;
        final LifecycleRoundTrip window = new LifecycleRoundTrip(System.nanoTime());
        final Map<String, LifecycleNaturalTickObserver> bindings = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> prerequisites = new LinkedHashMap<>();
        final List<Map<String, Object>> attempts = new ArrayList<>();
        String completionPhase;

        RoundTripRequest(ServerLevel world, String operation, String label) {
            this.world = world; this.operation = operation; this.label = label;
        }
    }

    static final class Fixture {
        String id;
        int[] min, max, from, to, hold;
        String block, energyType;
        String bucket;
        boolean nativeTicketNetwork;
        int[] source;
        float initialEnergy;
        List<int[]> witnesses;
        List<int[]> route, controls;
        Map<String, List<int[]>> groups;
    }

    static void validate(Fixture fixture) {
        validate(fixture, () -> ModList.get().isLoaded("mineralogy"));
    }

    static void validate(Fixture fixture, BooleanSupplier mineralogyLoaded) {
        if (fixture.id == null || !fixture.id.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid fixture ID");
        if (fixture.min == null || fixture.max == null || fixture.min.length != 3 || fixture.max.length != 3)
            throw new IllegalArgumentException("Invalid bounds");
        if (fixture.min[0] < 1024 || fixture.min[2] < 1024 || fixture.min[1] < 8 || fixture.max[1] > 240)
            throw new IllegalArgumentException("Fixtures must be remote and bounded above terrain");
        boolean nativeNetwork = fixture.nativeTicketNetwork;
        if (nativeNetwork && (!Arrays.asList("C-STEAM", "C-ELECTRIC", "C-FLUID").contains(fixture.id)
                || fixture.from != null || fixture.bucket != null || fixture.groups == null
                || !(fixture.groups.containsKey("supply") && fixture.groups.containsKey("machine")
                    || fixture.groups.containsKey("bridge") && fixture.groups.containsKey("endpoints"))))
            throw new IllegalArgumentException("Native ticket bounds require a declared network fixture");
        int width = fixture.max[0] - fixture.min[0], depth = fixture.max[2] - fixture.min[2];
        if (nativeNetwork && !((width == 159 || width == 543) && depth == 31
                || width == 31 && (depth == 159 || depth == 543)))
            throw new IllegalArgumentException("Native ticket networks require a 160 or 544 by 32 block footprint");
        for (int axis = 0; axis < 3; axis++)
            if (fixture.max[axis] < fixture.min[axis]
                    || fixture.max[axis] - fixture.min[axis] > (nativeNetwork && axis != 1 ? 543 : 31))
                throw new IllegalArgumentException("Oversized fixture");
        if (fixture.witnesses == null || fixture.witnesses.isEmpty())
            throw new IllegalArgumentException("Missing witnesses");
        for (int[] pos : fixture.witnesses) requireInside(fixture, pos);
        if (fixture.bucket != null) {
            boolean mineralogy = "mineralogy:crude_oil_bucket".equals(fixture.bucket);
            if (!fixture.bucket.matches("basepower:(crude|refined)_oil_bucket") && !mineralogy)
                throw new IllegalArgumentException("Unsupported oil bucket");
            if (mineralogy && !mineralogyLoaded.getAsBoolean())
                throw new IllegalArgumentException("Mineralogy oil bucket requires mineralogy");
            requireInside(fixture, fixture.source);
            if (fixture.source[1] >= fixture.max[1])
                throw new IllegalArgumentException("Bucket needs headroom inside fixture bounds");
        }
        if (fixture.groups != null) {
            for (Map.Entry<String, List<int[]>> group : fixture.groups.entrySet()) {
                if (!group.getKey().matches("[a-z]+") || group.getValue().isEmpty())
                    throw new IllegalArgumentException("Invalid chunk group");
                for (int[] chunk : group.getValue()) {
                    if (chunk.length != 2 || chunk[0] < (fixture.min[0] >> 4)
                            || chunk[0] > (fixture.max[0] >> 4) || chunk[1] < (fixture.min[2] >> 4)
                            || chunk[1] > (fixture.max[2] >> 4))
                        throw new IllegalArgumentException("Chunk outside fixture bounds");
                }
            }
        }
        if (fixture.from != null) {
            requireInside(fixture, fixture.from);
            requireInside(fixture, fixture.to);
            requireInside(fixture, fixture.hold);
            int distance = 0;
            for (int axis = 0; axis < 3; axis++) distance += Math.abs(fixture.from[axis] - fixture.to[axis]);
            if ((fixture.route == null && distance != 1) || fixture.initialEnergy <= 0 || !Float.isFinite(fixture.initialEnergy))
                throw new IllegalArgumentException("Invalid movement fixture");
            if (!"basesteam:steam_drill".equals(fixture.block)
                    && !"baseelectric:electric_drill".equals(fixture.block))
                throw new IllegalArgumentException("Not an Advantage drill");
            if (fixture.route != null) {
                if (fixture.route.size() < 2 || fixture.route.size() > 8 || fixture.controls == null
                        || fixture.controls.size() != fixture.route.size()
                        || !Arrays.equals(fixture.from, fixture.route.get(0))
                        || !Arrays.equals(fixture.to, fixture.route.get(fixture.route.size() - 1)))
                    throw new IllegalArgumentException("Invalid route");
                Set<String> visited = new LinkedHashSet<>();
                for (int i = 0; i < fixture.route.size(); i++) {
                    int[] pos = fixture.route.get(i);
                    requireInside(fixture, pos);
                    requireInside(fixture, fixture.controls.get(i));
                    if (!visited.add(Arrays.toString(pos))) throw new IllegalArgumentException("Cyclic route");
                    if (i > 0) {
                        int step = 0;
                        for (int axis = 0; axis < 3; axis++) step += Math.abs(pos[axis] - fixture.route.get(i - 1)[axis]);
                        if (step != 1) throw new IllegalArgumentException("Route teleport");
                    }
                }
            }
        }
    }

    private static void requireInside(Fixture fixture, int[] pos) {
        if (pos == null || pos.length != 3) throw new IllegalArgumentException("Invalid position");
        for (int axis = 0; axis < 3; axis++)
            if (pos[axis] < fixture.min[axis] || pos[axis] > fixture.max[axis])
                throw new IllegalArgumentException("Position outside fixture bounds");
    }

    static void register() {
        if (!new File("advantage-lifecycle.allow").isFile()) return;
        LifecycleProbe probe = new LifecycleProbe();
        try {
            Fixture[] definition = JSON.fromJson(new String(Files.readAllBytes(
                    new File("lifecycle-fixture.json").toPath()), StandardCharsets.UTF_8), Fixture[].class);
            if (definition.length == 0 || definition.length > 32) throw new IllegalArgumentException("Fixture count");
            for (Fixture fixture : definition) {
                validate(fixture);
                if (probe.fixtures.put(fixture.id, fixture) != null) throw new IllegalArgumentException("Duplicate fixture");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Invalid lifecycle fixture", error);
        }
        MinecraftForge.EVENT_BUS.addListener(probe::starting);
        MinecraftForge.EVENT_BUS.addListener(probe::tick);
        MinecraftForge.EVENT_BUS.addListener(probe::unload);
        MinecraftForge.EVENT_BUS.addListener(probe::load);
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advlifecycle")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("operation", StringArgumentType.word())
                        .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                            WorksController.getInstance().requireAuthorizedWorld(context.getSource().getServer());
                            if (!new File("advantage-lifecycle.allow").isFile()) throw new IllegalStateException("Missing lifecycle marker");
                            String operation = StringArgumentType.getString(context, "operation");
                            String label = StringArgumentType.getString(context, "label");
                            if (!label.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid label");
                            if (roundTrip != null) throw new IllegalStateException("Lifecycle round trip is busy");
                            if ("legacy-read".equals(operation) || "changed-target-read".equals(operation)) {
                                queueRoundTrip(context.getSource().getLevel(), operation, label);
                            } else {
                                execute(context.getSource().getLevel(), operation, label);
                                System.out.println("ADVLIFECYCLE COMPLETE " + operation + " " + label);
                            }
                            return 1;
                        }))));
    }

    private void execute(ServerLevel world, String operation, String label) {
        if (operation.startsWith("release-") || operation.startsWith("load-")) {
            boolean acquire = operation.startsWith("load-");
            String group = operation.substring(acquire ? 5 : 8);
            Set<Long> selected = new LinkedHashSet<>();
            for (Fixture fixture : fixtures.values()) {
                if (fixture.groups == null || !fixture.groups.containsKey(group)) continue;
                for (int[] pos : fixture.groups.get(group)) selected.add(ChunkPos.asLong(pos[0], pos[1]));
            }
            if (selected.isEmpty()) throw new IllegalArgumentException("Unknown chunk group " + group);
            for (long key : selected) {
                ChunkPos pos = new ChunkPos(key);
                world.setChunkForced(pos.x, pos.z, acquire);
                if (acquire) world.getChunk(pos.x, pos.z);
                // Removing the forced ticket lets the native ticket manager unload naturally.
                // The runner still requires actual ChunkEvent.Unload/Load witnesses.
            }
            snapshot(world, label);
            return;
        }
        switch (operation) {
            case "place-buckets":
                for (Fixture oil : fixtures.values()) {
                    if (oil.bucket == null) continue;
                    BlockPos source = position(oil.source);
                    if (!world.isEmptyBlock(source)) throw new IllegalStateException("Oil source is not empty: " + oil.id);
                    net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                            new net.minecraft.resources.ResourceLocation(oil.bucket));
                    if (!(item instanceof net.minecraft.world.item.BucketItem)
                            || !((net.minecraft.world.item.BucketItem) item).emptyContents(null, world, source, null))
                        throw new IllegalStateException("Bucket placement failed: " + oil.id);
                    String expected = oil.bucket.substring(0, oil.bucket.length() - "_bucket".length());
                    net.minecraft.world.level.material.FluidState state = world.getFluidState(source);
                    if (!state.isSource() || !expected.equals(
                            net.minecraft.core.Registry.FLUID.getKey(state.getType()).toString()))
                        throw new IllegalStateException("Bucket placed the wrong fluid: " + oil.id);
                }
                break;
            case "prime":
            case "low-prime":
                for (Fixture fixture : fixtures.values()) {
                    if (fixture.from == null) continue;
                    int[] current = fixture.route == null ? fixture.from : fixture.route.get(routeSteps.getOrDefault(fixture.id, 0));
                    BlockEntity tile = world.getBlockEntity(position(current));
                    if (!(tile instanceof PoweredEntity) || !fixture.block.equals(world.getBlockState(tile.getBlockPos()).getBlock().getRegistryName().toString()))
                        throw new IllegalStateException("Missing drill " + fixture.id);
                    float reserve = "low-prime".equals(operation) ? ("steam".equals(fixture.energyType) ? 9 : 249) : fixture.initialEnergy;
                    ((PoweredEntity) tile).setEnergy(reserve, new ConduitType(fixture.energyType));
                    tile.setChanged();
                }
                break;
            case "watch": watching = true; break;
            case "trace-loads": tracingLoads = true; break;
            case "release":
                for (long key : chunks()) {
                    ChunkPos pos = new ChunkPos(key);
                    world.setChunkForced(pos.x, pos.z, false);
                }
                break;
            case "load":
                for (long key : chunks()) {
                    ChunkPos pos = new ChunkPos(key);
                    world.setChunkForced(pos.x, pos.z, true);
                    world.getChunk(pos.x, pos.z);
                }
                break;
            case "offer":
            case "offer-blocked":
                Fixture fixture = fixtures.get("C-FLUID");
                if (fixture == null) throw new IllegalStateException("Missing fluid fixture");
                int accepted = FluidNetworkApi.offerFluid(world, position(fixture.witnesses.get(0)),
                        new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000), 1000);
                int expected = "offer-blocked".equals(operation) ? 0 : 1000;
                if (accepted != expected) throw new IllegalStateException("Fluid network accepted " + accepted + " instead of " + expected);
                break;
            case "isolated":
                for (Fixture network : fixtures.values()) {
                    if (network.groups == null) continue;
                    ConduitType type = new ConduitType("C-STEAM".equals(network.id) ? "steam"
                            : "C-ELECTRIC".equals(network.id) ? "electricity" : "fluid");
                    ConduitType energy = "C-FLUID".equals(network.id) ? new ConduitType("minecraft:water") : type;
                    if (!ConduitRegistry.getInstance().getRequestsForPower(world,
                            position(network.witnesses.get(0)), type, energy).isEmpty())
                        throw new IllegalStateException("Unloaded bridge still connects a consumer: " + network.id);
                }
                break;
            case "snapshot": break;
            case "legacy-read":
            case "changed-target-read":
                throw new IllegalStateException("Saved reads require a queued native START/END window");
            default: throw new IllegalArgumentException("Unknown lifecycle operation " + operation);
        }
        snapshot(world, label);
    }

    private static void requireNewResult(String label) {
        File directory = new File(System.getProperty("advantageWorks.resultsDir", "advantage-works-results"));
        if (new File(directory, "lifecycle-" + label + ".json").exists()
                || new File(directory, "lifecycle-" + label + ".json.tmp").exists())
            throw new IllegalStateException("Refusing to overwrite lifecycle evidence: " + label);
    }

    private void queueRoundTrip(ServerLevel world, String operation, String label) {
        for (String suffix : Arrays.asList("", "-initial", "-end")) requireNewResult(label + suffix);
        roundTrip = new RoundTripRequest(world, operation, label);
        System.out.println("ADVLIFECYCLE QUEUED " + operation + " " + label);
    }

    private static BlockEntity existingTile(ServerLevel world, BlockPos pos) {
        LevelChunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    private boolean roundTripPrerequisites(RoundTripRequest request) {
        ServerLevel world = request.world;
        boolean allChunks = true;
        for (long key : chunks()) {
            ChunkPos chunk = new ChunkPos(key);
            BlockPos pos = new BlockPos(chunk.getMinBlockX(), 80, chunk.getMinBlockZ());
            allChunks &= world.getChunkSource().getChunkNow(chunk.x, chunk.z) != null
                    && world.isPositionTickingWithEntitiesLoaded(pos) && world.isPositionEntityTicking(chunk);
        }
        request.bindings.clear(); request.prerequisites.clear();
        boolean ready = true;
        for (Fixture fixture : fixtures.values()) {
            if (fixture.to == null) continue;
            BlockPos pos = position(fixture.to);
            LevelChunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            BlockEntity tile = existingTile(world, pos);
            CompoundTag saved = tile == null ? new CompoundTag() : tile.save(new CompoundTag());
            boolean hasTarget = saved.contains("targetX", 99) && saved.contains("targetY", 99) && saved.contains("targetZ", 99);
            BlockPos target = hasTarget ? new BlockPos(saved.getInt("targetX"), saved.getInt("targetY"), saved.getInt("targetZ")) : pos;
            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("allFixtureChunksReady", allChunks);
            flags.put("chunkLoaded", chunk != null);
            flags.put("blockAndEntitiesLoaded", world.isPositionTickingWithEntitiesLoaded(pos));
            flags.put("entityTicking", world.isPositionEntityTicking(new ChunkPos(pos)));
            flags.put("targetChunkLoaded", world.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) != null);
            flags.put("targetTicking", world.isPositionTickingWithEntitiesLoaded(target));
            flags.put("worldAttached", tile != null && tile.getLevel() == world);
            flags.put("notRemoved", tile != null && !tile.isRemoved());
            flags.put("blockTypeValid", tile instanceof PoweredEntity && chunk != null
                    && tile.getType().isValid(chunk.getBlockState(pos))
                    && fixture.block.equals(chunk.getBlockState(pos).getBlock().getRegistryName().toString()));
            String bindingFailure = null;
            if (tile != null) {
                try { request.bindings.put(fixture.id, new LifecycleNaturalTickObserver(world, tile)); }
                catch (IllegalStateException error) { bindingFailure = error.getMessage(); }
            }
            flags.put("nativeBindingCurrent", request.bindings.containsKey(fixture.id)
                    && request.bindings.get(fixture.id).current(world));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("flags", flags); row.put("tileIdentity", tile == null ? null : System.identityHashCode(tile));
            row.put("savedNbt", saved.toString()); row.put("target", Arrays.asList(target.getX(), target.getY(), target.getZ()));
            row.put("bindingFailure", bindingFailure);
            request.prerequisites.put(fixture.id, row);
            ready &= flags.values().stream().allMatch(Boolean.TRUE::equals);
        }
        if (request.prerequisites.isEmpty()) throw new IllegalStateException("No saved-read fixtures");
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("worldTime", world.getGameTime()); attempt.put("phase", "START");
        attempt.put("prerequisites", new LinkedHashMap<>(request.prerequisites));
        request.attempts.add(attempt);
        return ready;
    }

    private void applyRoundTrip(RoundTripRequest request) {
        Map<String, CompoundTag> saved = new LinkedHashMap<>();
        for (Fixture fixture : fixtures.values()) {
            if (fixture.to == null) continue;
            CompoundTag tag = request.bindings.get(fixture.id).tile.save(new CompoundTag());
            if (!tag.contains("targetX", 99) || !tag.contains("targetY", 99) || !tag.contains("targetZ", 99)
                    || tag.getShort("progress") <= 0) throw new IllegalStateException("Drill must be mid-block " + fixture.id);
            requireInside(fixture, new int[]{tag.getInt("targetX"), tag.getInt("targetY"), tag.getInt("targetZ")});
            if ("changed-target-read".equals(request.operation) && !tag.contains("TargetState", 10) && !tag.contains("TargetStates", 9))
                throw new IllegalStateException("Changed-target prerequisite lacks restored saved state " + fixture.id);
            saved.put(fixture.id, tag);
        }
        for (Fixture fixture : fixtures.values()) {
            if (fixture.to == null) continue;
            request.bindings.get(fixture.id).install(request.world, request.window.startWorldTime + 1,
                    () -> roundTrip == request && request.window.started && !request.window.finished,
                    () -> witness(request.world, fixture.to), () -> callbackPrerequisites(request, fixture));
        }
        request.window.mutated();
        for (Map.Entry<String, CompoundTag> entry : saved.entrySet()) {
            CompoundTag tag = entry.getValue();
            if ("legacy-read".equals(request.operation)) {
                tag.remove("TargetState"); tag.remove("TargetStates");
            } else {
                request.world.setBlock(new BlockPos(tag.getInt("targetX"), tag.getInt("targetY"), tag.getInt("targetZ")),
                        Blocks.BEDROCK.defaultBlockState(), 3);
            }
            BlockEntity tile = request.bindings.get(entry.getKey()).tile;
            tile.load(tag); tile.setChanged();
        }
        requireNewResult(request.label + "-initial");
        Map<String, Object> initial = observe(request.world);
        initial.put("observedPhase", "START");
        new WorksResultWriter().write("lifecycle", request.label + "-initial", initial);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> callbackPrerequisites(RoundTripRequest request, Fixture fixture) {
        ServerLevel world = request.world;
        BlockPos pos = position(fixture.to);
        LifecycleNaturalTickObserver binding = request.bindings.get(fixture.id);
        List<Integer> saved = (List<Integer>) request.prerequisites.get(fixture.id).get("target");
        BlockPos target = new BlockPos(saved.get(0), saved.get(1), saved.get(2));
        LevelChunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("currentTile", existingTile(world, pos) == binding.tile);
        flags.put("worldAttached", binding.tile.getLevel() == world);
        flags.put("notRemoved", !binding.tile.isRemoved());
        flags.put("blockTypeValid", chunk != null && binding.tile.getType().isValid(chunk.getBlockState(pos)));
        flags.put("nativeBindingCurrent", binding.current(world));
        flags.put("blockAndEntitiesLoaded", world.isPositionTickingWithEntitiesLoaded(pos));
        flags.put("entityTicking", world.isPositionEntityTicking(new ChunkPos(pos)));
        flags.put("targetChunkLoaded", world.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) != null);
        flags.put("targetHasChunkAt", world.hasChunkAt(target));
        return flags;
    }

    @SuppressWarnings("unchecked")
    private void finishRoundTrip(RoundTripRequest request) {
        Map<String, Object> result = observe(request.world);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("protocol", LifecycleRoundTrip.PROTOCOL); proof.put("operation", request.operation);
        proof.put("label", request.label); proof.put("dimension", request.world.dimension().location().toString());
        proof.put("status", request.window.failure == null ? "PASS" : "FAIL");
        proof.put("failure", request.window.failure); proof.put("startPhase", "START"); proof.put("endPhase", request.completionPhase);
        proof.put("startWorldTime", request.window.startWorldTime); proof.put("endWorldTime", request.world.getGameTime());
        proof.put("mutationCount", request.window.mutations); proof.put("attempts", request.attempts);
        proof.put("prerequisites", request.prerequisites);
        Map<String, Object> callbacks = new LinkedHashMap<>();
        for (Map.Entry<String, LifecycleNaturalTickObserver> entry : request.bindings.entrySet()) {
            callbacks.put(entry.getKey(), entry.getValue().report());
        }
        proof.put("callbacks", callbacks);
        result.put("observedPhase", request.completionPhase);
        requireNewResult(request.label + "-end");
        new WorksResultWriter().write("lifecycle", request.label + "-end", result);
        Map<String, List<Map<String, Object>>> cases = (Map<String, List<Map<String, Object>>>) result.get("cases");
        for (Map.Entry<String, LifecycleNaturalTickObserver> entry : request.bindings.entrySet()) {
            Map<String, Object> first = entry.getValue().capture.after;
            if (first == null) continue;
            List<Map<String, Object>> rows = cases.get(entry.getKey());
            int[] to = fixtures.get(entry.getKey()).to;
            for (int i = 0; i < rows.size(); i++) {
                if (Arrays.equals((int[]) rows.get(i).get("pos"), to)) rows.set(i, first);
            }
        }
        result.put("roundTrip", proof);
        for (LifecycleNaturalTickObserver binding : request.bindings.values()) binding.close();
        requireNewResult(request.label);
        new WorksResultWriter().write("lifecycle", request.label, result);
        roundTrip = null;
        System.out.println("ADVLIFECYCLE COMPLETE " + request.operation + " " + request.label);
    }

    private void roundTripTick(TickEvent.WorldTickEvent event) {
        RoundTripRequest request = roundTrip;
        if (request == null || event.world != request.world) return;
        boolean finish = false;
        try {
            if (event.phase == TickEvent.Phase.START && !request.window.started) {
                if (request.window.start(request.world.getGameTime(), System.nanoTime(), roundTripPrerequisites(request)))
                    applyRoundTrip(request);
                finish = request.window.finished;
            } else if (event.phase == TickEvent.Phase.END && request.window.started) {
                boolean complete = request.bindings.values().stream().allMatch(binding -> binding.capture.complete() && binding.current(request.world));
                request.window.end(request.world.getGameTime(), complete);
                finish = true;
            }
        } catch (RuntimeException error) {
            request.window.fail(error.toString());
            finish = true;
        }
        if (finish) {
            request.completionPhase = event.phase.name();
            finishRoundTrip(request);
        }
    }

    private Set<Long> chunks() {
        Set<Long> result = new LinkedHashSet<>();
        for (Fixture fixture : fixtures.values())
            for (int z = fixture.min[2] >> 4; z <= fixture.max[2] >> 4; z++)
                for (int x = fixture.min[0] >> 4; x <= fixture.max[0] >> 4; x++) result.add(ChunkPos.asLong(x, z));
        return result;
    }

    private void unload(ChunkEvent.Unload event) {
        if (event.getWorld() instanceof ServerLevel) {
            increment(unloads, event.getChunk().getPos().toLong());
            recordEvent(event, "unload");
        }
    }

    private void load(ChunkEvent.Load event) {
        if (event.getWorld() instanceof ServerLevel) {
            increment(loads, event.getChunk().getPos().toLong());
            recordEvent(event, "load");
        }
    }

    private void recordEvent(ChunkEvent event, String operation) {
        if (!chunks().contains(event.getChunk().getPos().toLong())) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("operation", operation);
        record.put("pos", Arrays.asList(event.getChunk().getPos().x, event.getChunk().getPos().z));
        record.put("worldTime", ((ServerLevel) event.getWorld()).getGameTime());
        if (tracingLoads && "load".equals(operation)) {
            List<String> stack = new ArrayList<>();
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                stack.add(frame.toString());
                if (stack.size() == 64) break;
            }
            record.put("callStack", stack);
        }
        chunkEvents.add(record);
    }

    private void increment(Map<Long, Integer> counters, long key) {
        if (chunks().contains(key)) counters.put(key, counters.getOrDefault(key, 0) + 1);
    }

    private Map<String, Object> witness(ServerLevel world, int[] coordinates) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pos", coordinates);
        BlockPos pos = position(coordinates);
        // No world.getChunk/getTileEntity call is permitted before this non-loading lookup.
        LevelChunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        result.put("loaded", chunk != null);
        if (chunk == null) return result;
        result.put("block", chunk.getBlockState(pos).getBlock().getRegistryName().toString());
        result.put("state", chunk.getBlockState(pos).toString());
        BlockEntity tile = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (tile != null) {
            result.put("nbt", tile.save(new CompoundTag()).toString());
            result.put("tileIdentity", System.identityHashCode(tile));
            if (tile instanceof PoweredEntity) {
                PoweredEntity powered = (PoweredEntity) tile;
                Map<String, Float> energy = new LinkedHashMap<>();
                for (ConduitType type : powered.getTypes()) energy.put(type.toString(), powered.getEnergy(type));
                result.put("energy", energy);
            }
        }
        return result;
    }

    private void tick(TickEvent.WorldTickEvent event) {
        roundTripTick(event);
        if (!watching || !(event.world instanceof ServerLevel)) return;
        ServerLevel world = (ServerLevel) event.world;
        for (Fixture fixture : fixtures.values()) {
            if (fixture.from == null || completed.contains(fixture.id)) continue;
            int step = routeSteps.getOrDefault(fixture.id, 0);
            int[] from = fixture.route == null ? fixture.from : fixture.route.get(step);
            int[] to = fixture.route == null ? fixture.to : fixture.route.get(step + 1);
            if (event.phase == TickEvent.Phase.START) {
                Map<String, Object> original = witness(world, from);
                if (fixture.block.equals(original.get("block"))) before.put(fixture.id, original);
            } else {
                Map<String, Object> original = witness(world, from);
                Map<String, Object> moved = witness(world, to);
                if (fixture.block.equals(moved.get("block")) && !fixture.block.equals(original.get("block"))) {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("id", fixture.id);
                    record.put("worldTime", world.getGameTime());
                    record.put("before", before.get(fixture.id));
                    record.put("after", moved);
                    record.put("oldPosition", original);
                    if (fixture.route != null) record.put("step", step + 1);
                    movements.add(record);
                    if (fixture.route == null || step + 2 == fixture.route.size()) completed.add(fixture.id);
                    routeSteps.put(fixture.id, step + 1);
                    // Hold after observing the real move, without touching the drill's state or inventory.
                    int[] hold = fixture.controls == null ? fixture.hold : fixture.controls.get(step + 1);
                    world.setBlock(position(hold), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                }
            }
        }
    }

    private void snapshot(ServerLevel world, String label) {
        new WorksResultWriter().write("lifecycle", label, observe(world));
    }

    private Map<String, Object> observe(ServerLevel world) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> cases = new LinkedHashMap<>();
        for (Fixture fixture : fixtures.values()) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (int[] pos : fixture.witnesses) values.add(witness(world, pos));
            cases.put(fixture.id, values);
        }
        List<Map<String, Object>> chunkStates = new ArrayList<>();
        for (long key : chunks()) {
            ChunkPos pos = new ChunkPos(key);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pos", Arrays.asList(pos.x, pos.z));
            entry.put("loaded", world.getChunkSource().getChunkNow(pos.x, pos.z) != null);
            entry.put("unloads", unloads.getOrDefault(key, 0));
            entry.put("loads", loads.getOrDefault(key, 0));
            chunkStates.add(entry);
        }
        result.put("worldTime", world.getGameTime());
        result.put("ticketGeometry", NativeTicketGeometry.observe(world));
        result.put("cases", cases);
        result.put("chunks", chunkStates);
        result.put("movements", movements);
        result.put("chunkEvents", chunkEvents);
        result.put("routeSteps", routeSteps);
        return result;
    }

    private static BlockPos position(int[] coordinates) {
        return new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
    }
}
