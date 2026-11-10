package zone.moddev.mc.basepower.gui;

import cyano.poweradvantage.api.fluid.FluidPoweredEntity;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import zone.moddev.mc.basepower.math.Integer2D;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import cyano.poweradvantage.api.fluid.FluidStack;


public class FluidTankGUI extends SimpleMachineGUI {



	public FluidTankGUI(ResourceLocation image) {
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
			ResourceLocation displayImage, GUIContainer guiContainer, int x, int y, float zLevel
	) {
		barHeight = Float.isFinite(barHeight) ? Math.max(0F, Math.min(1F, barHeight)) : 0F;
		final int w = 16;
		final int barSlotHeight = 60;
		final int h = (int) (barSlotHeight * barHeight);

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
		TextureAtlasSprite sprite = guiContainer.getMinecraft().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
				.apply(stillTexture);
		int color = rendering.getTintColor(fluidStack);
		if (fs.getFluid().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
			color = guiContainer.getMinecraft().level != null && guiContainer.getMinecraft().player != null
					? BiomeColors.getAverageWaterColor(guiContainer.getMinecraft().level, guiContainer.getMinecraft().player.blockPosition()) : 0x3F76E4;
		}
		float red = ((color >>> 16) & 255) / 255F;
		float green = ((color >>> 8) & 255) / 255F;
		float blue = (color & 255) / 255F;
		guiContainer.getGraphics().flush();
		var pose = guiContainer.getMatrices().last().pose();
		RenderSystem.setShaderTexture(0, sprite.atlasLocation());
		Tesselator tessellator = Tesselator.getInstance();
		BufferBuilder vertexbuffer = tessellator.getBuilder();
		RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
		vertexbuffer.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		final int tileSize = 16;
		for (int dy = 0; dy < height; dy += tileSize) {
			int tileHeight = Math.min(tileSize, height - dy);
			for (int dx = 0; dx < width; dx += tileSize) {
				int tileWidth = Math.min(tileSize, width - dx);
				// Sprite coordinates are fractions of the tile, even when drawing only part of it.
				float u0 = sprite.getU0(), u1 = sprite.getU((float) tileWidth / tileSize);
				float v0 = sprite.getV0(), v1 = sprite.getV((float) tileHeight / tileSize);
				vertexbuffer.vertex(pose, x + dx, y + dy + tileHeight, zLevel).uv(u0, v1).color(red, green, blue, 1F).endVertex();
				vertexbuffer.vertex(pose, x + dx + tileWidth, y + dy + tileHeight, zLevel).uv(u1, v1).color(red, green, blue, 1F).endVertex();
				vertexbuffer.vertex(pose, x + dx + tileWidth, y + dy, zLevel).uv(u1, v0).color(red, green, blue, 1F).endVertex();
				vertexbuffer.vertex(pose, x + dx, y + dy, zLevel).uv(u0, v0).color(red, green, blue, 1F).endVertex();
			}
		}
		tessellator.end();
	}

}
