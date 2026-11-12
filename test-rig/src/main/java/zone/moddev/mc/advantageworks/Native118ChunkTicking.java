package zone.moddev.mc.advantageworks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

final class Native118ChunkTicking {
    private Native118ChunkTicking() {}

    static boolean withEntitiesLoaded(ServerLevel world, BlockPos pos) {
        // The native combined check became private in 1.18.
        long key = ChunkPos.pack(pos);
        return world.areEntitiesLoaded(key) && world.getChunkSource().isPositionTicking(key);
    }
}
