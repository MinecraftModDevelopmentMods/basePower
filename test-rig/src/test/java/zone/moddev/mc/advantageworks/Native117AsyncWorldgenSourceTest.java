package zone.moddev.mc.advantageworks;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.Assert.*;

public class Native117AsyncWorldgenSourceTest {
    @Test public void actualNativeUnknownTicketExpiresButDedicatedRegionTicketDoesNot() {
        assertEquals(1L, TicketType.UNKNOWN.timeout());
        TicketType<ChunkPos> retained = TicketType.create("async-worldgen-unit", Comparator.comparingLong(ChunkPos::toLong));
        assertEquals(0L, retained.timeout());
    }

    @Test public void actualMappedNativeFutureApiHasBothBlockingAndOffThreadBranches() throws Exception {
        List<String> calls = new ArrayList<>();
        try (InputStream input = ServerChunkCache.class.getResourceAsStream("ServerChunkCache.class")) {
            assertNotNull("Actual mapped Forge class is required", input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!"getChunkFuture".equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String desc, boolean isInterface) {
                            calls.add(method);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.contains("getChunkFutureMainThread"));
        assertTrue("Calling this API on a server tick still blocks", calls.contains("managedBlock"));
        assertTrue("Use the native off-thread scheduling branch", calls.contains("supplyAsync"));
        assertTrue(calls.contains("thenCompose"));
    }

    @Test public void nativeWorldgenAdapterNeverSynchronouslyRequestsFullChunks() throws Exception {
        String controller = source("WorksController.java");
        int start = controller.indexOf("synchronized String sampleWorldgen(");
        int end = controller.indexOf("List<String> stationIdsWithAll()");
        assertFalse("Population and count must not block the native server tick",
                controller.substring(start, end).contains("world.getChunk("));
        Path adapter = Path.of("src/main/java/zone/moddev/mc/advantageworks/Native117WorldgenAccess.java");
        assertTrue("A native async adapter must own the FULL futures", Files.isRegularFile(adapter));
        String nativeSource = Files.readString(adapter, StandardCharsets.UTF_8);
        assertTrue(nativeSource.matches("(?s).*getChunkFuture\\(.*ChunkStatus\\.FULL,\\s*true\\).*"));
        assertTrue(nativeSource.contains("isLightCorrect()"));
        assertTrue(nativeSource.contains("addRegionTicket(TICKET, position, 0, position)"));
        assertTrue(nativeSource.contains("removeRegionTicket(TICKET, position, 0, position)"));
        assertFalse(nativeSource.contains(".join()"));
        assertFalse(nativeSource.contains("world.getChunk("));
    }

    @Test public void samplerUsesPolledFuturesRatherThanSynchronousPopulation() throws Exception {
        String source = source("WorldgenSamplingJob.java");
        assertTrue("Pending native futures must be representable", source.contains("CompletableFuture<SampleChunk>"));
        assertTrue(source.contains(".isDone()"));
        assertTrue(source.contains(".getNow(null)"));
        assertFalse(source.contains(".join()"));
    }

    @Test public void serverStopClosesTheOwnedJobAndDispatchExecutor() throws Exception {
        String controller = source("WorksController.java");
        assertTrue(controller.contains("addListener(this::worldgenStopping)"));
        int start = controller.indexOf("void worldgenStopping(ServerStoppingEvent");
        int end = controller.indexOf("static boolean matchesExpectedBiome", start);
        assertTrue(start >= 0 && end > start);
        assertTrue(controller.substring(start, end).contains("worldgenJob.release()"));
        assertTrue(source("AsyncWorldgenChunks.java").contains("executor.shutdownNow()"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/zone/moddev/mc/advantageworks", name), StandardCharsets.UTF_8);
    }
}
