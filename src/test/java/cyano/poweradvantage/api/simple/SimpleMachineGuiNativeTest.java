package cyano.poweradvantage.api.simple;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
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
import zone.moddev.mc.basepower.resources.NativeGuiExtractionFixture;
import static org.junit.Assert.*;
import static zone.moddev.mc.basepower.resources.NativeGuiExtractionFixture.*;

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
        assertEquals(GuiGraphicsExtractor.class, SimpleMachineGUI.GUIContainer.class.getMethod("getGraphics").getReturnType());
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
        fixture.screen.extractLabels(fixture.graphics, 0, 0);
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
        fixture.screen.extractLabels(fixture.graphics, 0, 0);
        assertEquals(2, fixture.target.texts.size());
        assertEquals("M".repeat(29) + "...", text(fixture.target.texts.get(0)));
        assertEquals("I".repeat(23) + "...", text(fixture.target.texts.get(1)));
        assertEquals(-8, fixture.target.texts.get(0).x);
        assertEquals(8, fixture.target.texts.get(1).x);
    }

    @Test public void nativeScreenWrapperExtractsBackgroundOnceAndReleasesDecorationContext() throws Exception {
        var fixture = screen("Machine", "Inventory", 854);
        set(fixture.screen, "graphics", null);
        int[] decorations = {0};
        fixture.gui.decoration = (screen, x, y) -> {
            decorations[0]++;
            assertSame(fixture.graphics, screen.getGraphics());
            assertSame(fixture.pose, screen.getMatrices());
            assertEquals(339, x);
            assertEquals(132, y);
        };
        fixture.screen.extractRenderStateWithTooltipAndSubtitles(fixture.graphics, -100, -100, 0F);
        assertEquals(1, fixture.screen.backgroundCalls);
        assertEquals(1, decorations[0]);
        assertNull(fixture.screen.getGraphics());
        var blits = new ArrayList<BlitRenderState>();
        fixture.target.forEachElement(state -> {
            if (state instanceof BlitRenderState blit) blits.add(blit);
        }, GuiRenderState.TraverseRange.ALL);
        assertEquals(2, blits.size());
        var panel = blits.stream().filter(state -> state.y0() == 270).findFirst().orElseThrow();
        var machine = blits.stream().filter(state -> state.y0() == 132).findFirst().orElseThrow();
        assertEquals(339, panel.x0());
        assertEquals(515, panel.x1());
        assertEquals(360, panel.y1());
        assertEquals(132F / 256F, panel.v0(), 0F);
        assertEquals(222F / 256F, panel.v1(), 0F);
        assertEquals(339, machine.x0());
        assertEquals(515, machine.x1());
        assertEquals(260, machine.y1());
        assertEquals(0F, machine.v0(), 0F);
        assertEquals(0.5F, machine.v1(), 0F);
        assertNotSame(panel.textureSetup().texure0(), machine.textureSetup().texure0());
        assertEquals(2, fixture.target.texts.size());
    }

    @Test public void failedDecorationCannotLeakActiveExtractor() throws Exception {
        var fixture = screen("Machine", "Inventory", 854);
        var failure = new IllegalStateException("deliberate decoration failure");
        fixture.gui.decoration = (screen, x, y) -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.screen.extractBackground(fixture.graphics, -100, -100, 0F)));
        assertNull(fixture.screen.getGraphics());
    }

    @Test public void longTitleSchedulesNativeDeferredTooltipOnlyInTheTitleRow() throws Exception {
        var fixture = screen("M".repeat(100), "Inventory", 200);
        fixture.screen.extractRenderState(fixture.graphics, 50, 121, 0F);
        assertNotNull(get(fixture.graphics, "deferredTooltip"));
        set(fixture.graphics, "deferredTooltip", null);
        fixture.screen.extractRenderState(fixture.graphics, 50, 132, 0F);
        assertNull(get(fixture.graphics, "deferredTooltip"));
        set(fixture.screen, "title", Component.literal("Machine"));
        fixture.screen.extractRenderState(fixture.graphics, 50, 121, 0F);
        assertNull(get(fixture.graphics, "deferredTooltip"));
    }

    private static Fixture screen(String title, String inventory, int width) throws Exception {
        var target = new TextQueue();
        var extraction = new NativeGuiExtractionFixture(target);
        var graphics = extraction.graphics;
        var gui = new RecordingGui();
        extraction.texture(gui.guiDisplayImage);
        extraction.texture(SimpleMachineGUI.playerInventoryBox);
        var screen = allocate(RecordingScreen.class);
        var font = new MetricsFont();
        set(screen, "this$0", gui);
        set(screen, "graphics", graphics);
        set(screen, "minecraft", extraction.minecraft);
        var inventoryItems = new Inventory(null, new EntityEquipment());
        set(screen, "menu", gui.createMenu(1, inventoryItems, new SimpleContainer(0)));
        set(screen, "draggingItem", net.minecraft.world.item.ItemStack.EMPTY);
        set(screen, "renderables", List.of());
        set(screen, "playerInventoryLabel", Component.literal(inventory));
        set(screen, "title", Component.literal(title));
        set(screen, "font", font);
        set(screen, "width", width);
        set(screen, "height", 480);
        set(screen, "imageWidth", 176);
        set(screen, "imageHeight", 240);
        set(screen, "topPos", 120);
        return new Fixture(screen, graphics, target, extraction.pose, font, gui);
    }

    private record Fixture(RecordingScreen screen, GuiGraphicsExtractor graphics,
            TextQueue target, Matrix3x2fStack pose, MetricsFont font, RecordingGui gui) {}

    @FunctionalInterface private interface Decoration {
        void extract(SimpleMachineGUI.GUIContainer screen, int x, int y);
    }

    private static final class RecordingGui extends SimpleMachineGUI {
        Decoration decoration = (screen, x, y) -> {};
        RecordingGui() { super(Identifier.parse("basepower:textures/gui/container/fluid_tank_gui.png")); }
        @Override public void drawGUIDecorations(Object entity, GUIContainer screen, int x, int y, float z) {
            decoration.extract(screen, x, y);
        }
    }

    private static final class RecordingScreen extends SimpleMachineGUI.GUIContainer {
        int backgroundCalls;
        RecordingScreen(SimpleMachineGUI gui, SimpleMachineGUI.Container menu, Inventory items) {
            gui.super(menu, items, Component.literal("Machine"));
        }
        @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
            backgroundCalls++;
            super.extractBackground(graphics, x, y, delta);
        }
    }

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
        @Override public void addText(GuiTextRenderState text) { texts.add(text); }
    }

    private static String text(GuiTextRenderState state) {
        var text = new StringBuilder();
        state.text.accept((index, style, point) -> { text.appendCodePoint(point); return true; });
        return text.toString();
    }

}
