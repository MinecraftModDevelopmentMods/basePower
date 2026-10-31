package zone.moddev.mc.advantageworks;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

final class Native117WorldgenAccess implements WorldgenSamplingJob.WorldAccess {
    private static final TicketType<ChunkPos> TICKET = TicketType.create(
            "advantageworks_worldgen_sample", Comparator.comparingLong(ChunkPos::toLong));
    private final ServerLevel world;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final AsyncWorldgenChunks<LevelChunk> chunks;

    Native117WorldgenAccess(ServerLevel world) {
        this.world = world;
        ServerChunkCache source = world.getChunkSource();
        chunks = new AsyncWorldgenChunks<>(new AsyncWorldgenChunks.Backend<LevelChunk>() {
            @Override public void retain(int x, int z) {
                ChunkPos position = new ChunkPos(x, z);
                source.addRegionTicket(TICKET, position, 0, position);
            }

            @Override public CompletableFuture<LevelChunk> request(int x, int z) {
                // Forge 37's public API managedBlocks on the server-thread branch, but not here.
                return source.getChunkFuture(x, z, ChunkStatus.FULL, true).thenApply(result -> {
                    net.minecraft.world.level.chunk.ChunkAccess access = result.left().orElseThrow(() ->
                            new IllegalStateException("Native FULL chunk unavailable: " + result.right()));
                    if (!(access instanceof LevelChunk) || access.getStatus() != ChunkStatus.FULL
                            || !access.isLightCorrect() || !access.getPos().equals(new ChunkPos(x, z))) {
                        throw new IllegalStateException("Native chunk was not FULL, lit, and at the requested position");
                    }
                    return (LevelChunk) access;
                });
            }

            @Override public void release(int x, int z) {
                ChunkPos position = new ChunkPos(x, z);
                source.removeRegionTicket(TICKET, position, 0, position);
            }
        }, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "advantageworks-worldgen-dispatch");
            thread.setDaemon(true);
            return thread;
        }));
    }

    @Override public boolean matchesBiome(String expected, int x, int y, int z) {
        return WorksController.matchesExpectedBiome(world, expected, world.getBiome(cursor.set(x, y, z)));
    }

    @Override public CompletableFuture<WorldgenSamplingJob.SampleChunk> requestChunk(int x, int z, boolean counting) {
        return chunks.request(x, z).thenApply(chunk -> new WorldgenSamplingJob.SampleChunk() {
            private Biome center;

            @Override public boolean centerMatches(String expected) {
                if (center == null) center = world.getBiome(new BlockPos((x << 4) + 8, world.getSeaLevel(), (z << 4) + 8));
                return WorksController.matchesExpectedBiome(world, expected, center);
            }

            @Override public String blockName(int blockX, int y, int blockZ) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(
                        chunk.getBlockState(cursor.set(blockX, y, blockZ)).getBlock());
                return id == null ? null : id.toString();
            }
        });
    }

    @Override public void releaseChunk(int x, int z) { chunks.release(x, z); }
    @Override public void close() { chunks.close(); }
    @Override public Map<String, Object> diagnostics() { return chunks.diagnostics(); }
}
