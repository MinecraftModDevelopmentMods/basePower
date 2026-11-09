package zone.moddev.mc.advantageworks.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;

/** Scoped native shader projection for framebuffer-based screen tests. */
final class NativeGuiProjection implements AutoCloseable {
    private final Matrix4f projection = RenderSystem.getProjectionMatrix().copy();
    private final PoseStack modelView = RenderSystem.getModelViewStack();

    NativeGuiProjection(float width, float height, float near, float far, float depth) {
        // Native Matrix4f takes top/bottom, unlike the old OpenGL bottom/top call.
        RenderSystem.setProjectionMatrix(Matrix4f.orthographic(0, width, 0, height, near, far));
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0, 0, depth);
        RenderSystem.applyModelViewMatrix();
    }

    @Override public void close() {
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection);
    }
}
