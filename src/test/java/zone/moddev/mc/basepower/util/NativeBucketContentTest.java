package zone.moddev.mc.basepower.util;

import org.junit.Test;
import static org.junit.Assert.*;
import zone.moddev.mc.basepower.init.Fluids;
import zone.moddev.mc.basepower.init.Items;

public class NativeBucketContentTest {
    @Test public void oilBucketsResolveToTheSourceEvenWhenFlowingWasRegisteredFirst() {
        assertNotSame(Fluids.crude_oil, Fluids.flowing_crude_oil);
        assertNotSame(Fluids.refined_oil, Fluids.flowing_refined_oil);
        assertSame(Fluids.crude_oil, FluidContainerHelper.fluidForBucket(Items.crude_oil_bucket));
        assertSame(Fluids.refined_oil, FluidContainerHelper.fluidForBucket(Items.refined_oil_bucket));
    }

    @Test public void vanillaWaterLavaAndEmptyBucketsKeepTheirNativeIdentity() {
        assertSame(net.minecraft.world.level.material.Fluids.WATER,
                FluidContainerHelper.fluidForBucket(net.minecraft.world.item.Items.WATER_BUCKET));
        assertSame(net.minecraft.world.level.material.Fluids.LAVA,
                FluidContainerHelper.fluidForBucket(net.minecraft.world.item.Items.LAVA_BUCKET));
        assertNull(FluidContainerHelper.fluidForBucket(net.minecraft.world.item.Items.BUCKET));
        assertNull(FluidContainerHelper.fluidForBucket(net.minecraft.world.item.Items.DIRT));
    }
}
