package zone.moddev.mc.advantageworks.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/** Scoped native shader projection for framebuffer-based screen tests. */
final class NativeGuiProjection implements AutoCloseable {
    private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    private final VertexSorting sorting = RenderSystem.getVertexSorting();
    private final Matrix4fStack modelView = RenderSystem.getModelViewStack();

    NativeGuiProjection(float width, float height, float near, float far, float depth) {
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, width, height, 0, near, far),
                VertexSorting.ORTHOGRAPHIC_Z);
        modelView.pushMatrix();
        modelView.identity();
        modelView.translate(0, 0, depth);
        RenderSystem.applyModelViewMatrix();
    }

    @Override public void close() {
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, sorting);
    }
}
