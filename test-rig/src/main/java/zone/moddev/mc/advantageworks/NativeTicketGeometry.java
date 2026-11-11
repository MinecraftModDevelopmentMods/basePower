package zone.moddev.mc.advantageworks;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/** Read-only engine observations; never adds tickets or loads witness chunks. */
final class NativeTicketGeometry {
    private NativeTicketGeometry() {}

    static Map<String, Object> observe(ServerLevel world) {
        try {
            int fullLevel = -1, entityTickingLevel = -1;
            for (int level = 0; level <= ChunkLevel.MAX_LEVEL; level++) {
                if (ChunkLevel.generationStatus(level) == ChunkStatus.FULL) fullLevel = level;
                if (ChunkLevel.fullStatus(level) == FullChunkStatus.ENTITY_TICKING)
                    entityTickingLevel = level;
            }
            ServerChunkCache provider = world.getChunkSource();
            DistanceManager manager = ObfuscationReflectionHelper.getPrivateValue(
                    ServerChunkCache.class, provider, "f_8327_");
            // Forge 36 adds a second map, forcedTickets; observe the vanilla tickets map only.
            Long2ObjectOpenHashMap<?> tickets = ObfuscationReflectionHelper.getPrivateValue(
                    DistanceManager.class, manager, "f_140761_");
            Set<Integer> forcedLevels = new TreeSet<>();
            for (Object value : tickets.values()) {
                for (Object entry : (Iterable<?>) value) {
                    Ticket<?> ticket = (Ticket<?>) entry;
                    if (ticket.getType() == TicketType.FORCED) forcedLevels.add(ticket.getTicketLevel());
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fullLevel", fullLevel);
            result.put("entityTickingLevel", entityTickingLevel);
            result.put("maxDistance", ChunkLevel.RADIUS_AROUND_FULL_CHUNK);
            result.put("lastHolderLevel", ChunkLevel.MAX_LEVEL);
            result.put("holderRadius", ChunkLevel.MAX_LEVEL - entityTickingLevel);
            result.put("observedForcedLevels", new ArrayList<>(forcedLevels));
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot observe native chunk ticket geometry", error);
        }
    }
}
