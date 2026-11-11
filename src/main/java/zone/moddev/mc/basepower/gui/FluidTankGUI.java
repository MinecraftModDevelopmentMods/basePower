package zone.moddev.mc.basepower.gui;

import cyano.poweradvantage.api.fluid.FluidPoweredEntity;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import zone.moddev.mc.basepower.math.Integer2D;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.BiomeColors;
import org.joml.Matrix3x2f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import cyano.poweradvantage.api.fluid.FluidStack;


public class FluidTankGUI extends SimpleMachineGUI {



	public FluidTankGUI(Identifier image) {
		super(image,
				new Integer2D[0]);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void drawGUIDecorations(Object srcEntity, GUIContainer guiContainer, int x, int y, float z) {
		// draw indicator
		if (srcEntity instanceof FluidPoweredEntity) {
			FluidStack fs = ((FluidPoweredEntity) srcEntity).getTank().getFluid();
			if (fs != null && ((FluidPoweredEntity) srcEntity).getTank().getFluidAmount() > 0) {
				drawFluidBar(fs,
						(float) fs.amount / (float) ((FluidPoweredEntity) srcEntity).getTank().getCapacity(),
						80, 9,
						guiDisplayImage, guiContainer, x, y, z);
			}
		}
		guiContainer.blit(x + 77, y + 6, 176, 0, 22, 66); // x, y, textureOffsetX, textureOffsetY, width, height)
	}


	@OnlyIn(Dist.CLIENT)
	public static void drawFluidBar(
			FluidStack fs, float barHeight, int xPos, int yPos,
			Identifier displayImage, GUIContainer guiContainer, int x, int y, float zLevel
	) {
		final int w = 16;
		final int barSlotHeight = 60;
		final int h = gaugeHeight(barHeight);

		FluidTankGUI.drawFluidFilledRectangle(guiContainer, fs, x + xPos, y + yPos + barSlotHeight - h, w, h, zLevel);
		guiContainer.blit(displayImage, x + xPos - 3, y + yPos - 3, 176, 0, 22, 66);
	}


	@OnlyIn(Dist.CLIENT)
	public static void drawFluidFilledRectangle(
			GUIContainer guiContainer, FluidStack fs,
			int x, int y, int width, int height,
			float zLevel
	) {
		if (fs == null || fs.getFluid() == null || width <= 0 || height <= 0) return;
		// The atlas supplies the current animation frame; water's sprite is grayscale.
		var fluidStack = zone.moddev.mc.basepower.util.ForgeFluidAdapter.toForge(fs);
		var rendering = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fs.getFluid());
		var stillTexture = rendering.getStillTexture(fluidStack);
		if (stillTexture == null) return;
        var atlas = guiContainer.getMinecraft().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite sprite = atlas.getSprite(stillTexture);
		int color = rendering.getTintColor(fluidStack);
		if (fs.getFluid().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
			color = guiContainer.getMinecraft().level != null && guiContainer.getMinecraft().player != null
					? BiomeColors.getAverageWaterColor(guiContainer.getMinecraft().level, guiContainer.getMinecraft().player.blockPosition()) : 0x3F76E4;
		}
        var graphics = guiContainer.getGraphics();
        var texture = TextureSetup.singleTexture(atlas.getTextureView(), atlas.getSampler());
        extractFluidTiles(graphics.getRenderState(), texture, new Matrix3x2f(graphics.pose()),
                graphics.getScissorStack().peek(), x, y, width, height,
                sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), color);
    }

    static int gaugeHeight(float level) {
        return (int) (60 * (Float.isFinite(level) ? Math.max(0F, Math.min(1F, level)) : 0F));
    }

    @OnlyIn(Dist.CLIENT)
    static void extractFluidTiles(GuiRenderState target, TextureSetup texture, Matrix3x2f pose,
            ScreenRectangle scissor, int x, int y, int width, int height,
            float spriteU0, float spriteU1, float spriteV0, float spriteV1, int color) {
        if (width <= 0 || height <= 0) return;
        // Legacy gauges use the fluid's RGB tint and texture alpha, not its tint alpha.
        color = 0xFF000000 | (color & 0xFFFFFF);
		final int tileSize = 16;
		for (int dy = 0; dy < height; dy += tileSize) {
			int tileHeight = Math.min(tileSize, height - dy);
			for (int dx = 0; dx < width; dx += tileSize) {
				int tileWidth = Math.min(tileSize, width - dx);
				// Sprite coordinates are fractions of the tile, even when drawing only part of it.
                float u1 = spriteU0 + (spriteU1 - spriteU0) * tileWidth / tileSize;
                float v1 = spriteV0 + (spriteV1 - spriteV0) * tileHeight / tileSize;
                target.submitGuiElement(new BlitRenderState(
                        RenderPipelines.GUI_TEXTURED, texture, new Matrix3x2f(pose),
                        x + dx, y + dy, x + dx + tileWidth, y + dy + tileHeight,
                        spriteU0, u1, spriteV0, v1, color, scissor));
			}
		}
	}

}
