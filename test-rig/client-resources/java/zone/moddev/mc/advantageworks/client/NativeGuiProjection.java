package zone.moddev.mc.advantageworks.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.WindowRenderState;

/** Scoped native GUI extraction, GPU submission and fenced framebuffer readback. */
final class NativeGuiProjection implements AutoCloseable {
    private final Minecraft minecraft;
    private final RenderTarget original;
    private final RenderTarget target;
    private final Field targetField;
    private final Field widthField, heightField;
    private final int width, height, scale;
    private final WindowRenderState windowState;
    private final int stateWidth, stateHeight, stateScale;
    private final GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
    private final ProjectionType projectionType = RenderSystem.getProjectionType();

    private NativeGuiProjection(Minecraft minecraft, int width, int height, int scale) throws ReflectiveOperationException {
        this.minecraft = minecraft;
        original = minecraft.getMainRenderTarget();
        var window = minecraft.getWindow();
        this.width = window.getWidth();
        this.height = window.getHeight();
        this.scale = window.getGuiScale();
        windowState = minecraft.gameRenderer.getGameRenderState().windowRenderState;
        stateWidth = windowState.width;
        stateHeight = windowState.height;
        stateScale = windowState.guiScale;
        targetField = Minecraft.class.getDeclaredField("mainRenderTarget");
        targetField.setAccessible(true);
        widthField = window.getClass().getDeclaredField("framebufferWidth");
        heightField = window.getClass().getDeclaredField("framebufferHeight");
        widthField.setAccessible(true);
        heightField.setAccessible(true);
        target = new TextureTarget("Advantage native GUI witness", width, height, true);
        targetField.set(minecraft, target);
        widthField.setInt(window, width);
        heightField.setInt(window, height);
        window.setGuiScale(scale);
        // Native 26 projects GUI geometry from the extracted frame, not the live window.
        windowState.width = width;
        windowState.height = height;
        windowState.guiScale = scale;
    }

    static com.google.gson.JsonObject calibrate(Minecraft minecraft) {
        final int width = 64, height = 48, background = 0xFF101820;
        com.google.gson.JsonArray cases = new com.google.gson.JsonArray();
        for (int scale : new int[] {1, 2}) {
            BufferedImage actual = render(minecraft, width * scale, height * scale, scale, background,
                    graphics -> drawRuler(graphics, width, height, 0));
            String error = rulerError(actual, width, height, scale, background);
            if (error != null) throw new IllegalStateException("Native GUI projection calibration failed: " + error);
            BufferedImage shifted = render(minecraft, width * scale, height * scale, scale, background,
                    graphics -> drawRuler(graphics, width, height, 1));
            if (rulerError(shifted, width, height, scale, background) == null) {
                throw new IllegalStateException("Native GUI projection accepted a shifted calibration rectangle");
            }
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("scale", scale);
            row.addProperty("logicalWidth", width);
            row.addProperty("logicalHeight", height);
            row.addProperty("exactPixelsChecked", width * height * scale * scale);
            row.add("actual", NativeGuiEvidence.frame(actual));
            row.add("shiftedRejected", NativeGuiEvidence.frame(shifted));
            cases.add(row);
        }
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("protocol", "NATIVE64_GUI_PROJECTION_PIXEL_RULER_V1");
        result.addProperty("status", "PASS");
        result.add("cases", cases);
        return result;
    }

    private static void drawRuler(GuiGraphicsExtractor graphics, int width, int height, int shift) {
        graphics.fill(RenderPipelines.GUI, 7 + shift, 9, 23 + shift, 25, 0xFF37A95C);
        graphics.fill(RenderPipelines.GUI, 2, 2, 3, 3, 0xFF3158D5);
        graphics.fill(RenderPipelines.GUI, width - 1, height - 1, width, height, 0xFFDD7341);
    }

