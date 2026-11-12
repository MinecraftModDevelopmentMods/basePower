package zone.moddev.mc.basepower.client;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Client-only oil models, resolved against the current animated block atlas. */
public final class ClientOilRenderer {
    private ClientOilRenderer() {}

    public static IClientFluidTypeExtensions extensions(String name) {
        return new OilModel(name);
    }

    private static final class OilModel implements IClientFluidTypeExtensions {
        private final Identifier still;
        private final Identifier flowing;
        private volatile FluidModel cached;

        private OilModel(String name) {
            still = Identifier.fromNamespaceAndPath(PowerAdvantage.MODID, "blocks/" + name + "_still");
            flowing = Identifier.fromNamespaceAndPath(PowerAdvantage.MODID, "blocks/" + name + "_flow");
        }

        @Override public Identifier getStillTexture() { return still; }
        @Override public Identifier getFlowingTexture() { return flowing; }

        @Override
        public FluidModel getModel(FluidState state, BlockAndTintGetter level, BlockPos pos, FluidModel vanilla) {
            var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            TextureAtlasSprite stillSprite = atlas.getSprite(still);
            TextureAtlasSprite flowingSprite = atlas.getSprite(flowing);
            FluidModel model = cached;
            // A resource reload replaces sprites, so never retain a model from the old atlas.
            if (model == null || model.stillMaterial().sprite() != stillSprite
                    || model.flowingMaterial().sprite() != flowingSprite) {
                model = new FluidModel(ChunkSectionLayer.TRANSLUCENT,
                        new Material.Baked(stillSprite, true), new Material.Baked(flowingSprite, true), null, null);
                cached = model;
            }
            return model;
        }
    }

}
