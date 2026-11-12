package zone.moddev.mc.basepower.client;

import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.simple.SimpleMachineGUI.GUIContainer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import zone.moddev.mc.basepower.util.ForgeFluidAdapter;

/** Keeps native client-only types out of common machine GUI definitions. */
public final class FluidGaugeRenderer {
    private FluidGaugeRenderer() {}

    public static void draw(GUIContainer gui, FluidStack stack, int x, int y, int width, int height) {
        if (stack == null || stack.getFluid() == null || width <= 0 || height <= 0) return;
        var fluid = ForgeFluidAdapter.toForge(stack);
        var rendering = IClientFluidTypeExtensions.of(stack.getFluid());
        var still = rendering.getStillTexture(fluid);
        if (still == null) return;
        var minecraft = gui.getMinecraft();
        var atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        int color = rendering.getTintColor(fluid);
        if (stack.getFluid().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
            color = minecraft.level != null && minecraft.player != null
                    ? BiomeColors.getAverageWaterColor(minecraft.level, minecraft.player.blockPosition()) : 0x3F76E4;
        }
        extractFluidTiles(gui.getGraphics(), atlas.getSprite(still), x, y, width, height, color);
    }

    public static void extractFluidTiles(GuiGraphicsExtractor graphics, TextureAtlasSprite sprite,
            int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        // Legacy gauges use the RGB tint and texture alpha, not the tint's alpha.
        color = 0xFF000000 | (color & 0xFFFFFF);
        // Clip full-size native tiles so partial rows do not stretch the texture.
        graphics.enableScissor(x, y, x + width, y + height);
        try {
            for (int dy = 0; dy < height; dy += 16) {
                for (int dx = 0; dx < width; dx += 16) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite,
                            x + dx, y + dy, 16, 16, color);
                }
            }
        } finally {
            graphics.disableScissor();
        }
    }
}