    private static String rulerError(BufferedImage image, int width, int height, int scale, int background) {
        if (image.getWidth() != width * scale || image.getHeight() != height * scale) return "Wrong framebuffer dimensions";
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int u = x / scale, v = y / scale;
            int expected = u >= 7 && u < 23 && v >= 9 && v < 25 ? 0xFF37A95C
                    : u == 2 && v == 2 ? 0xFF3158D5
                    : u == width - 1 && v == height - 1 ? 0xFFDD7341 : background;
            if (image.getRGB(x, y) != expected) return "Wrong pixel at " + x + "," + y;
        }
        return null;
    }

    static BufferedImage render(Minecraft minecraft, int width, int height, int scale, int background,
            Consumer<GuiGraphicsExtractor> draw) {
        return render(minecraft, width, height, scale, background,
                state -> new GuiGraphicsExtractor(minecraft, state, -100, -100), draw);
    }

    static BufferedImage render(Minecraft minecraft, int width, int height, int scale, int background,
            Function<GuiRenderState, GuiGraphicsExtractor> graphicsFactory, Consumer<GuiGraphicsExtractor> draw) {
        NativeGuiEvidence.State state = new NativeGuiEvidence.State();
        try (NativeGuiProjection scope = new NativeGuiProjection(minecraft, width, height, scale);
                GuiRenderer renderer = new GuiRenderer(state, minecraft.renderBuffers().bufferSource(),
                        minecraft.gameRenderer.getSubmitNodeStorage(),
                        minecraft.gameRenderer.getFeatureRenderDispatcher(), List.of())) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    scope.target.getColorTexture(), background, scope.target.getDepthTexture(), 1.0);
            GuiGraphicsExtractor graphics = graphicsFactory.apply(state);
            draw.accept(graphics);
            graphics.extractDeferredElements(-100, -100, 0F);
            Field fogField = minecraft.gameRenderer.getClass().getDeclaredField("fogRenderer");
            fogField.setAccessible(true);
            FogRenderer fog = (FogRenderer) fogField.get(minecraft.gameRenderer);
            renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE));
            BufferedImage image = read(scope.target);
            var extraction = state.extracted();
            extraction.addProperty("extractor", graphics.getClass().getName());
            extraction.addProperty("renderer", GuiRenderer.class.getName());
            extraction.addProperty("renderSubmitted", true);
            extraction.addProperty("fenceCompleted", true);
            extraction.addProperty("screenshotCallbackCompleted", true);
            NativeGuiEvidence.retain(image, extraction);
            return image;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot scope the actual native GUI target", failure);
        }
    }

    static BufferedImage read(RenderTarget target) {
        AtomicReference<NativeImage> captured = new AtomicReference<>();
        Screenshot.takeScreenshot(target, captured::set);
        try (var fence = RenderSystem.getDevice().createCommandEncoder().createFence()) {
            if (!fence.awaitCompletion(10_000_000_000L)) throw new IllegalStateException("Native GUI readback fence timed out");
            RenderSystem.executePendingTasks();
            NativeImage image = captured.get();
            if (image == null) throw new IllegalStateException("Native screenshot callback did not complete");
            try (image) {
                BufferedImage result = new BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < target.height; y++) for (int x = 0; x < target.width; x++)
                    result.setRGB(x, y, image.getPixel(x, y));
                return result;
            }
        }
    }

    @Override public void close() throws ReflectiveOperationException {
        try {
            targetField.set(minecraft, original);
            widthField.setInt(minecraft.getWindow(), width);
            heightField.setInt(minecraft.getWindow(), height);
            minecraft.getWindow().setGuiScale(scale);
            windowState.width = stateWidth;
            windowState.height = stateHeight;
            windowState.guiScale = stateScale;
            RenderSystem.setProjectionMatrix(projection, projectionType);
        } finally {
            minecraft.gameRenderer.getSubmitNodeStorage().endFrame();
            minecraft.gameRenderer.getFeatureRenderDispatcher().endFrame();
            target.destroyBuffers();
        }
    }
}
