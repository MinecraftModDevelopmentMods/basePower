package zone.moddev.mc.basepower.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native extraction state geometry, not texture upload or screenshot qualification. */
public class FluidTankGuiNativeTest {
    @Test public void displayHeightRetainsBottomAnchoringAndFiniteClamping() throws Exception {
        assertEquals(0, FluidTankGUI.gaugeHeight(Float.NaN));
        assertEquals(0, FluidTankGUI.gaugeHeight(Float.POSITIVE_INFINITY));
        assertEquals(0, FluidTankGUI.gaugeHeight(-1));
        assertEquals(0, FluidTankGUI.gaugeHeight(0));
        assertEquals(30, FluidTankGUI.gaugeHeight(0.5F));
        assertEquals(60, FluidTankGUI.gaugeHeight(1));
        assertEquals(60, FluidTankGUI.gaugeHeight(2));
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/zone/moddev/mc/basepower/gui/FluidTankGUI.java"));
        assertTrue(source.contains("x + xPos, y + yPos + barSlotHeight - h, w, h, zLevel"));
        assertTrue(source.contains("guiContainer.blit(displayImage, x + xPos - 3, y + yPos - 3, 176, 0, 22, 66)"));
    }

    @Test public void fullGaugeQueuesFourNativeQuadsAndCropsTheLastTileUv() {
        var states = extract(new Matrix3x2f(), null, 80, 9, 16, 60, 0x22123456);
        assertEquals(4, states.size());
        for (int row = 0; row < 4; row++) {
            var tile = at(states, 80, 9 + row * 16);
            assertEquals(96, tile.x1());
            assertEquals(9 + Math.min(60, (row + 1) * 16), tile.y1());
            assertEquals(0.25F, tile.u0(), 0F);
            assertEquals(0.5F, tile.u1(), 0F);
            assertEquals(0.5F, tile.v0(), 0F);
            assertEquals(row == 3 ? 0.6875F : 0.75F, tile.v1(), 0F);
            assertSame(RenderPipelines.GUI_TEXTURED, tile.pipeline());
            assertEquals(0xFF123456, tile.color());
        }
    }

    @Test public void widePartialRectanglePreservesTileGeometryAndBothUvFractions() {
        var states = extract(new Matrix3x2f(), null, 3, 7, 19, 17, 0x3F76E4);
        assertEquals(4, states.size());
        var corner = at(states, 19, 23);
        assertEquals(22, corner.x1());
        assertEquals(24, corner.y1());
        assertEquals(0.25F + 0.25F * 3 / 16, corner.u1(), 0F);
        assertEquals(0.5F + 0.25F / 16, corner.v1(), 0F);
        assertEquals(0xFF3F76E4, corner.color());
    }

    @Test public void extractionSnapshotsTransformsAndUsesNativeScissorBounds() {
        var pose = new Matrix3x2f().translation(10, 20);
        var scissor = new ScreenRectangle(11, 22, 5, 6);
        var states = extract(pose, scissor, 0, 0, 16, 16, 0xFFFFFFFF);
        assertEquals(1, states.size());
        var state = states.getFirst();
        pose.translate(100, 100);
        assertEquals(new Matrix3x2f().translation(10, 20), state.pose());
        assertEquals(scissor, state.scissorArea());
        assertEquals(scissor, state.bounds());
    }

    @Test public void emptyAndInvalidRectanglesSubmitNothing() {
        assertTrue(extract(new Matrix3x2f(), null, 0, 0, 0, 60, -1).isEmpty());
        assertTrue(extract(new Matrix3x2f(), null, 0, 0, 16, 0, -1).isEmpty());
        assertTrue(extract(new Matrix3x2f(), null, 0, 0, -1, 60, -1).isEmpty());
        assertTrue(extract(new Matrix3x2f(), null, 0, 0, 16, -1, -1).isEmpty());
    }

    private static List<BlitRenderState> extract(Matrix3x2f pose, ScreenRectangle scissor,
            int x, int y, int width, int height, int color) {
        var target = new GuiRenderState();
        FluidTankGUI.extractFluidTiles(target, TextureSetup.noTexture(), pose, scissor,
                x, y, width, height, 0.25F, 0.5F, 0.5F, 0.75F, color);
        var states = new ArrayList<BlitRenderState>();
        target.forEachElement(state -> states.add((BlitRenderState) state), GuiRenderState.TraverseRange.ALL);
        return states;
    }

    private static BlitRenderState at(List<BlitRenderState> states, int x, int y) {
        return states.stream().filter(state -> state.x0() == x && state.y0() == y).findFirst().orElseThrow();
    }
}
