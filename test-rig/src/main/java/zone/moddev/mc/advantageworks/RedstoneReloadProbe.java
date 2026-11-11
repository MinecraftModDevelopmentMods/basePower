package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.commands.Commands;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;

/** Separate cold-process experiment: finite earned work, never expected-state restoration. */
final class RedstoneReloadProbe {
    private static final File MARKER = new File("advantage-redstone-reload.allow");
    private static final File FIXTURE = new File("redstone-reload-fixture.json");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int OBSERVATION_TICKS = 16;
    private static final int READINESS_TICKS = 80;
    private enum Phase { FROZEN, PREPARING, SEEDING, RESTORING, OPERATING, DONE }

    private final String processToken = UUID.randomUUID().toString();
    private final List<Witness> witnesses = definitions();
    private final List<net.minecraft.world.level.block.entity.TickingBlockEntity> suspended = new ArrayList<>();
    private final List<Map<String, Object>> seedTrace = new ArrayList<>();
    private final List<Map<String, Object>> ticks = new ArrayList<>();
    private final List<Map<String, Object>> readiness = new ArrayList<>();
    private Phase phase = Phase.FROZEN;
    private ServerLevel world;
    private String label;
    private boolean used;
    private int preparationIndex;
    private int constructionCalls;
    private long tickStart;
    private Map<String, Object> initial;
    private Map<String, Object> restored;

    static void register() {
        if (!MARKER.isFile()) return;
        RedstoneReloadProbe probe = new RedstoneReloadProbe();
        MinecraftForge.EVENT_BUS.addListener(probe::commands);
        MinecraftForge.EVENT_BUS.addListener(probe::tick);
    }

