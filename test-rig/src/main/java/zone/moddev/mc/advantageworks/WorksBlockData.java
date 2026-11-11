package zone.moddev.mc.advantageworks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class WorksBlockData {
    private WorksBlockData() {}

    static CompoundTag save(BlockEntity tile) {
        return tile.saveWithFullMetadata(tile.getLevel().registryAccess());
    }

    static void load(BlockEntity tile, CompoundTag data) {
        var problems = new net.minecraft.util.ProblemReporter.Collector();
        tile.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                problems, tile.getLevel().registryAccess(), data));
        if (!problems.isEmpty()) throw new IllegalStateException("Native block NBT could not be read: " + problems.getReport());
    }

    static AABB bounds(BlockPos min, BlockPos maxExclusive) {
        return new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(maxExclusive));
    }
}
