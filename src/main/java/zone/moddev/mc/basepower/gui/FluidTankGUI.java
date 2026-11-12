package zone.moddev.mc.basepower.gui;

import cyano.poweradvantage.api.fluid.FluidPoweredEntity;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import zone.moddev.mc.basepower.math.Integer2D;
import net.minecraft.resources.Identifier;
import cyano.poweradvantage.api.fluid.FluidStack;


public class FluidTankGUI extends SimpleMachineGUI {



	public FluidTankGUI(Identifier image) {
		super(image,
				new Integer2D[0]);
	}

	@Override
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


	public static void drawFluidFilledRectangle(
			GUIContainer guiContainer, FluidStack fs,
			int x, int y, int width, int height,
			float zLevel
	) {
        zone.moddev.mc.basepower.client.FluidGaugeRenderer.draw(guiContainer, fs, x, y, width, height);
    }

    static int gaugeHeight(float level) {
        return (int) (60 * (Float.isFinite(level) ? Math.max(0F, Math.min(1F, level)) : 0F));
    }

}
