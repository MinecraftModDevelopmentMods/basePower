package zone.moddev.mc.basepower.client;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Configure the translucent render layer for the registered oils. */
@OnlyIn(Dist.CLIENT)
public final class ClientOilRenderer {
    private ClientOilRenderer() {}

    public static void configureRenderLayers() {
        for (net.minecraft.world.level.material.Fluid fluid : new net.minecraft.world.level.material.Fluid[] {
                zone.moddev.mc.basepower.init.Fluids.crude_oil,
                zone.moddev.mc.basepower.init.Fluids.flowing_crude_oil,
                zone.moddev.mc.basepower.init.Fluids.refined_oil,
                zone.moddev.mc.basepower.init.Fluids.flowing_refined_oil}) {
            ItemBlockRenderTypes.setRenderLayer(fluid, ChunkSectionLayer.TRANSLUCENT);
        }
    }

}
