package zone.moddev.mc.poweradvantage.init;

import cyano.poweradvantage.api.fluid.FluidStack;
import net.minecraft.nbt.CompoundTag;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class FluidLookupTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void resolvesLegacyUnqualifiedVanillaNamesInsteadOfTheEmptyDefault() {
        assertSame(net.minecraft.world.level.material.Fluids.WATER, Fluids.byName("water"));
        assertSame(net.minecraft.world.level.material.Fluids.LAVA, Fluids.byName("lava"));
        assertSame(net.minecraft.world.level.material.Fluids.WATER, Fluids.byName("minecraft:water"));
    }

    @Test public void unknownNamesAreNotReplacedByAnEmptyFluid() {
        assertNull(Fluids.byName("not_registered"));
        assertNull(Fluids.byName("missingmod:not_registered"));
        assertNull(Fluids.byName(null));
    }

    @Test public void retainsAmountAndNbtWhenReadingLegacySavedWater() {
        CompoundTag data = new CompoundTag();
        data.putString("FluidName", "water");
        data.putInt("Amount", 700);
        CompoundTag tag = new CompoundTag();
        tag.putInt("batch", 37);
        data.put("Tag", tag);
        FluidStack stack = FluidStack.loadFluidStackFromNBT(data);
        assertNotNull(stack);
        assertSame(net.minecraft.world.level.material.Fluids.WATER, stack.getFluid());
        assertEquals(700, stack.amount);
        assertEquals(tag, stack.tag);
    }
}
