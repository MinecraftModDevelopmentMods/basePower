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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;

/** Scoped native GUI extraction, GPU submission and fenced framebuffer readback. */
final class NativeGuiProjection implements AutoCloseable {
    private final Minecraft minecraft;
    private final RenderTarget original;
    private final RenderTarget target;
    private final Field targetField;
    private final Field widthField, heightField;
    private final int width, height, scale;
    private final GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
    private final ProjectionType projectionType = RenderSystem.getProjectionType();

    private NativeGuiProjection(Minecraft minecraft, int width, int height, int scale) throws ReflectiveOperationException {
        this.minecraft = minecraft;
        original = minecraft.getMainRenderTarget();
        var window = minecraft.getWindow();
        this.width = window.getWidth();
        this.height = window.getHeight();
        this.scale = window.getGuiScale();
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
    }

    static BufferedImage render(Minecraft minecraft, int width, int height, int scale, int background,
            Consumer<GuiGraphics> draw) {
        return render(minecraft, width, height, scale, background,
                state -> new GuiGraphics(minecraft, state, -100, -100), draw);
    }

    static BufferedImage render(Minecraft minecraft, int width, int height, int scale, int background,
            Function<GuiRenderState, GuiGraphics> graphicsFactory, Consumer<GuiGraphics> draw) {
        GuiRenderState state = new GuiRenderState();
        try (NativeGuiProjection scope = new NativeGuiProjection(minecraft, width, height, scale);
                GuiRenderer renderer = new GuiRenderer(state, minecraft.renderBuffers().bufferSource(),
                        minecraft.gameRenderer.getSubmitNodeStorage(),
                        minecraft.gameRenderer.getFeatureRenderDispatcher(), List.of())) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    scope.target.getColorTexture(), background, scope.target.getDepthTexture(), 1.0);
            GuiGraphics graphics = graphicsFactory.apply(state);
            draw.accept(graphics);
            graphics.renderDeferredElements();
            Field fogField = minecraft.gameRenderer.getClass().getDeclaredField("fogRenderer");
            fogField.setAccessible(true);
            FogRenderer fog = (FogRenderer) fogField.get(minecraft.gameRenderer);
            renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE));
            return read(scope.target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot scope the actual native GUI target", failure);
        }
    }

    private static BufferedImage read(RenderTarget target) {
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
            RenderSystem.setProjectionMatrix(projection, projectionType);
        } finally {
            minecraft.gameRenderer.getSubmitNodeStorage().endFrame();
            minecraft.gameRenderer.getFeatureRenderDispatcher().endFrame();
            target.destroyBuffers();
        }
    }
}
