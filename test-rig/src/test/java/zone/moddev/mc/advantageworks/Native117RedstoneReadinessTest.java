package zone.moddev.mc.advantageworks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native scheduling preconditions, independent of the drill's expected outcome. */
public class Native117RedstoneReadinessTest {
    @Test public void blockTickingAndRegistrationCannotReplaceLoadedEntitySections() {
        assertFalse(RedstoneReloadProbe.nativeReady(true, true, false, true));
    }

    @Test public void loadedEntitySectionsCannotReplaceEntityTickingReadiness() {
        assertFalse(RedstoneReloadProbe.nativeReady(true, true, true, false));
    }

    @Test public void onlyAllFourNativePreconditionsReleaseAcrossEveryCombination() {
        for (int mask = 0; mask < 16; mask++) {
            assertEquals("native readiness mask " + mask, mask == 15,
                    RedstoneReloadProbe.nativeReady((mask & 1) != 0, (mask & 2) != 0,
                            (mask & 4) != 0, (mask & 8) != 0));
        }
    }

    @Test public void runtimeRecordsActualQueriesAndNeverWaitsForTheExpectedResult() throws Exception {
        String source = Files.readString(Paths.get("src/main/java/zone/moddev/mc/advantageworks/RedstoneReloadProbe.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private boolean ready(String stage)");
        int end = source.indexOf("private void construct(Witness witness)", start);
        assertTrue(start >= 0 && end > start);
        String readiness = source.substring(start, end);
        assertTrue(readiness.contains("Native118ChunkTicking.withEntitiesLoaded(world, witness.pos)"));
        assertTrue(readiness.contains("world.isPositionEntityTicking(witness.pos)"));
        assertTrue(readiness.contains("NativeBlockEntityTicks.contains(world, tile)"));
        assertTrue(readiness.contains("all &= nativeReady(ticking, listed, blockAndEntitiesLoaded, entityTicking);"));
        for (String field : new String[]{"blockTicking", "tickableListed", "blockAndEntitiesLoaded", "entityTicking"}) {
            assertTrue(field, readiness.contains("row.put(\"" + field + "\","));
        }
        for (String forbidden : new String[]{"getEnergy(", "progress", "ACTIVE", "frame(", "snapshots(", ".tickUpdate(", ".powerUpdate("}) {
            assertFalse(forbidden, readiness.contains(forbidden));
        }
        assertTrue(source.contains("private static final int OBSERVATION_TICKS = 16;"));
        assertTrue(source.contains("private static final int READINESS_TICKS = 80;"));
    }
}
