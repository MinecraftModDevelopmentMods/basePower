package cyano.poweradvantage.api.simple;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.GuiTextRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2f;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basepower.math.Integer2D;
import static org.junit.Assert.*;

/** Native extraction/layout contracts, not GPU glyph or whole-panel screenshot proof. */
public class SimpleMachineGuiNativeTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void menuRetainsMachineRowsPlayerGridAndHotbarCoordinates() {
        var points = Integer2D.fromCoordinates(12, 34, 12, 55, 124, 45);
        var gui = new SimpleMachineGUI(Identifier.parse("basepower:textures/gui/container/fluid_tank_gui.png"), points);
        var inventory = new Inventory(null, new EntityEquipment());
        var machine = new SimpleContainer(points.length);
        var menu = gui.createMenu(17, inventory, machine);
        assertEquals(176, gui.guiWidth);
        assertEquals(240, gui.guiHeight);
        assertEquals(17, menu.containerId);
        assertEquals(points.length + 36, menu.slots.size());
        for (int i = 0; i < points.length; i++) {
            var slot = menu.slots.get(i);
            assertSame(machine, slot.container);
            assertEquals(points[i].X, slot.x);
            assertEquals(points[i].Y + 12, slot.y);
        }
        for (int i = 0; i < 27; i++) {
            var slot = menu.slots.get(points.length + i);
            assertSame(inventory, slot.container);
            assertEquals(8 + (i % 9) * 18, slot.x);
            assertEquals(158 + (i / 9) * 18, slot.y);
        }
        for (int i = 0; i < 9; i++) {
            var slot = menu.slots.get(points.length + 27 + i);
            assertEquals(8 + i * 18, slot.x);
            assertEquals(216, slot.y);
        }
    }

    @Test public void nativeMatrixBridgeKeepsNeedlePivotRotationAndStackBalance() throws Exception {
        var fixture = screen("Machine", "Inventory", 854);
        assertSame(fixture.graphics, fixture.screen.getGraphics());
        assertSame(fixture.pose, fixture.screen.getMatrices());
        assertEquals(Matrix3x2fStack.class, SimpleMachineGUI.GUIContainer.class.getMethod("getMatrices").getReturnType());
        assertEquals(GuiGraphics.class, SimpleMachineGUI.GUIContainer.class.getMethod("getGraphics").getReturnType());
        for (float value : new float[] {0, 0.5F, 1}) {
            var pose = fixture.screen.getMatrices();
            var before = new Matrix3x2f(pose);
            pose.pushMatrix();
            try {
                pose.translate(80, 55).rotate((value - 0.5F) * (float) Math.PI).translate(-3.5F, -17.5F);
                var center = pose.transformPosition(3.5F, 17.5F, new Vector2f());
                assertEquals(80F, center.x, 0.0001F);
                assertEquals(55F, center.y, 0.0001F);
                var tip = pose.transformPosition(3.5F, 0, new Vector2f());
                assertEquals(value == 0 ? 62.5F : value == 1 ? 97.5F : 80F, tip.x, 0.0001F);
                assertEquals(value == 0.5F ? 37.5F : 55F, tip.y, 0.0001F);
            } finally {
                pose.popMatrix();
            }
            assertEquals(before, new Matrix3x2f(pose));
        }
    }

    @Test public void captionsReachNativeTextExtractionWithOpaqueWhiteAndSeparateRows() throws Exception {
        var fixture = screen("Machine", "Inventory", 854);
        fixture.pose.translate(13, 29);
        fixture.screen.renderLabels(fixture.graphics, 0, 0);
        assertEquals(2, fixture.target.texts.size());
        var title = fixture.target.texts.get(0);
        var inventory = fixture.target.texts.get(1);
        assertEquals("Machine", text(title));
        assertEquals("Inventory", text(inventory));
        assertEquals(67, title.x);
        assertEquals(1, title.y);
        assertEquals(8, inventory.x);
        assertEquals(140, inventory.y);
        for (var caption : fixture.target.texts) {
            assertEquals(0xFFFFFFFF, caption.color);
            assertTrue(caption.dropShadow);
            assertSame(fixture.font, caption.font);
            assertEquals(new Matrix3x2f(fixture.pose), new Matrix3x2f(caption.pose));
        }
        fixture.pose.translate(50, 50);
        assertEquals(13F, title.pose.m20(), 0F);
        assertEquals(29F, title.pose.m21(), 0F);
    }

    @Test public void longCaptionsKeepTheirAcceptedWidthLimitsAndEllipsis() throws Exception {
        var fixture = screen("M".repeat(100), "I".repeat(100), 200);
        fixture.screen.renderLabels(fixture.graphics, 0, 0);
        assertEquals(2, fixture.target.texts.size());
        assertEquals("M".repeat(29) + "...", text(fixture.target.texts.get(0)));
        assertEquals("I".repeat(23) + "...", text(fixture.target.texts.get(1)));
        assertEquals(-8, fixture.target.texts.get(0).x);
        assertEquals(8, fixture.target.texts.get(1).x);
    }

    @Test public void backgroundAndTooltipUseNativeExtractionWithoutGlobalRenderState() throws Exception {
        String source = Files.readString(Path.of("src/main/java/cyano/poweradvantage/api/simple/SimpleMachineGUI.java"));
        assertTrue(source.contains("graphics.setTooltipForNextFrame(font, title, mouseX, mouseY)"));
        assertTrue(source.contains("graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, 256, 256)"));
        assertTrue(source.contains("blit(playerInventoryBox, x, y + PLAYER_PANEL_TOP, 0, 132, playerInventoryWidth, playerInventoryHeight)"));
        assertTrue(source.contains("blit(x, y + MACHINE_TOP, 0, 0, guiWidth, MACHINE_HEIGHT)"));
        assertTrue(source.contains("drawGUIDecorations(entity, this, x, y + MACHINE_TOP, getZLevel())"));
        assertTrue(source.contains("this.graphics = null"));
        assertFalse(source.contains("RenderSystem"));
        assertFalse(source.contains("graphics.renderTooltip(font, title"));
        SimpleMachineGUI.GUIContainer.class.getMethod("blit", int.class, int.class, int.class, int.class, int.class, int.class);
        SimpleMachineGUI.GUIContainer.class.getMethod("blit", Identifier.class, int.class, int.class, int.class, int.class, int.class, int.class);
    }

    private static Fixture screen(String title, String inventory, int width) throws Exception {
        var target = new TextQueue();
        var pose = new Matrix3x2fStack(16);
        var graphics = allocate(GuiGraphics.class);
        set(graphics, "pose", pose);
        set(graphics, "guiRenderState", target);
        set(graphics, "scissorStack", new GuiGraphics.ScissorStack());
        var screen = allocate(SimpleMachineGUI.GUIContainer.class);
        var font = new MetricsFont();
        set(screen, "graphics", graphics);
        set(screen, "playerInventoryLabel", Component.literal(inventory));
        set(screen, "title", Component.literal(title));
        set(screen, "font", font);
        set(screen, "width", width);
        set(screen, "imageWidth", 176);
        return new Fixture(screen, graphics, target, pose, font);
    }

    private record Fixture(SimpleMachineGUI.GUIContainer screen, GuiGraphics graphics,
            TextQueue target, Matrix3x2fStack pose, MetricsFont font) {}

    private static final class MetricsFont extends Font {
        MetricsFont() { super(null); }
        @Override public int width(String text) { return text.length() * 6; }
        @Override public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
        }
    }

    // Capture real native text states before GPU glyph preparation; no font assets are fabricated.
    private static final class TextQueue extends GuiRenderState {
        final List<GuiTextRenderState> texts = new ArrayList<>();
        @Override public void submitText(GuiTextRenderState text) { texts.add(text); }
    }

    private static String text(GuiTextRenderState state) {
        var text = new StringBuilder();
        state.text.accept((index, style, point) -> { text.appendCodePoint(point); return true; });
        return text.toString();
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field instance = allocator.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        return type.cast(allocator.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type));
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(instance, value);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
