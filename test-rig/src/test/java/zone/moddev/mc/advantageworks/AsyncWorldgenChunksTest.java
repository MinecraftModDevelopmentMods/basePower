package zone.moddev.mc.advantageworks;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class AsyncWorldgenChunksTest {
    @Test public void nativeDispatchIsOffOwnerThreadButTicketsStayOnOwnerThread() throws Exception {
        Backend backend = new Backend(4);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AsyncWorldgenChunks<String> chunks = new AsyncWorldgenChunks<>(backend, executor);
        try {
            for (int x = 0; x < 4; x++) assertFalse(chunks.request(x, -1).isDone());
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            assertEquals(4, backend.retains);
            assertEquals(4, chunks.diagnostics().get("peakInFlight"));
            try { chunks.request(4, -1); fail("Fifth native request was allowed"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("excess")); }
            try { chunks.request(0, -1); fail("Duplicate native request was allowed"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Duplicate")); }
            chunks.release(0, -1);
            assertFalse("Shared native futures must not be cancelled", backend.futures.get(0).isCancelled());
        } finally {
            chunks.close();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(4, backend.releases);
        assertEquals(0, chunks.diagnostics().get("activeTickets"));
        assertEquals(true, chunks.diagnostics().get("executorShutdown"));
        chunks.close();
        assertEquals(4, backend.releases);
    }

    @Test public void directServerThreadExecutorIsARealNegativeControl() throws Exception {
        Backend backend = new Backend(1);
        InlineExecutor executor = new InlineExecutor();
        AsyncWorldgenChunks<String> chunks = new AsyncWorldgenChunks<>(backend, executor);
        try {
            CompletableFuture<String> future = chunks.request(0, 0);
            assertTrue(future.isCompletedExceptionally());
            try { future.get(5, TimeUnit.SECONDS); fail("Blocking native API branch was accepted"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause().getMessage().contains("off the server thread"));
            }
            assertTrue(backend.futures.isEmpty());
        } finally { chunks.close(); }
        assertEquals(1, backend.releases);
    }

    @Test public void nativeExceptionIsNotConvertedToReadinessOrEmptyTerrain() throws Exception {
        Backend backend = new Backend(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AsyncWorldgenChunks<String> chunks = new AsyncWorldgenChunks<>(backend, executor);
        try {
            CompletableFuture<String> future = chunks.request(0, 0);
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            backend.futures.get(0).completeExceptionally(new IllegalStateException("native lost chunk"));
            try { future.get(5, TimeUnit.SECONDS); fail("Native exception was hidden"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertEquals("native lost chunk", expected.getCause().getMessage());
            }
        } finally { chunks.close(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        assertEquals(1, backend.releases);
    }

    @Test public void zeroNullNativeResultFailsAndTicketIsReleased() throws Exception {
        Backend backend = new Backend(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AsyncWorldgenChunks<String> chunks = new AsyncWorldgenChunks<>(backend, executor);
        try {
            CompletableFuture<String> future = chunks.request(0, 0);
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            backend.futures.get(0).complete(null);
            try { future.get(5, TimeUnit.SECONDS); fail("Null native chunk was accepted"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause().getMessage().contains("chunk was lost"));
            }
        } finally { chunks.close(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        assertEquals(1, backend.releases);
    }

    @Test public void rejectedDispatchReleasesItsTicketAndDoesNotLeaveAnActiveRequest() {
        Backend backend = new Backend(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        AsyncWorldgenChunks<String> chunks = new AsyncWorldgenChunks<>(backend, executor);
        try {
            try { chunks.request(0, 0); fail("Rejected dispatch was accepted"); }
            catch (java.util.concurrent.RejectedExecutionException expected) { }
            assertEquals(0, chunks.diagnostics().get("activeTickets"));
        } finally { chunks.close(); }
        assertEquals(1, backend.retains);
        assertEquals(1, backend.releases);
    }

    @Test public void lateNativeCompletionAfterStopCannotResurrectRequestsOrTickets() throws Exception {
        Backend backend = new Backend(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AsyncWorldgenChunks<String> chunks = new AsyncWorldgenChunks<>(backend, executor);
        CompletableFuture<String> result = chunks.request(0, 0);
        assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
        chunks.close();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        backend.futures.get(0).complete("late native chunk");
        assertTrue(result.isCancelled());
        assertEquals(1, backend.releases);
        assertEquals(0, chunks.diagnostics().get("activeTickets"));
        try { chunks.request(1, 0); fail("Closed dispatcher was reused"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closed")); }
    }

    private static final class Backend implements AsyncWorldgenChunks.Backend<String> {
        final Thread owner = Thread.currentThread();
        final CountDownLatch entered;
        final Map<Integer, CompletableFuture<String>> futures = new ConcurrentHashMap<>();
        int retains, releases;
        Backend(int count) { entered = new CountDownLatch(count); }
        @Override public void retain(int x, int z) { assertSame(owner, Thread.currentThread()); retains++; }
        @Override public CompletableFuture<String> request(int x, int z) {
            assertNotSame(owner, Thread.currentThread());
            CompletableFuture<String> result = new CompletableFuture<>();
            futures.put(x, result);
            entered.countDown();
            return result;
        }
        @Override public void release(int x, int z) { assertSame(owner, Thread.currentThread()); releases++; }
    }

    private static final class InlineExecutor extends AbstractExecutorService {
        boolean stopped;
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
