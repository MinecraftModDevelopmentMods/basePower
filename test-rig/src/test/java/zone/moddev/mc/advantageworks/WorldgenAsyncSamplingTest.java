package zone.moddev.mc.advantageworks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldgenAsyncSamplingTest {
    @Test public void pendingNativeFuturesReturnEveryTickAndHoldExactlyFourSlots() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = population(world);
        job.tick();
        assertEquals(4, world.active.size());
        for (int tick = 0; tick < 100; tick++) job.tick();
        assertEquals(4, world.requests.size());
        assertEquals(0, world.reads);
        assertEquals(WorldgenSamplingJob.Phase.POPULATE, job.phase());
        job.close();
        assertEquals(0, world.active.size());
        assertEquals(1, world.closes);
    }

    @Test public void outOfOrderCompletionDoesNotChangePopulationOrderOrSkipTheHead() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = population(world);
        job.tick();
        List<Long> first = new ArrayList<>(world.active.keySet());
        for (int index = 3; index > 0; index--) world.active.get(first.get(index)).complete(world.chunk);
        job.tick();
        assertTrue(world.released.isEmpty());
        assertEquals(4, world.requests.size());
        world.active.get(first.get(0)).complete(world.chunk);
        job.tick();
        assertEquals(first, world.released);
        assertEquals(8, world.requests.size());
        assertEquals(4, world.active.size());
        assertEquals(0, world.reads);
        job.close();
    }

    @Test public void anExceptionalFutureBehindAPendingHeadFailsWithoutAPartialReport() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = population(world);
        job.tick();
        new ArrayList<>(world.active.values()).get(2).completeExceptionally(new IllegalStateException("native failure"));
        job.tick();
        failedAndClosed(job, world);
        assertTrue(job.failure().toString().contains("native failure"));
    }

    @Test public void aLostNullChunkBehindAPendingHeadFailsInsteadOfBecomingEmptyTerrain() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = population(world);
        job.tick();
        new ArrayList<>(world.active.values()).get(1).complete(null);
        job.tick();
        failedAndClosed(job, world);
        assertTrue(job.failure().getMessage().contains("chunk was lost"));
    }

    @Test public void aNullRequestFutureIsNotAcceptedAsReadiness() {
        ControlledWorld world = new ControlledWorld();
        world.nullRequest = true;
        WorldgenSamplingJob job = population(world);
        job.tick();
        failedAndClosed(job, world);
        assertTrue(job.failure().getMessage().contains("request was null"));
    }

    @Test public void countLoadsAlsoYieldAndReadAtMostTwoCompletedFullChunksPerTick() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = population(world);
        while (job.phase() != WorldgenSamplingJob.Phase.COUNT) {
            world.completeAll();
            job.tick();
            assertNull(job.failure());
        }
        assertEquals(0, world.reads);
        job.tick();
        for (int tick = 0; tick < 10; tick++) job.tick();
        assertEquals(4, world.active.size());
        assertEquals(0, world.reads);
        world.completeAll();
        job.tick();
        assertEquals(2L * 16 * 16 * 97, world.reads);
        assertEquals(WorldgenSamplingJob.Phase.COUNT, job.phase());
        job.close();
        assertTrue(world.active.isEmpty());
    }

    @Test public void releasingTheJobSlotCancelsAllOwnedPendingRequestsOnce() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = population(world);
        WorldgenSamplingJob.Slot slot = new WorldgenSamplingJob.Slot();
        slot.start(job);
        job.tick();
        List<CompletableFuture<WorldgenSamplingJob.SampleChunk>> futures = new ArrayList<>(world.active.values());
        slot.release();
        slot.release();
        assertNull(slot.current());
        failedAndClosed(job, world);
        for (CompletableFuture<?> future : futures) assertTrue(future.isCancelled());
        job.tick();
        assertEquals(1, world.closes);
    }

    @Test public void zeroCountsStillRequireTheComplete867ChunkInventoryAndAllReads() {
        ControlledWorld world = new ControlledWorld();
        WorldgenSamplingJob job = new WorldgenSamplingJob(world, 0, 70, 0, 64, 8);
        int ticks = 0;
        while (!job.finished()) {
            world.completeAll();
            int requests = world.requests.size();
            long reads = world.reads;
            job.tick();
            assertTrue(world.requests.size() - requests <= 4);
            assertTrue(world.reads - reads <= 2L * 16 * 16 * 97);
            assertTrue(world.active.size() <= 4);
            assertTrue(++ticks < 5000);
        }
        assertNull(job.failure());
        assertEquals(867, job.result().get("chunksScanned"));
        assertEquals(867L * 16 * 16 * 97, world.reads);
        for (Object count : ((Map<?, ?>) job.result().get("counts")).values()) assertEquals(0L, count);
        assertEquals(1, world.closes);
        assertTrue(world.active.isEmpty());
    }

    @Test public void ticketCleanupFailureCannotTurnIntoACompletedSample() {
        ControlledWorld world = new ControlledWorld();
        world.failClose = true;
        WorldgenSamplingJob job = population(world);
        job.close();
        assertEquals(WorldgenSamplingJob.Phase.FAILED, job.phase());
        assertEquals(1, job.failure().getSuppressed().length);
        requireNoResult(job);
    }

    private static WorldgenSamplingJob population(ControlledWorld world) {
        WorldgenSamplingJob job = new WorldgenSamplingJob(world, 0, 70, 0, 64, 1);
        for (int tick = 0; job.phase() != WorldgenSamplingJob.Phase.POPULATE; tick++) {
            assertTrue(tick < 1000);
            job.tick();
        }
        return job;
    }

    private static void failedAndClosed(WorldgenSamplingJob job, ControlledWorld world) {
        assertEquals(WorldgenSamplingJob.Phase.FAILED, job.phase());
        assertNotNull(job.failure());
        assertTrue(world.active.isEmpty());
        assertEquals(1, world.closes);
        requireNoResult(job);
    }

    private static void requireNoResult(WorldgenSamplingJob job) {
        try { job.result(); fail("Incomplete native chunks produced a sample"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("not complete")); }
    }

    private static final class ControlledWorld implements WorldgenSamplingJob.WorldAccess {
        final Map<Long, CompletableFuture<WorldgenSamplingJob.SampleChunk>> active = new LinkedHashMap<>();
        final List<Long> requests = new ArrayList<>(), released = new ArrayList<>();
        long reads;
        int closes;
        boolean nullRequest, failClose;
        final WorldgenSamplingJob.SampleChunk chunk = new WorldgenSamplingJob.SampleChunk() {
            @Override public boolean centerMatches(String expected) { return true; }
            @Override public String blockName(int x, int y, int z) {
                assertTrue(y >= 0 && y <= 96);
                reads++;
                return null;
            }
        };

        @Override public boolean matchesBiome(String expected, int x, int y, int z) {
            return z == 0 && x == ("DESERT".equals(expected) ? -1024 : 1024);
        }

        @Override public CompletableFuture<WorldgenSamplingJob.SampleChunk> requestChunk(int x, int z, boolean counting) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            assertFalse(active.containsKey(key));
            CompletableFuture<WorldgenSamplingJob.SampleChunk> future = new CompletableFuture<>();
            active.put(key, future);
            requests.add(key);
            return nullRequest ? null : future;
        }

        @Override public void releaseChunk(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            assertNotNull(active.remove(key));
            released.add(key);
        }

        @Override public void close() {
            closes++;
            for (CompletableFuture<?> future : active.values()) future.cancel(false);
            active.clear();
            if (failClose) throw new IllegalStateException("ticket cleanup failed");
        }

        void completeAll() {
            for (CompletableFuture<WorldgenSamplingJob.SampleChunk> future : active.values()) future.complete(chunk);
        }
    }
}
