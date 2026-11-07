package zone.moddev.mc.advantageworks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The original sample order, with a bounded batch and a return to the server between phases. */
final class WorldgenSamplingJob {
    static final int SEARCH_BATCH = 256;
    static final int PLAN_BATCH = 64;
    static final int POPULATION_BATCH = 4;
    static final int COUNT_BATCH = 2;
    static final int MAX_IN_FLIGHT = 4;
    private static final int SEARCH_SIDE = 129;

    interface WorldAccess {
        boolean matchesBiome(String expected, int x, int y, int z);
        CompletableFuture<SampleChunk> requestChunk(int chunkX, int chunkZ, boolean counting);
        void releaseChunk(int chunkX, int chunkZ);
        void close();
        default Map<String, Object> diagnostics() { return java.util.Collections.emptyMap(); }
    }

    interface SampleChunk {
        boolean centerMatches(String expected);
        String blockName(int x, int y, int z);
    }

    enum Phase { SPAWN, DESERT, DESERT_SQUARE, OCEAN, OCEAN_SQUARE, HALO, POPULATE, COUNT, DONE, FAILED }

    static final class Slot {
        private WorldgenSamplingJob active;
        void start(WorldgenSamplingJob job) {
            if (active != null) throw new IllegalStateException("A worldgen sample is already running");
            active = job;
        }
        WorldgenSamplingJob current() { return active; }
        void release() {
            try { if (active != null) active.close(); }
            finally { active = null; }
        }
    }

    private final WorldAccess world;
    private final int spawnX, spawnY, spawnZ, seaLevel, radius;
    private final Set<Long> chunks = new LinkedHashSet<>();
    private final Set<Long> populationChunks = new LinkedHashSet<>();
    private final Map<String, String> expectedBiomes = new LinkedHashMap<>();
    private final Map<String, String> centers = new LinkedHashMap<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Map<String, Long> violations = new LinkedHashMap<>();
    private final Map<String, Long> boundaryBlocks = new LinkedHashMap<>();
    private final Map<Long, CompletableFuture<SampleChunk>> pending = new LinkedHashMap<>();
    private Phase phase = Phase.SPAWN;
    private int squareX, squareZ, squareIndex, searchIndex;
    private int locatedX, locatedZ;
    private boolean located;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private Iterator<Long> iterator;
    private RuntimeException failure;
    private boolean closed;

    WorldgenSamplingJob(WorldAccess world, int spawnX, int spawnY, int spawnZ, int seaLevel, int radius) {
        if (radius < 1 || radius > 16) throw new IllegalArgumentException("Worldgen radius must be 1..16");
        this.world = world;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.seaLevel = seaLevel;
        this.radius = radius;
        squareX = spawnX >> 4;
        squareZ = spawnZ >> 4;
        centers.put("spawn", squareX + "," + squareZ);
        expectedBiomes.put("basepower:crude_oil", "DESERT");
        expectedBiomes.put("mineralogy:crude_oil", "OCEAN");
        expectedBiomes.put("baseelectric:li_ore", null);
        expectedBiomes.put("baseelectric:sulfur_ore", null);
        expectedBiomes.put("mineralogy:sulfur_ore", null);
        for (String id : expectedBiomes.keySet()) {
            counts.put(id, 0L);
            if (expectedBiomes.get(id) != null) {
                violations.put(id, 0L);
                boundaryBlocks.put(id, 0L);
            }
        }
    }

    void tick() {
        if (finished()) return;
        try {
            switch (phase) {
                case SPAWN: addSquare(Phase.DESERT); break;
                case DESERT: search("DESERT", Phase.DESERT_SQUARE, Phase.OCEAN); break;
                case DESERT_SQUARE: addSquare(Phase.OCEAN); break;
                case OCEAN: search("OCEAN", Phase.OCEAN_SQUARE, Phase.HALO); break;
                case OCEAN_SQUARE: addSquare(Phase.HALO); break;
                case HALO: planHalo(); break;
                case POPULATE: populate(); break;
                case COUNT: count(); break;
                default: break;
            }
        } catch (RuntimeException exception) {
            failure = exception;
            phase = Phase.FAILED;
        }
        if (finished()) cleanup();
    }

    Phase phase() { return phase; }
    boolean finished() { return phase == Phase.DONE || phase == Phase.FAILED; }
    RuntimeException failure() { return failure; }
    Map<String, Object> diagnostics() { return world.diagnostics(); }

    void close() {
        if (!finished()) {
            failure = new IllegalStateException("Worldgen sample cancelled before completion");
            phase = Phase.FAILED;
        }
        cleanup();
    }

