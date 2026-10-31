package zone.moddev.mc.advantageworks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Keeps native request dispatch off the owner thread and tickets bounded until consumption. */
final class AsyncWorldgenChunks<T> implements AutoCloseable {
    interface Backend<T> {
        void retain(int x, int z);
        CompletableFuture<T> request(int x, int z);
        void release(int x, int z);
    }

    private final Backend<T> backend;
    private final ExecutorService executor;
    private final Thread owner = Thread.currentThread();
    private final Map<Long, CompletableFuture<T>> active = new LinkedHashMap<>();
    private volatile boolean closed;
    private int peak, requested, released;

    AsyncWorldgenChunks(Backend<T> backend, ExecutorService executor) {
        this.backend = Objects.requireNonNull(backend);
        this.executor = Objects.requireNonNull(executor);
    }

    CompletableFuture<T> request(int x, int z) {
        requireOwner();
        if (closed) throw new IllegalStateException("Worldgen chunk dispatcher is closed");
        long key = pack(x, z);
        if (active.containsKey(key) || active.size() >= WorldgenSamplingJob.MAX_IN_FLIGHT) {
            throw new IllegalStateException("Duplicate or excess native worldgen chunk request");
        }
        backend.retain(x, z);
        try {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                if (Thread.currentThread() == owner) {
                    throw new IllegalStateException("Native FULL future must be requested off the server thread");
                }
                if (closed) throw new IllegalStateException("Worldgen chunk dispatcher stopped before dispatch");
                return Objects.requireNonNull(backend.request(x, z), "Native chunk future was null");
            }, executor).thenCompose(value -> value).thenApply(value ->
                    Objects.requireNonNull(value, "Native FULL chunk was lost"));
            active.put(key, future);
            requested++;
            peak = Math.max(peak, active.size());
            return future;
        } catch (RuntimeException failure) {
            backend.release(x, z);
            throw failure;
        }
    }

    void release(int x, int z) {
        requireOwner();
        CompletableFuture<T> future = active.remove(pack(x, z));
        if (future == null) throw new IllegalStateException("Native worldgen ticket was not held");
        try { future.cancel(false); }
        finally { backend.release(x, z); released++; }
    }

    @Override public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            for (long key : new ArrayList<>(active.keySet())) {
                try { release((int) (key >> 32), (int) key); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
        } finally { executor.shutdownNow(); }
        if (failure != null) throw failure;
    }

    Map<String, Object> diagnostics() {
        requireOwner();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", "native_FULL_off_thread_future_poll_v1");
        result.put("maximumInFlight", WorldgenSamplingJob.MAX_IN_FLIGHT);
        result.put("peakInFlight", peak);
        result.put("requests", requested);
        result.put("releasedTickets", released);
        result.put("activeTickets", active.size());
        result.put("dispatcherClosed", closed);
        result.put("executorShutdown", executor.isShutdown());
        return result;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Worldgen ticket access off owner thread");
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
