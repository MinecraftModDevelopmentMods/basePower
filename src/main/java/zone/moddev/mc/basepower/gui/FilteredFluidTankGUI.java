package zone.moddev.mc.basepower.gui;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import cyano.poweradvantage.api.fluid.FluidPoweredEntity;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import zone.moddev.mc.basepower.math.Integer2D;
import net.minecraft.resources.ResourceLocation;
import cyano.poweradvantage.api.fluid.FluidStack;

public class FilteredFluidTankGUI extends SimpleMachineGUI {


	public FilteredFluidTankGUI(ResourceLocation image) {
		super(image,
				Integer2D.fromCoordinates(31, 31));
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
		FluidTankGUI.drawFluidBar(fs, barHeight, xPos, yPos, displayImage, guiContainer, x, y, zLevel);
	}
}
