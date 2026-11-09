package zone.moddev.mc.advantageworks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;

/** Observes native chunk readiness; never loads chunks, constructs actors or changes world state. */
final class Native117Readiness {
    static final String PROTOCOL = "native117-chunk-readiness-v1";
    static final int MAX_CHUNKS = 256;
    static final int MAX_CHECKS = 1200;
    static final long MAX_WAIT_NANOS = 120_000_000_000L;
    private static final File MARKER = new File("advantage-works.allow");
    private ServerLevel world;
    private String label;
    private File output;
    private Observation observation;

    static void register() {
        if (!MARKER.isFile()) return;
        Native117Readiness probe = new Native117Readiness();
        MinecraftForge.EVENT_BUS.addListener(probe::commands);
        MinecraftForge.EVENT_BUS.addListener(probe::tick);
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advreadiness").requires(source -> source.hasPermission(4))
                .then(Commands.literal("chunks")
                    .then(Commands.argument("minX", IntegerArgumentType.integer(-1875000, 1874999))
                    .then(Commands.argument("minZ", IntegerArgumentType.integer(-1875000, 1874999))
                    .then(Commands.argument("maxX", IntegerArgumentType.integer(-1875000, 1874999))
                    .then(Commands.argument("maxZ", IntegerArgumentType.integer(-1875000, 1874999))
                    .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                        WorksController.getInstance().requireAuthorizedWorld(context.getSource().getServer());
                        request(context.getSource().getLevel(), new Bounds(
                                IntegerArgumentType.getInteger(context, "minX"),
                                IntegerArgumentType.getInteger(context, "minZ"),
                                IntegerArgumentType.getInteger(context, "maxX"),
                                IntegerArgumentType.getInteger(context, "maxZ")),
                                StringArgumentType.getString(context, "label"));
                        return 1;
                    }))))))));
    }

    private void request(ServerLevel level, Bounds bounds, String requestedLabel) {
        if (!MARKER.isFile() || observation != null) throw new IllegalStateException("Unmarked or busy readiness probe");
        if (!requestedLabel.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Unsafe readiness label");
        File destination = new File("advantage-works-results", "native-readiness-" + requestedLabel + ".json");
        if (destination.exists()) throw new IllegalStateException("Refusing to overwrite readiness evidence");
        world = level;
        label = requestedLabel;
        output = destination;
        observation = new Observation(bounds, level.getGameTime(), System.nanoTime());
        System.out.println("ADVREADINESS QUEUED " + label);
    }

    private void tick(TickEvent.WorldTickEvent event) {
        if (observation == null || event.phase != TickEvent.Phase.END || event.world != world) return;
        List<State> states = new ArrayList<>();
        for (int x = observation.bounds.minX; x <= observation.bounds.maxX; x++) {
            for (int z = observation.bounds.minZ; z <= observation.bounds.maxZ; z++) {
                BlockPos pos = new BlockPos(x * 16, world.getMinBuildHeight(), z * 16);
                states.add(new State(x, z, world.isPositionTickingWithEntitiesLoaded(pos),
                        world.isPositionEntityTicking(new ChunkPos(x, z))));
            }
        }
        if (!observation.observe(world.getGameTime(), System.nanoTime(), states)) return;
        JsonObject report = observation.report();
        report.addProperty("label", label);
        report.addProperty("minecraft", "1.17.1");
        report.addProperty("dimension", world.dimension().location().toString());
        try {
            Files.createDirectories(output.toPath().getParent());
            Files.writeString(output.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot retain readiness evidence", failure); }
        String completedLabel = label;
        boolean passed = observation.passed;
        observation = null;
        world = null;
        output = null;
        label = null;
        System.out.println("ADVREADINESS RESULT " + completedLabel + " status=" + (passed ? "PASS" : "FAIL"));
        System.out.println("ADVREADINESS " + (passed ? "COMPLETE " : "FAILED ") + completedLabel);
    }

    static final class Bounds {
        final int minX, minZ, maxX, maxZ;

        Bounds(int minX, int minZ, int maxX, int maxZ) {
            long width = (long) maxX - minX + 1, depth = (long) maxZ - minZ + 1;
            if (minX < -1875000 || minZ < -1875000 || maxX > 1874999 || maxZ > 1874999
                    || width <= 0 || depth <= 0 || width > 64 || depth > 64 || width * depth > MAX_CHUNKS) {
                throw new IllegalArgumentException("Readiness requires an ordered rectangle of at most 256 chunks and 64 per axis");
            }
            this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        }

        int size() { return (maxX - minX + 1) * (maxZ - minZ + 1); }
    }

    static final class State {
        final int x, z;
        final boolean blockAndEntitiesLoaded, entityTicking;

        State(int x, int z, boolean blockAndEntitiesLoaded, boolean entityTicking) {
            this.x = x; this.z = z;
            this.blockAndEntitiesLoaded = blockAndEntitiesLoaded;
            this.entityTicking = entityTicking;
        }

        boolean ready() { return blockAndEntitiesLoaded && entityTicking; }
        boolean same(State other) {
            return x == other.x && z == other.z && blockAndEntitiesLoaded == other.blockAndEntitiesLoaded
                    && entityTicking == other.entityTicking;
        }
        JsonObject json() {
            JsonObject row = new JsonObject();
            row.addProperty("x", x); row.addProperty("z", z);
            row.addProperty("blockAndEntitiesLoaded", blockAndEntitiesLoaded);
            row.addProperty("entityTicking", entityTicking);
            return row;
        }
    }

    static final class Observation {
        final Bounds bounds;
        final long requestedWorldTime, startedNanos;
        final JsonArray transitions = new JsonArray();
        List<State> latest = new ArrayList<>();
        int checks;
        long completedWorldTime, elapsedNanos;
        boolean finished, passed;

        Observation(Bounds bounds, long requestedWorldTime, long startedNanos) {
            this.bounds = bounds; this.requestedWorldTime = requestedWorldTime; this.startedNanos = startedNanos;
        }

        boolean observe(long worldTime, long nowNanos, List<State> states) {
            if (finished) throw new IllegalStateException("Readiness observation is terminal");
            if (states.size() != bounds.size()) throw new IllegalArgumentException("Incomplete readiness sample");
            int index = 0;
            for (int x = bounds.minX; x <= bounds.maxX; x++) for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                State state = states.get(index++);
                if (state.x != x || state.z != z) throw new IllegalArgumentException("Readiness sample has duplicate or misplaced chunks");
            }
            checks++;
            JsonArray changes = new JsonArray();
            for (int i = 0; i < states.size(); i++) {
                if (latest.isEmpty() || !states.get(i).same(latest.get(i))) changes.add(states.get(i).json());
            }
            if (changes.size() > 0) {
                JsonObject transition = new JsonObject();
                transition.addProperty("check", checks);
                transition.addProperty("worldTime", worldTime);
                transition.add("changes", changes);
                transitions.add(transition);
            }
            latest = new ArrayList<>(states);
            completedWorldTime = worldTime;
            elapsedNanos = nowNanos - startedNanos;
            if (elapsedNanos < 0) throw new IllegalArgumentException("Readiness clock moved backwards");
            passed = checks <= MAX_CHECKS && elapsedNanos <= MAX_WAIT_NANOS && latest.stream().allMatch(State::ready);
            finished = passed || checks >= MAX_CHECKS || elapsedNanos >= MAX_WAIT_NANOS;
            return finished;
        }

        JsonObject report() {
            if (!finished) throw new IllegalStateException("Unfinished readiness cannot produce a receipt");
            JsonObject report = new JsonObject();
            report.addProperty("protocol", PROTOCOL);
            report.addProperty("status", passed ? "PASS" : "FAIL");
            if (!passed) report.addProperty("failure", "Native chunk readiness timed out");
            report.addProperty("requestedWorldTime", requestedWorldTime);
            report.addProperty("completedWorldTime", completedWorldTime);
            report.addProperty("checks", checks);
            report.addProperty("elapsedMillis", elapsedNanos / 1_000_000L);
            report.addProperty("maximumChecks", MAX_CHECKS);
            report.addProperty("maximumWaitMillis", MAX_WAIT_NANOS / 1_000_000L);
            JsonObject rectangle = new JsonObject();
            rectangle.addProperty("minX", bounds.minX); rectangle.addProperty("minZ", bounds.minZ);
            rectangle.addProperty("maxX", bounds.maxX); rectangle.addProperty("maxZ", bounds.maxZ);
            report.add("bounds", rectangle);
            JsonArray chunks = new JsonArray();
            latest.forEach(state -> chunks.add(state.json()));
            report.add("chunks", chunks);
            report.add("transitions", transitions);
            return report;
        }
    }
}
