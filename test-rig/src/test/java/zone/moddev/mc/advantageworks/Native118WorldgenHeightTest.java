package zone.moddev.mc.advantageworks;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native118WorldgenHeightTest {
    @Test public void countsFromTheNativeNegativeBuildHeight() {
        WorldgenSamplingJob.WorldAccess world = new WorldgenSamplingJob.WorldAccess() {
            @Override public int minBuildHeight() { return -64; }
            @Override public boolean matchesBiome(String expected, int x, int y, int z) { return false; }
            @Override public void releaseChunk(int x, int z) { }
            @Override public void close() { }
            @Override public CompletableFuture<WorldgenSamplingJob.SampleChunk> requestChunk(int x, int z, boolean count) {
                return CompletableFuture.completedFuture(new WorldgenSamplingJob.SampleChunk() {
                    @Override public boolean centerMatches(String expected) { return false; }
                    @Override public String blockName(int bx, int y, int bz) {
                        assertTrue(count);
                        assertTrue(y >= -64 && y <= 96);
                        return y == -64 && bx == x * 16 && bz == z * 16 ? "baseelectric:sulfur_ore" : null;
                    }
                });
            }
        };
        WorldgenSamplingJob job = new WorldgenSamplingJob(world, 0, 70, 0, 64, 1);
        for (int tick = 0; tick < 500 && !job.finished(); tick++) job.tick();
        assertTrue(job.finished());
        assertNull(job.failure());
        assertEquals(9L, ((Map<?, ?>) job.result().get("counts")).get("baseelectric:sulfur_ore"));
    }
}