    private static List<Witness> definitions() {
        List<Witness> result = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int offset = i == 8 ? 0 : i;
            result.add(new Witness(i == 8 ? "unheld" : "held-" + i, i != 8, offset,
                    new BlockPos(60000 + i * 64 + 14 + ((offset >> 1) & 1),
                            80 + (offset & 1), 60000 + ((offset >> 2) & 1))));
        }
        return result;
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advredstonereload")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("operation", StringArgumentType.word())
                        .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                            if (!MARKER.isFile() || used) throw new IllegalStateException("Missing marker or reused probe");
                            ServerLevel current = context.getSource().getLevel();
                            if (!current.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration"))
                                throw new IllegalStateException("Not an authorized disposable world");
                            String requested = StringArgumentType.getString(context, "label");
                            if (!requested.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Unsafe label");
                            world = current;
                            label = requested;
                            used = true;
                            try {
                                String operation = StringArgumentType.getString(context, "operation");
                                if ("seed".equals(operation)) {
                                    if (FIXTURE.exists()) throw new IllegalStateException("Never rebuild a saved fixture");
                                    phase = Phase.PREPARING;
                                } else if ("resume".equals(operation)) {
                                    JsonObject fixture = JSON.fromJson(new String(Files.readAllBytes(FIXTURE.toPath()),
                                            StandardCharsets.UTF_8), JsonObject.class);
                                    if (fixture.get("schema").getAsInt() != 1 || fixture.getAsJsonArray("cases").size() != 9
                                            || processToken.equals(fixture.get("processToken").getAsString()))
                                        throw new IllegalStateException("Missing cold-process seed authority");
                                    phase = Phase.RESTORING;
                                } else throw new IllegalArgumentException("Expected seed or resume");
                            } catch (Throwable failure) { fail(failure); }
                            return 1;
                        }))));
    }

    private boolean paused() { return phase != Phase.SEEDING && phase != Phase.OPERATING; }

    private void tick(TickEvent.LevelTickEvent event) {
        if (!(event.level instanceof ServerLevel) || event.level.dimension() != Level.OVERWORLD) return;
        ServerLevel current = (ServerLevel) event.level;
        if (event.phase == TickEvent.Phase.START) {
            tickStart = current.getGameTime();
            if (paused()) {
                suspended.addAll(NativeBlockEntityTicks.drain(current));
            }
            return;
        }
        NativeBlockEntityTicks.restore(current, suspended);
        if (current != world || phase == Phase.DONE) return;
        try {
            if (phase == Phase.PREPARING) {
                if (preparationIndex < witnesses.size()) {
                    construct(witnesses.get(preparationIndex++));
                } else if (ready("seed")) {
                    initial = frame(0);
                    phase = Phase.SEEDING;
                }
            } else if (phase == Phase.SEEDING) {
                Map<String, Object> observation = frame(seedTrace.size() + 1);
                seedTrace.add(observation);
                boolean ready = true;
                for (Witness witness : witnesses) {
                    CompoundTag tag = WorksBlockData.save(tile(witness));
                    ready &= tag.getShort("progress") > 0 && tag.contains("targetX", 99)
                            && ((PoweredEntity) tile(witness)).getEnergy(new ConduitType("electricity")) > 24;
                }
                if (ready) freezeSeed();
                else if (seedTrace.size() >= 16) throw new AssertionError("No finite natural mid-obsidian state within 16 ticks");
            } else if (phase == Phase.RESTORING) {
                if (preparationIndex < witnesses.size()) {
                    force(witnesses.get(preparationIndex++));
                } else if (ready("restart")) {
                    restored = frame(0);
                    writeResult("redstone-reload-restored-" + label + ".json", restored);
                    phase = Phase.OPERATING;
                }
            } else if (phase == Phase.OPERATING) {
                Map<String, Object> observation = frame(ticks.size() + 1);
                ticks.add(observation);
                writeResult(String.format(java.util.Locale.ROOT, "redstone-reload-tick-%s-%02d.json", label, ticks.size()), observation);
                if (ticks.size() == OBSERVATION_TICKS) {
                    phase = Phase.DONE;
                    Map<String, Object> report = metadata();
                    report.put("observationTicks", OBSERVATION_TICKS);
                    report.put("held", true);
                    report.put("restored", restored);
                    report.put("ticks", ticks);
                    report.put("readiness", readiness);
                    writeResult("redstone-reload-" + label + ".json", report);
                    complete();
                }
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void force(Witness witness) {
        // Include every neighbor and the 3x3 target, also at x=15 chunk edges.
        for (int x = (witness.pos.getX() - 1) >> 4; x <= (witness.pos.getX() + 1) >> 4; x++)
            for (int z = (witness.pos.getZ() - 1) >> 4; z <= (witness.pos.getZ() + 1) >> 4; z++) {
                world.setChunkForced(x, z, true);
                world.getChunk(x, z);
            }
    }

    private boolean ready(String stage) {
        boolean all = true;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Witness witness : witnesses) {
            BlockEntity tile = tile(witness);
            boolean ticking = world.getChunkSource().isPositionTicking(new net.minecraft.world.level.ChunkPos(witness.pos).toLong());
            boolean listed = NativeBlockEntityTicks.contains(world, tile);
            boolean blockAndEntitiesLoaded = Native118ChunkTicking.withEntitiesLoaded(world, witness.pos);
            boolean entityTicking = world.isPositionEntityTicking(witness.pos);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", witness.id);
            row.put("pos", coordinates(witness.pos));
            row.put("blockTicking", ticking);
            row.put("tickableListed", listed);
            row.put("blockAndEntitiesLoaded", blockAndEntitiesLoaded);
            row.put("entityTicking", entityTicking);
            rows.add(row);
            all &= nativeReady(ticking, listed, blockAndEntitiesLoaded, entityTicking);
        }
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("phase", stage);
        sample.put("worldTime", world.getGameTime());
        sample.put("cases", rows);
        readiness.add(sample);
        if (!all && readiness.size() >= READINESS_TICKS)
            throw new AssertionError("Native block/list/entity readiness timed out after " + READINESS_TICKS + " paused ticks");
        return all;
    }

    static boolean nativeReady(boolean blockTicking, boolean tickableListed,
            boolean blockAndEntitiesLoaded, boolean entityTicking) {
        return blockTicking && tickableListed && blockAndEntitiesLoaded && entityTicking;
    }

    private void construct(Witness witness) {
        if (phase != Phase.PREPARING || FIXTURE.exists()) throw new IllegalStateException("Construction outside fresh setup");
        force(witness);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            world.setBlock(witness.pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse("baseelectric:electric_drill"));
        if (block == Blocks.AIR) throw new IllegalStateException("Electric drill registry absent");
        world.setBlock(witness.pos, block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH), 2);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            world.setBlock(witness.pos.offset(dx, dy, -1), Blocks.OBSIDIAN.defaultBlockState(), 2);
        BlockEntity tile = tile(witness);
        ((PoweredEntity) tile).setEnergy(500, new ConduitType("electricity"));
        ItemStack dirt = new ItemStack(Items.DIRT, 7);
        WorksItemData.update(dirt, tag -> tag.putString("AdvWorksWitness", witness.id));
        ((Container) tile).setItem(0, dirt);
        ((Container) tile).setItem(1, new ItemStack(Items.APPLE, 13));
        tile.setChanged();
        constructionCalls++;
    }

    private BlockEntity tile(Witness witness) {
        BlockEntity tile = world.getBlockEntity(witness.pos);
        if (!(tile instanceof PoweredEntity) || !(tile instanceof Container))
            throw new IllegalStateException("Missing powered inventory at " + witness.pos);
        return tile;
    }

    private void freezeSeed() {
        // Freeze BEFORE adding redstone: no tick may erase the naturally earned target/progress.
        phase = Phase.FROZEN;
        List<Map<String, Object>> preHold = snapshots();
        for (Witness witness : witnesses) if (witness.held)
            world.setBlock(witness.pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        for (Witness witness : witnesses) tile(witness).setChanged();
        Map<String, Object> fixture = metadata();
        fixture.put("initialEnergy", 500);
        fixture.put("initial", initial);
        fixture.put("seedTrace", seedTrace);
        fixture.put("readiness", readiness);
        fixture.put("preHold", preHold);
        fixture.put("worldTime", world.getGameTime());
        fixture.put("cases", snapshots());
        fixture.put("freezeMethod", "Suspend tickable list before adding holds; no expected NBT writes; restart the same world");
        writeNew(FIXTURE, fixture);
        complete();
    }

    private Map<String, Object> frame(int index) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("tickStartWorldTime", tickStart);
        result.put("worldTime", world.getGameTime());
        result.put("cases", snapshots());
        return result;
    }

    private List<Map<String, Object>> snapshots() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Witness witness : witnesses) {
            BlockEntity tile = tile(witness);
            CompoundTag nbt = WorksBlockData.save(tile);
            BlockState state = world.getBlockState(witness.pos);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", witness.id);
            row.put("pos", coordinates(witness.pos));
            row.put("held", witness.held);
            row.put("offset", witness.offset);
            row.put("block", net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
            row.put("state", state.toString());
            row.put("active", active(state));
            row.put("signal", world.hasNeighborSignal(witness.pos));
            row.put("holdBlock", net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(world.getBlockState(witness.pos.above()).getBlock()).toString());
            row.put("energy", ((PoweredEntity) tile).getEnergy(new ConduitType("electricity")));
            row.put("progress", (int) nbt.getShort("progress"));
            row.put("nbt", nbt.toString());
            List<Map<String, Object>> targets = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = witness.pos.offset(dx, dy, -1);
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("pos", coordinates(pos));
                target.put("state", world.getBlockState(pos).toString());
                targets.add(target);
            }
            row.put("targets", targets);
            result.add(row);
        }
        return result;
    }

    private static boolean active(BlockState state) {
        for (net.minecraft.world.level.block.state.properties.Property<?> property : state.getProperties())
            if (property.getName().equals("active")) return Boolean.TRUE.equals(state.getValue(property));
        throw new IllegalStateException("Drill has no ACTIVE property");
    }

    private static int[] coordinates(BlockPos pos) { return new int[]{pos.getX(), pos.getY(), pos.getZ()}; }

    private Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 1);
        result.put("processToken", processToken);
        result.put("constructionCallsThisProcess", constructionCalls);
        return result;
    }

    private void fail(Throwable failure) {
        phase = Phase.DONE;
        Map<String, Object> report = metadata();
        report.put("failure", failure.toString());
        java.io.StringWriter trace = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        report.put("traceback", trace.toString());
        report.put("seedTrace", seedTrace);
        report.put("ticks", ticks);
        report.put("readiness", readiness);
        writeResult("redstone-reload-failure-" + label + ".json", report);
        complete();
    }

    private void complete() { System.out.println("ADVREDSTONE COMPLETE " + label); }

    private static void writeResult(String name, Object value) { writeNew(new File("advantage-works-results", name), value); }

    private static void writeNew(File path, Object value) {
        try {
            if (path.getParentFile() != null) Files.createDirectories(path.getParentFile().toPath());
            Files.write(path.toPath(), JSON.toJson(value).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot retain immutable observation " + path, error); }
    }

    private static final class Witness {
        final String id;
        final boolean held;
        final int offset;
        final BlockPos pos;
        Witness(String id, boolean held, int offset, BlockPos pos) {
            this.id = id; this.held = held; this.offset = offset; this.pos = pos;
        }
    }
}
