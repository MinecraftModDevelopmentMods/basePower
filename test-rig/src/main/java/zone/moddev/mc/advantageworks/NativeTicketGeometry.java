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
            net.minecraft.world.level.TicketStorage tickets = world.getDataStorage().get(
                    net.minecraft.world.level.TicketStorage.TYPE);
            if (tickets == null) throw new IllegalStateException("Native chunk ticket storage is missing");
            Set<Integer> forcedLevels = new TreeSet<>();
            for (long chunk : tickets.getForceLoadedChunks()) {
                for (Ticket ticket : tickets.getTickets(chunk)) {
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
