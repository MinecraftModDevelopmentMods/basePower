package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import cyano.poweradvantage.api.fluid.FluidNetworkApi;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.CommandRuntimeException;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.material.Fluid;
import cyano.poweradvantage.api.fluid.FluidStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WorksController {
    private static final WorksController INSTANCE = new WorksController();
    private static final int STATUS_NEUTRAL = 8;
    private static final int STATUS_PASS = 5;
    private static final int STATUS_FAIL = 14;
    private static final int STATUS_ATTENTION = 4;

    private WorksDefinition definition;
    private final Map<String, WorksDefinition.Station> stations = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final WorksResultWriter resultWriter = new WorksResultWriter();
    private String activeExclusiveStation;
    private final WorldgenSamplingJob.Slot worldgenJob = new WorldgenSamplingJob.Slot();

    private WorksController() {
        MinecraftForge.EVENT_BUS.addListener(this::worldgenTick);
        MinecraftForge.EVENT_BUS.addListener(this::worldgenStopping);
    }

    static WorksController getInstance() {
        return INSTANCE;
    }

    synchronized void loadDefinition() {
        if (definition != null) return;
        definition = WorksDefinitionLoader.loadNative117();
        for (WorksDefinition.Station station : definition.stations) {
            stations.put(station.id.toUpperCase(Locale.ROOT), station);
            statuses.put(station.id, "NOT_BUILT");
        }
    }

    void requireAuthorizedWorld(MinecraftServer server) throws CommandRuntimeException {
        File marker = new File("advantage-works.allow");
        if (!WorksWorldGuard.authorized(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), marker.isFile())) {
            throw commandError("Refusing to alter an unmarked world. Expected AdvantageWorks level and advantage-works.allow.");
        }
    }

    synchronized String build(MinecraftServer server, String target) throws CommandRuntimeException {
        loadDefinition();
        ServerLevel world = overworld(server);
        if ("all".equalsIgnoreCase(target)) buildConcourse(world);
        List<WorksDefinition.Station> selected = select(target, true);
        int built = 0;
        int skipped = 0;
        for (WorksDefinition.Station station : selected) {
            if (!requirementsPresent(station)) {
                statuses.put(station.id, "SKIP");
                setStatusBlock(world, station, STATUS_ATTENTION);
                skipped++;
                continue;
            }
            resetStation(world, station);
            statuses.put(station.id, "READY");
            built++;
        }
        world.setDefaultSpawnPos(new BlockPos(0, 4, 0), 0.0F);
        return "Advantage Works built " + built + " station(s); skipped " + skipped + " for missing mods.";
    }

    synchronized String reset(MinecraftServer server, String target) throws CommandRuntimeException {
        List<WorksDefinition.Station> selected = select(target, true);
        ServerLevel world = overworld(server);
        int reset = 0;
        for (WorksDefinition.Station station : selected) {
            if (!requirementsPresent(station)) continue;
            setStationChunksForced(world, station, false);
            resetStation(world, station);
            statuses.put(station.id, "READY");
            if (station.id.equals(activeExclusiveStation)) activeExclusiveStation = null;
            reset++;
        }
        return "Reset " + reset + " station(s).";
    }

    synchronized String start(MinecraftServer server, String target) throws CommandRuntimeException {
        WorksDefinition.Station station = oneStation(target);
        if (!requirementsPresent(station)) {
            statuses.put(station.id, "SKIP");
            return station.id + " skipped because required mods are absent.";
        }
        if (station.exclusive && activeExclusiveStation != null
                && !station.id.equals(activeExclusiveStation)) {
            throw commandError("Stop exclusive station " + activeExclusiveStation + " first.");
        }
        ServerLevel world = overworld(server);
        setStationChunksForced(world, station, true);
        applyActions(world, station, station.startActions);
        if (station.exclusive) activeExclusiveStation = station.id;
        statuses.put(station.id, "RUNNING");
        setStatusBlock(world, station, STATUS_NEUTRAL);
        return "Started " + station.id + " - " + station.name;
    }

    synchronized String stop(MinecraftServer server, String target) throws CommandRuntimeException {
        WorksDefinition.Station station = oneStation(target);
        ServerLevel world = overworld(server);
        if (requirementsPresent(station)) {
            applyActions(world, station, station.stopActions);
            removeTaggedEntities(world, station);
            setStationChunksForced(world, station, false);
        }
        if (station.id.equals(activeExclusiveStation)) activeExclusiveStation = null;
        statuses.put(station.id, "STOPPED");
        return "Stopped " + station.id + " - " + station.name;
    }

    private static void setStationChunksForced(ServerLevel world,
                                                WorksDefinition.Station station,
                                                boolean forced) {
        int minChunkX = (station.origin[0] + station.boundsMin[0]) >> 4;
        int maxChunkX = (station.origin[0] + station.boundsMax[0]) >> 4;
        int minChunkZ = (station.origin[2] + station.boundsMin[2]) >> 4;
        int maxChunkZ = (station.origin[2] + station.boundsMax[2]) >> 4;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                world.setChunkForced(chunkX, chunkZ, forced);
            }
        }
    }

    synchronized String check(MinecraftServer server, String target) throws CommandRuntimeException {
        List<WorksDefinition.Station> selected = select(target, true);
        ServerLevel world = overworld(server);
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (WorksDefinition.Station station : selected) {
            String status = checkStation(world, station);
            summary.put(status, summary.containsKey(status) ? summary.get(status) + 1 : 1);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("evidenceScope", "declared_assertions_only; manual procedures are not automated passes");
        payload.put("stationStatuses", new LinkedHashMap<>(statuses));
        resultWriter.write("all", "summary", payload);
        return "Advantage Works check: " + summary;
    }

    synchronized String status(String target) throws CommandRuntimeException {
        if ("all".equalsIgnoreCase(target)) return statuses.toString();
        WorksDefinition.Station station = oneStation(target);
        return station.id + " " + station.name + ": " + statuses.get(station.id);
    }

    synchronized String checkpoint(MinecraftServer server, String target, String name) throws CommandRuntimeException {
        WorksDefinition.Station station = oneStation(target);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkpoint", name);
        payload.put("worldTime", overworld(server).getGameTime());
        payload.put("snapshot", snapshot(overworld(server), station));
        File result = resultWriter.write(station.id, "checkpoint-" + name, payload);
        return "Checkpoint written for " + station.id + " to " + result.getPath();
    }

    synchronized String sampleWorldgen(MinecraftServer server, int radius) throws CommandRuntimeException {
        if (radius < 1 || radius > 16) {
            throw commandError("Worldgen sample radius must be between 1 and 16 chunks");
        }
        if (worldgenJob.current() != null) throw commandError("A worldgen sample is already running");
        ServerLevel world = overworld(server);
        BlockPos spawn = world.getSharedSpawnPos();
        worldgenJob.start(new WorldgenSamplingJob(new Native117WorldgenAccess(world),
                spawn.getX(), spawn.getY(), spawn.getZ(), world.getSeaLevel(), radius));
        return "Queued worldgen sample with radius " + radius + " for bounded server-tick sampling";
    }

    private synchronized void worldgenTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || worldgenJob.current() == null) return;
        WorldgenSamplingJob job = worldgenJob.current();
        job.tick();
        if (!job.finished()) return;
        try {
            if (job.failure() != null) throw job.failure();
            Map<String, Object> payload = job.result();
            payload.put("chunkLoading", job.diagnostics());
            File result = resultWriter.write("worldgen", "sample", payload);
            org.apache.logging.log4j.LogManager.getLogger(AdvantageWorksMod.MODID).info(
                    "Worldgen sample wrote {} chunks to {}: {}", payload.get("chunksScanned"),
                    result.getPath(), payload.get("counts"));
        } catch (RuntimeException failure) {
            org.apache.logging.log4j.LogManager.getLogger(AdvantageWorksMod.MODID)
                    .error("Worldgen sample failed", failure);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("phase", job.phase().name());
            payload.put("failure", failure.toString());
            payload.put("chunkLoading", job.diagnostics());
            try {
                resultWriter.write("worldgen", "sample-failed", payload);
            } catch (RuntimeException writeFailure) {
                org.apache.logging.log4j.LogManager.getLogger(AdvantageWorksMod.MODID)
                        .error("Cannot publish worldgen sample failure", writeFailure);
            }
        } finally {
            worldgenJob.release();
        }
    }

    private synchronized void worldgenStopping(FMLServerStoppingEvent event) {
        worldgenJob.release();
    }

    static boolean matchesExpectedBiome(ServerLevel world, String expected, Biome biome) {
        net.minecraft.resources.ResourceKey<Biome> key = world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY)
                .getResourceKey(biome).orElseThrow(() -> new IllegalStateException("Unregistered native biome"));
        if ("DESERT".equals(expected)) {
            return BiomeDictionary.hasType(key, BiomeDictionary.Type.getType("SANDY"))
                    && !BiomeDictionary.hasType(key, BiomeDictionary.Type.getType("BEACH"))
                    && !BiomeDictionary.hasType(key, BiomeDictionary.Type.getType("MESA"));
        }
        return BiomeDictionary.hasType(key, BiomeDictionary.Type.getType(expected));
    }

    List<String> stationIdsWithAll() {
        loadDefinition();
        List<String> ids = new ArrayList<>();
        ids.add("all");
        ids.add("power");
        ids.add("steam");
        ids.add("electric");
        ids.addAll(stations.keySet());
        return ids;
    }

    private String checkStation(ServerLevel world, WorksDefinition.Station station) {
        if (!requirementsPresent(station)) {
            statuses.put(station.id, "SKIP");
            setStatusBlock(world, station, STATUS_ATTENTION);
            writeStationResult(world, station, "SKIP", Collections.<Map<String, Object>>emptyList());
            return "SKIP";
        }
        List<Map<String, Object>> assertionResults = new ArrayList<>();
        for (WorksDefinition.Assertion assertion : station.assertions) {
            if (!requirementsPresent(assertion.requiredMods) || !optionalTargetPresent(assertion)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", assertion.id);
                result.put("type", assertion.type);
                result.put("status", "SKIP");
                result.put("detail", "optional integration is absent");
                assertionResults.add(result);
                continue;
            }
            boolean passed;
            String detail;
            Exception evaluationError = null;
            try {
                passed = evaluateAssertion(world, station, assertion);
                detail = passed ? "matched" : "did not match";
            } catch (Exception ex) {
                passed = false;
                evaluationError = ex;
                detail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
            String assertionStatus = AssertionChecks.verdict(passed, assertion.expectedFailure, evaluationError);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", assertion.id);
            result.put("type", assertion.type);
            result.put("status", assertionStatus);
            result.put("detail", detail);
            if (assertion.expectedFailure != null) result.put("bug", assertion.expectedFailure);
            assertionResults.add(result);
        }

        String status = AssertionChecks.aggregate(assertionResults, !station.manualChecks.isEmpty());
        statuses.put(station.id, status);
        setStatusBlock(world, station, statusColor(status));
        writeStationResult(world, station, status, assertionResults);
        return status;
    }

    private void writeStationResult(ServerLevel world, WorksDefinition.Station station, String status,
                                    List<Map<String, Object>> assertions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", station.name);
        payload.put("district", station.district);
        payload.put("status", status);
        payload.put("evidenceScope", "declared_assertions_only; manual procedures are not automated passes");
        if ("SKIP".equals(status)) payload.put("skipReason", "required integration or all measurements unavailable");
        payload.put("worldTime", world.getGameTime());
        payload.put("assertions", assertions);
        payload.put("manualChecks", station.manualChecks);
        payload.put("snapshot", snapshot(world, station));
        resultWriter.write(station.id, "latest", payload);
    }

    private List<Map<String, Object>> snapshot(ServerLevel world, WorksDefinition.Station station) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (WorksDefinition.Placement placement : station.placements) positions.add(absolute(station, placement.pos));
        for (WorksDefinition.Assertion assertion : station.assertions) {
            if (assertion.pos != null) positions.add(absolute(station, assertion.pos));
            for (int[] position : assertion.positions) positions.add(absolute(station, position));
        }
        for (BlockPos pos : positions) {
            Map<String, Object> block = new LinkedHashMap<>();
            BlockState state = world.getBlockState(pos);
            ResourceLocation name = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            block.put("pos", Arrays.asList(pos.getX(), pos.getY(), pos.getZ()));
            block.put("block", name == null ? "unknown" : name.toString());
            block.put("state", state.toString());
            BlockEntity tile = world.getBlockEntity(pos);
            if (tile != null) block.put("nbt", tile.save(new CompoundTag()).toString());
            snapshot.add(block);
        }
        return snapshot;
    }

    private boolean evaluateAssertion(ServerLevel world, WorksDefinition.Station station,
                                      WorksDefinition.Assertion assertion) {
        if (assertion.block != null && block(assertion.block) == Blocks.AIR
                && !"minecraft:air".equals(assertion.block)) {
            throw new IllegalArgumentException("Unregistered assertion block " + assertion.block);
        }
        if (BayDrillAssertions.applies(station, assertion)) {
            return BayDrillAssertions.matches(station, assertion,
                    relative -> world.getBlockState(absolute(station, relative)).getBlock() == block(assertion.block),
                    relative -> {
                        BlockState state = world.getBlockState(absolute(station, relative));
                        if (assertion.meta != null) {
                            return state.equals(legacyState(assertion.block, block(assertion.block), assertion.meta));
                        }
                        return state.getValues().entrySet().stream().anyMatch(entry ->
                                assertion.property.equals(entry.getKey().getName())
                                        && assertion.expectedText.equals(String.valueOf(entry.getValue())));
                    });
        }
        if ("registryBlock".equals(assertion.type)) {
            return block(assertion.block) != Blocks.AIR;
        }
        if ("registryItem".equals(assertion.type)) {
            return assertion.item != null && item(assertion.item) != null;
        }
        if ("modLoaded".equals(assertion.type)) {
            return ModList.get().isLoaded(assertion.item);
        }
        if ("fluidAmount".equals(assertion.type)) {
            long total = 0;
            for (int[] position : assertion.positions) {
                BlockEntity source = world.getBlockEntity(absolute(station, position));
                if (source == null) return false;
                total += AssertionChecks.fluidAmount(source.save(new CompoundTag()), assertion.nbtPath, assertion.fluid);
            }
            return compare(total, assertion.operator, assertion.value);
        }
        BlockPos pos = absolute(station, assertion.pos);
        if ("block".equals(assertion.type)) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() != block(assertion.block)) return false;
            return assertion.meta == null || state.equals(legacyState(assertion.block, state.getBlock(), assertion.meta));
        }
        if ("blockProperty".equals(assertion.type)) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() != block(assertion.block)) return false;
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                if (entry.getKey().getName().equals(assertion.property)) {
                    return assertion.expectedText.equals(String.valueOf(entry.getValue()));
                }
            }
            return false;
        }
        if ("comparatorOutput".equals(assertion.type)) {
            BlockState state = world.getBlockState(pos);
            Block source = state.getBlock();
            return source.hasAnalogOutputSignal(state)
                    && compare(source.getAnalogOutputSignal(state, world, pos),
                    assertion.operator, assertion.value);
        }
        BlockEntity tile = world.getBlockEntity(pos);
        if ("tileExists".equals(assertion.type)) return tile != null;
        if ("inventoryEmpty".equals(assertion.type)) {
            if (!(tile instanceof Container)) return false;
            return inventoryCount((Container) tile, assertion.item) == 0;
        }
        if ("inventoryCount".equals(assertion.type)) {
            if (!(tile instanceof Container)) return false;
            return compare(inventoryCount((Container) tile, assertion.item), assertion.operator, assertion.count);
        }
        if ("inventoryNbtNumber".equals(assertion.type)) {
            if (!(tile instanceof Container) || assertion.slot == null) return false;
            Container inventory = (Container) tile;
            if (assertion.slot < 0 || assertion.slot >= inventory.getContainerSize()) return false;
            ItemStack stack = inventory.getItem(assertion.slot);
            if (stack.isEmpty() || !stack.hasTag()) return false;
            Number number = numberAt(stack.getTag(), assertion.nbtPath);
            return number != null && compare(number.doubleValue(), assertion.operator, assertion.value);
        }
        if ("nbtNumber".equals(assertion.type)) {
            if (tile == null) return false;
            CompoundTag nbt = tile.save(new CompoundTag());
            Number number = numberAt(nbt, assertion.nbtPath);
            return number != null && compare(number.doubleValue(), assertion.operator, assertion.value);
        }
        if ("nbtString".equals(assertion.type)) {
            if (tile == null) return false;
            String text = stringAt(tile.save(new CompoundTag()), assertion.nbtPath);
            return assertion.expectedText != null && assertion.expectedText.equals(text);
        }
        if ("energy".equals(assertion.type)) {
            if (tile == null) return false;
            int[] energy = tile.save(new CompoundTag()).getIntArray("Energy");
            int index = assertion.energyIndex == null ? 0 : assertion.energyIndex;
            return index >= 0 && index < energy.length
                    && compare(Float.intBitsToFloat(energy[index]), assertion.operator, assertion.value);
        }
        throw new IllegalArgumentException("Unsupported assertion type " + assertion.type);
    }

    private static int inventoryCount(Container inventory, String itemName) {
        Item expected = itemName == null ? null : item(itemName);
        if (itemName != null && expected == null) throw new IllegalArgumentException("Unregistered assertion item " + itemName);
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && (expected == null || stack.getItem() == expected)) count += stack.getCount();
        }
        return count;
    }

    private static Number numberAt(CompoundTag root, String path) {
        if (path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        CompoundTag current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.contains(parts[i], 10)) return null;
            current = current.getCompound(parts[i]);
        }
        String leaf = parts[parts.length - 1];
        if (!current.contains(leaf, 99)) return null;
        return current.getDouble(leaf);
    }

    private static String stringAt(CompoundTag root, String path) {
        if (path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        CompoundTag current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.contains(parts[i], 10)) return null;
            current = current.getCompound(parts[i]);
        }
        String leaf = parts[parts.length - 1];
        return current.contains(leaf, 8) ? current.getString(leaf) : null;
    }

    private static boolean compare(double actual, String operator, Number expected) {
        return AssertionChecks.compare(actual, operator, expected);
    }

    private void resetStation(ServerLevel world, WorksDefinition.Station station) {
        clearStation(world, station);
        decorateStation(world, station);
        for (WorksDefinition.Placement placement : station.placements) applyPlacement(world, station, placement);
        applyActions(world, station, station.setupActions);
        setStatusBlock(world, station, STATUS_NEUTRAL);
    }

    private void clearStation(ServerLevel world, WorksDefinition.Station station) {
        BlockPos min = absolute(station, station.boundsMin);
        BlockPos max = absolute(station, station.boundsMax);
        removeTaggedEntities(world, station);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private void removeTaggedEntities(ServerLevel world, WorksDefinition.Station station) {
        BlockPos min = absolute(station, station.boundsMin);
        BlockPos max = absolute(station, station.boundsMax);
        for (Entity entity : world.getEntitiesOfClass(Entity.class,
                new AABB(min, max.offset(1, 1, 1)))) {
            if (StationOwnership.matches(entity, station.id)) {
                entity.discard();
            }
        }
    }

    static final class StationOwnership {
        static boolean matches(Entity entity, String stationId) {
            return stationId.equals(NativeEntityData.string(entity, "AdvantageWorksStation"));
        }
    }

    private void decorateStation(ServerLevel world, WorksDefinition.Station station) {
        int color = districtColor(station.district);
        int maxX = station.boundsMax[0];
        int maxZ = station.boundsMax[2];
        for (int x = station.boundsMin[0]; x <= maxX; x++) {
            for (int z = station.boundsMin[2]; z <= maxZ; z++) {
                boolean edge = x == station.boundsMin[0] || x == maxX
                        || z == station.boundsMin[2] || z == maxZ;
                BlockState state = edge ? wool(color).defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
                world.setBlock(absolute(station, new int[]{x, 0, z}), state, 2);
            }
        }
        if (station.enclosed) encloseStation(world, station);
        placeSign(world, absolute(station, new int[]{1, 1, 1}), station.id,
                station.name, "", "");
        placeCommandSign(world, absolute(station, new int[]{3, 1, 1}), "[START]",
                "/advworks start " + station.id);
        placeCommandSign(world, absolute(station, new int[]{5, 1, 1}), "[RESET]",
                "/advworks reset " + station.id);
        placeCommandSign(world, absolute(station, new int[]{7, 1, 1}), "[CHECK]",
                "/advworks check " + station.id);
        placeCommandSign(world, absolute(station, new int[]{9, 1, 1}), "[STOP]",
                "/advworks stop " + station.id);
    }

    private void encloseStation(ServerLevel world, WorksDefinition.Station station) {
        int minX = station.boundsMin[0];
        int maxX = station.boundsMax[0];
        int minZ = station.boundsMin[2];
        int maxZ = station.boundsMax[2];
        int gateX = (minX + maxX) / 2;
        for (int y = 1; y <= 4; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (!(x == gateX && y <= 2)) {
                    world.setBlock(absolute(station, new int[]{x, y, minZ}),
                            Blocks.GLASS.defaultBlockState(), 2);
                }
                world.setBlock(absolute(station, new int[]{x, y, maxZ}),
                        Blocks.GLASS.defaultBlockState(), 2);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                world.setBlock(absolute(station, new int[]{minX, y, z}),
                        Blocks.GLASS.defaultBlockState(), 2);
                world.setBlock(absolute(station, new int[]{maxX, y, z}),
                        Blocks.GLASS.defaultBlockState(), 2);
            }
        }
        world.setBlock(absolute(station, new int[]{gateX, 1, minZ}),
                Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH), 2);
    }

    private void buildConcourse(ServerLevel world) {
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                world.setBlock(new BlockPos(x, 3, z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        for (int i = -140; i <= 160; i++) {
            for (int width = -2; width <= 2; width++) {
                world.setBlock(new BlockPos(i, 3, width), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                world.setBlock(new BlockPos(width, 3, i), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        placeSign(world, new BlockPos(-3, 4, 0), "POWER WORKS", "WEST", "", "");
        placeSign(world, new BlockPos(0, 4, -3), "STEAM WORKS", "NORTH", "", "");
        placeSign(world, new BlockPos(3, 4, 0), "ELECTRIC WORKS", "EAST", "", "");
        placeSign(world, new BlockPos(0, 4, 3), "PROVING", "GROUNDS SOUTH", "", "");
    }

    private void applyActions(ServerLevel world, WorksDefinition.Station station,
                              List<WorksDefinition.Action> actions) {
        for (WorksDefinition.Action action : actions) {
            if (!requirementsPresent(action.requiredMods)) continue;
            String type = action.type == null ? "setBlock" : action.type;
            BlockPos pos = action.pos == null ? null : absolute(station, action.pos);
            switch (type) {
                case "setBlock":
                    applyPlacement(world, station, action);
                    break;
                case "setAir":
                    world.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    break;
                case "clearInventory":
                    BlockEntity clearTile = world.getBlockEntity(pos);
                    if (clearTile instanceof Container) ((Container) clearTile).clearContent();
                    break;
                case "setInventory":
                    BlockEntity tile = world.getBlockEntity(pos);
                    if (!(tile instanceof Container)) throw new IllegalStateException("No inventory at " + pos);
                    Item stackItem = item(action.item);
                    if (stackItem == null) throw new IllegalStateException("Unknown item " + action.item);
                    ItemStack stack = itemStack(stackItem, action.count, action.damage);
                    if (action.nbt != null) {
                        try {
                            stack.setTag(JsonNbtConverter.toCompound(action.nbt));
                        } catch (Exception ex) {
                            throw new IllegalStateException("Cannot parse item NBT for " + action.item, ex);
                        }
                    }
                    if (action.enchantment != null) {
                        Enchantment enchantment = ForgeRegistries.ENCHANTMENTS.getValue(
                                new ResourceLocation(action.enchantment));
                        if (enchantment == null) {
                            throw new IllegalStateException("Unknown enchantment " + action.enchantment);
                        }
                        stack.enchant(enchantment, action.level);
                    }
                    ((Container) tile).setItem(action.slot, stack);
                    tile.setChanged();
                    break;
                case "fillInventory":
                    BlockEntity fillTile = world.getBlockEntity(pos);
                    if (!(fillTile instanceof Container)) throw new IllegalStateException("No inventory at " + pos);
                    Item fillItem = item(action.item);
                    if (fillItem == null) throw new IllegalStateException("Unknown item " + action.item);
                    Container inventory = (Container) fillTile;
                    ItemStack fillTemplate = itemStack(fillItem, 1, action.damage);
                    int fillCount = Math.min(action.count,
                            Math.min(fillTemplate.getMaxStackSize(), inventory.getMaxStackSize()));
                    for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                        inventory.setItem(slot,
                                itemStack(fillItem, fillCount, action.damage));
                    }
                    fillTile.setChanged();
                    break;
                case "mergeNbt":
                    mergeTileNbt(world, pos, action.nbt);
                    break;
                case "setFluid":
                    BlockEntity fluidTile = world.getBlockEntity(pos);
                    Fluid fluid = fluid(action.fluid);
                    if (fluid == null) throw new IllegalStateException("Unknown fluid " + action.fluid);
                    int requested = (int) Math.min(Integer.MAX_VALUE, action.value);
                    int filled;
                    if (fluidTile instanceof IAdvantageFluidHandler) {
                        IAdvantageFluidHandler handler = (IAdvantageFluidHandler) fluidTile;
                        handler.drain(null, Integer.MAX_VALUE, true);
                        filled = requested == 0 ? 0 : handler.fill(null, new FluidStack(fluid, requested), true);
                    } else {
                        throw new IllegalStateException("No fluid handler at " + pos);
                    }
                    if (filled != requested) {
                        throw new IllegalStateException("Fluid handler at " + pos + " accepted " + filled
                                + " of " + requested + " mB " + action.fluid);
                    }
                    fluidTile.setChanged();
                    break;
                case "offerFluid":
                    Fluid offeredFluid = fluid(action.fluid);
                    if (offeredFluid == null) throw new IllegalStateException("Unknown fluid " + action.fluid);
                    FluidStack offeredStack = new FluidStack(offeredFluid, (int) action.value);
                    int accepted = FluidNetworkApi.offerFluid(world, pos, offeredStack, action.transferLimit);
                    if (accepted != action.expectedValue.intValue()) {
                        throw new IllegalStateException("Public fluid API at " + pos + " accepted " + accepted
                                + " mB; expected " + action.expectedValue);
                    }
                    if (offeredStack.amount != action.value) {
                        throw new IllegalStateException("Public fluid API mutated its offered stack at " + pos);
                    }
                    break;
                case "spawnItem":
                    Item dropped = item(action.item);
                    if (dropped == null) throw new IllegalStateException("Unknown item " + action.item);
                    ItemEntity entityItem = new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.1,
                            pos.getZ() + 0.5, itemStack(dropped, action.count, action.damage));
                    tagEntity(entityItem, station.id);
                    world.addFreshEntity(entityItem);
                    break;
                case "spawnEntity":
                    EntityType<?> entityType = ForgeRegistries.ENTITIES.getValue(new ResourceLocation(action.entity));
                    Entity entity = entityType == null ? null : entityType.create(world);
                    if (entity == null) throw new IllegalStateException("Unknown entity " + action.entity);
                    entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    tagEntity(entity, station.id);
                    world.addFreshEntity(entity);
                    break;
                case "time":
                    world.setDayTime(action.value);
                    break;
                case "weatherClear":
                    world.getLevelData().setRaining(false);
                    ((net.minecraft.world.level.storage.ServerLevelData) world.getLevelData()).setThundering(false);
                    break;
                case "weatherRain":
                    world.getLevelData().setRaining(true);
                    ((net.minecraft.world.level.storage.ServerLevelData) world.getLevelData()).setThundering(false);
                    break;
                case "weatherThunder":
                    world.getLevelData().setRaining(true);
                    ((net.minecraft.world.level.storage.ServerLevelData) world.getLevelData()).setThundering(true);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported action type " + type);
            }
        }
    }

    private void applyPlacement(ServerLevel world, WorksDefinition.Station station,
                                WorksDefinition.Placement placement) {
        if (!requirementsPresent(placement.requiredMods)) return;
        Block target = block(placement.block);
        if (target == Blocks.AIR && !"minecraft:air".equals(placement.block)) {
            if (placement.optional) return;
            throw new IllegalStateException("Unknown block " + placement.block + " for " + station.id);
        }
        BlockPos pos = absolute(station, placement.pos);
        world.setBlock(pos, legacyState(placement.block, target, placement.meta), 2);
        if (placement.nbt != null) mergeTileNbt(world, pos, placement.nbt);
    }

    private void mergeTileNbt(ServerLevel world, BlockPos pos, JsonObject supplied) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile == null) throw new IllegalStateException("No tile entity at " + pos);
        try {
            CompoundTag base = tile.save(new CompoundTag());
            CompoundTag additions = JsonNbtConverter.toCompound(supplied);
            base.merge(additions);
            base.putInt("x", pos.getX());
            base.putInt("y", pos.getY());
            base.putInt("z", pos.getZ());
            tile.load(base);
            tile.setChanged();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot merge tile NBT at " + pos, ex);
        }
    }

    private List<WorksDefinition.Station> select(String target, boolean allowGroups) throws CommandRuntimeException {
        loadDefinition();
        if ("all".equalsIgnoreCase(target)) return new ArrayList<>(stations.values());
        if (allowGroups) {
            String district = target.toLowerCase(Locale.ROOT);
            if ("power".equals(district) || "steam".equals(district) || "electric".equals(district)) {
                List<WorksDefinition.Station> result = new ArrayList<>();
                for (WorksDefinition.Station station : stations.values()) {
                    if (district.equals(station.district)) result.add(station);
                }
                return result;
            }
        }
        return Collections.singletonList(oneStation(target));
    }

    private WorksDefinition.Station oneStation(String id) throws CommandRuntimeException {
        loadDefinition();
        WorksDefinition.Station station = stations.get(id.toUpperCase(Locale.ROOT));
        if (station == null) throw commandError("Unknown Advantage Works station " + id);
        return station;
    }

    private static boolean requirementsPresent(WorksDefinition.Station station) {
        return requirementsPresent(station.requiredMods);
    }

    private static boolean requirementsPresent(List<String> requiredMods) {
        for (String mod : requiredMods) if (!ModList.get().isLoaded(mod)) return false;
        return true;
    }

    private static boolean optionalTargetPresent(WorksDefinition.Assertion assertion) {
        if (!assertion.optional) return true;
        if (assertion.block != null) return block(assertion.block) != Blocks.AIR;
        if (assertion.item != null && !"modLoaded".equals(assertion.type)) return item(assertion.item) != null;
        return true;
    }

    private int districtColor(String id) {
        for (WorksDefinition.District district : definition.districts) {
            if (district.id.equals(id)) return district.woolMeta;
        }
        return STATUS_NEUTRAL;
    }

    private static int statusColor(String status) {
        if ("PASS".equals(status)) return STATUS_PASS;
        if ("FAIL".equals(status)) return STATUS_FAIL;
        return STATUS_ATTENTION;
    }

    private static void setStatusBlock(ServerLevel world, WorksDefinition.Station station, int meta) {
        world.setBlock(absolute(station, new int[]{11, 1, 1}), wool(meta).defaultBlockState(), 2);
    }

    private static void placeCommandSign(ServerLevel world, BlockPos pos, String label, String command) {
        world.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), 2);
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof SignBlockEntity)) return;
        SignBlockEntity sign = (SignBlockEntity) tile;
        TextComponent clickable = new TextComponent(label);
        clickable.setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
        sign.setMessage(0, clickable);
        sign.setMessage(1, new TextComponent("right-click"));
        sign.setChanged();
    }

    private static void placeSign(ServerLevel world, BlockPos pos, String... lines) {
        world.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), 2);
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof SignBlockEntity)) return;
        SignBlockEntity sign = (SignBlockEntity) tile;
        for (int i = 0; i < 4; i++) {
            sign.setMessage(i, new TextComponent(i < lines.length ? trimSign(lines[i]) : ""));
        }
        sign.setChanged();
    }

    private static String trimSign(String text) {
        if (text == null) return "";
        return text.length() <= 15 ? text : text.substring(0, 15);
    }

    private static BlockPos absolute(WorksDefinition.Station station, int[] relative) {
        return new BlockPos(station.origin[0] + relative[0], station.origin[1] + relative[1],
                station.origin[2] + relative[2]);
    }

    private static void tagEntity(Entity entity, String stationId) {
        entity.getPersistentData().putString("AdvantageWorksStation", stationId);
    }

    private static ServerLevel overworld(MinecraftServer server) throws CommandRuntimeException {
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) throw commandError("Overworld is not loaded");
        return world;
    }

    private static Block block(String name) {
        if (name == null) return Blocks.AIR;
        String resolved = "minecraft:flowing_water".equals(name) ? "minecraft:water"
                : "minecraft:stonebrick".equals(name) ? "minecraft:stone_bricks" : name;
        Block value = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(resolved));
        return value == null ? Blocks.AIR : value;
    }

    private static Item item(String name) {
        if (name == null) return null;
        String resolved = "minecraft:sapling".equals(name) ? "minecraft:oak_sapling"
                : "minecraft:planks".equals(name) ? "minecraft:oak_planks" : name;
        ResourceLocation key = new ResourceLocation(resolved);
        return ForgeRegistries.ITEMS.containsKey(key) ? ForgeRegistries.ITEMS.getValue(key) : null;
    }

    private static CommandRuntimeException commandError(String message) {
        return new CommandRuntimeException(new TextComponent(message));
    }

    private static BlockState legacyState(String name, Block block, int metadata) {
        if (name != null && (name.startsWith("basepower:")
                || name.startsWith("basesteam:") || name.startsWith("baseelectric:"))) {
            return LegacyMigrationApi.decodeBlockState(new ResourceLocation(name), block, metadata);
        }
        if (block instanceof LiquidBlock) {
            return block.defaultBlockState().setValue(LiquidBlock.LEVEL, Integer.valueOf(metadata & 15));
        }
        return block.defaultBlockState();
    }

    private static ItemStack itemStack(Item item, int count, int damage) {
        ItemStack stack = new ItemStack(item, count);
        if (damage != 0) stack.setDamageValue(damage);
        return stack;
    }

    private static Fluid fluid(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        ResourceLocation id = name.indexOf(':') >= 0 ? new ResourceLocation(name)
                : new ResourceLocation("basepower", name);
        Fluid value = Registry.FLUID.getOptional(id).orElse(null);
        if (value == null) {
            String migratedName = LegacyMigrationApi.fluidTarget(name);
            if (!name.equals(migratedName)) {
                value = Registry.FLUID.getOptional(new ResourceLocation(migratedName)).orElse(null);
            }
        }
        if (value == null && name.indexOf(':') < 0) {
            value = Registry.FLUID.getOptional(new ResourceLocation("minecraft", name)).orElse(null);
        }
        return value;
    }

    private static Block wool(int metadata) {
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", colors[metadata & 15] + "_wool"));
    }
}
