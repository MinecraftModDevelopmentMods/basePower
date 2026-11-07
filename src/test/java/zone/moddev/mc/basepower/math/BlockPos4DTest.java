package zone.moddev.mc.basepower.math;

import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlockPos4DTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void customDimensionKeysNeverShareCoordinates() {
        ResourceKey<Level> first = ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation("test:first"));
        ResourceKey<Level> second = ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation("test:second"));
        BlockPos4D pos = new BlockPos4D(first, new BlockPos(-17, 80, 32));
        assertNotEquals(pos, new BlockPos4D(second, pos.pos));
        assertEquals(pos, BlockPos4D.fromNBT(pos.toNBT()));
        assertFalse(pos.equals(null));
    }

    @Test public void cacheKeysCopyMutableInputPositions() {
        BlockPos.MutableBlockPos source = new BlockPos.MutableBlockPos(1, 2, 3);
        BlockPos4D key = new BlockPos4D(Level.OVERWORLD, source);
        source.set(4, 5, 6);
        assertEquals(new BlockPos(1, 2, 3), key.pos);
    }

    @Test public void legacyVanillaCoordinatesRetainTheirDimension() {
        assertEquals(new BlockPos4D(Level.NETHER, new BlockPos(1, 2, 3)),
                BlockPos4D.fromNBT(new IntArrayTag(new int[] {-1, 1, 2, 3})));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownNumericDimensionsAreNotInvented() { BlockPos4D.legacyDimension(7); }
}