    private void cleanup() {
        if (closed) return;
        closed = true;
        for (CompletableFuture<SampleChunk> future : pending.values()) future.cancel(false);
        pending.clear();
        try { world.close(); }
        catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else if (failure != exception) failure.addSuppressed(exception);
            phase = Phase.FAILED;
        }
    }

    Map<String, Object> result() {
        if (phase != Phase.DONE) throw new IllegalStateException("Worldgen sample is not complete", failure);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("radius", radius);
        payload.put("chunksScanned", chunks.size());
        payload.put("centers", centers);
        payload.put("counts", counts);
        payload.put("biomeViolations", violations);
        payload.put("biomeBoundaryBlocks", boundaryBlocks);
        return payload;
    }

    private void addSquare(Phase next) {
        int side = radius * 2 + 1;
        for (int work = 0; work < SEARCH_BATCH && squareIndex < side * side; work++, squareIndex++) {
            chunks.add(pack(squareX - radius + squareIndex % side, squareZ - radius + squareIndex / side));
        }
        if (squareIndex == side * side) {
            squareIndex = 0;
            phase = next;
        }
    }

    private void search(String expected, Phase found, Phase absent) {
        for (int work = 0; work < SEARCH_BATCH && searchIndex < SEARCH_SIDE * SEARCH_SIDE;
                work++, searchIndex++) {
            int x = ((spawnX >> 4) - 256 + searchIndex % SEARCH_SIDE * 4) << 4;
            int z = ((spawnZ >> 4) - 256 + searchIndex / SEARCH_SIDE * 4) << 4;
            if (!world.matchesBiome(expected, x, seaLevel, z)) continue;
            // Vec3i.distanceSq(Vec3i) uses the candidate block's center in the mapped native API.
            double dx = x + 0.5D - spawnX, dy = seaLevel + 0.5D - spawnY, dz = z + 0.5D - spawnZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                located = true;
                locatedX = x >> 4;
                locatedZ = z >> 4;
                bestDistance = distance;
            }
        }
        if (searchIndex == SEARCH_SIDE * SEARCH_SIDE) {
            centers.put(expected, located ? locatedX + "," + locatedZ : "NOT_FOUND");
            squareX = locatedX;
            squareZ = locatedZ;
            phase = located ? found : absent;
            searchIndex = 0;
            located = false;
            bestDistance = Double.POSITIVE_INFINITY;
        }
    }

    private void planHalo() {
        if (iterator == null) iterator = chunks.iterator();
        for (int work = 0; work < PLAN_BATCH && iterator.hasNext(); work++) {
            long packed = iterator.next();
            int x = (int) (packed >> 32), z = (int) packed;
            for (int haloZ = z - 2; haloZ <= z + 2; haloZ++) {
                for (int haloX = x - 2; haloX <= x + 2; haloX++) {
                    populationChunks.add(pack(haloX, haloZ));
                }
            }
        }
        if (!iterator.hasNext()) {
            iterator = populationChunks.iterator();
            phase = Phase.POPULATE;
        }
    }

    private void populate() {
        advanceChunks(false);
        if (!iterator.hasNext() && pending.isEmpty()) {
            iterator = chunks.iterator();
            phase = Phase.COUNT;
        }
    }

    private void count() {
        advanceChunks(true);
        if (!iterator.hasNext() && pending.isEmpty()) phase = Phase.DONE;
    }

    private void advanceChunks(boolean counting) {
        // Inspect only completed futures; the server tick must never wait for native lighting.
        for (CompletableFuture<SampleChunk> future : pending.values()) {
            if (future.isDone()) Objects.requireNonNull(future.getNow(null), "Native FULL chunk was lost");
        }
        int budget = counting ? COUNT_BATCH : POPULATION_BATCH;
        Iterator<Map.Entry<Long, CompletableFuture<SampleChunk>>> completed = pending.entrySet().iterator();
        for (int work = 0; work < budget && completed.hasNext(); work++) {
            Map.Entry<Long, CompletableFuture<SampleChunk>> entry = completed.next();
            if (!entry.getValue().isDone()) break;
            long packed = entry.getKey();
            int chunkX = (int) (packed >> 32), chunkZ = (int) packed;
            SampleChunk chunk = Objects.requireNonNull(entry.getValue().getNow(null), "Native FULL chunk was lost");
            if (counting) countChunk(chunk, chunkX, chunkZ);
            completed.remove();
            world.releaseChunk(chunkX, chunkZ);
        }
        for (int work = 0; work < budget && pending.size() < MAX_IN_FLIGHT && iterator.hasNext(); work++) {
            long packed = iterator.next();
            CompletableFuture<SampleChunk> future = Objects.requireNonNull(
                    world.requestChunk((int) (packed >> 32), (int) packed, counting), "Native chunk request was null");
            pending.put(packed, future);
        }
    }

    private void countChunk(SampleChunk chunk, int chunkX, int chunkZ) {
        int minX = chunkX << 4, minZ = chunkZ << 4;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int y = 0; y <= 96; y++) {
                    int x = minX + localX, z = minZ + localZ;
                    String name = chunk.blockName(x, y, z);
                    if (name == null || !counts.containsKey(name)) continue;
                    counts.put(name, counts.get(name) + 1L);
                    String expected = expectedBiomes.get(name);
                    if (expected != null && !chunk.centerMatches(expected)) {
                        violations.put(name, violations.get(name) + 1L);
                    } else if (expected != null && !world.matchesBiome(expected, x, y, z)) {
                        boundaryBlocks.put(name, boundaryBlocks.get(name) + 1L);
                    }
                }
            }
        }
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
