package zone.moddev.mc.basepower.migration;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyFlatteningStateTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void flatteningUsesHistoricalKeysEvenOnTheCurrentNativeEngine() {
        CompoundTag state = LegacyWorldDataHook.legacyFlatteningState(Blocks.STONE.defaultBlockState());
        assertEquals(Set.of("Name"), state.keySet());
        assertEquals("minecraft:stone", state.getStringOr("Name", ""));
        assertEquals("Native saves use a different contract", Set.of("id"),
                NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()).keySet());
    }

    @Test public void allSixDirectionsAndOtherPropertiesSurviveTheHistoricalEncoding() {
        for (Direction direction : Direction.values()) {
            var source = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, direction)
                    .setValue(PistonBaseBlock.EXTENDED, true);
            CompoundTag historical = LegacyWorldDataHook.legacyFlatteningState(source);
            assertEquals(Set.of("Name", "Properties"), historical.keySet());
            assertEquals("minecraft:piston", historical.getStringOr("Name", ""));
            assertEquals(direction.getSerializedName(), historical.getCompoundOrEmpty("Properties").getStringOr("facing", ""));
            assertEquals("true", historical.getCompoundOrEmpty("Properties").getStringOr("extended", ""));
            assertEquals(NbtUtils.writeBlockState(source).getCompoundOrEmpty("properties"), historical.getCompoundOrEmpty("Properties"));
            historical.getCompoundOrEmpty("Properties").putString("facing", "invalid");
            assertEquals("Returned data must not alter the source state", direction, source.getValue(PistonBaseBlock.FACING));
        }
    }
}
