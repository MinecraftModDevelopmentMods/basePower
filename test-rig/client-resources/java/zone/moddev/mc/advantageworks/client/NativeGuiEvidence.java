package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Retains the native queue and fenced framebuffer bytes, including deliberately broken frames. */
final class NativeGuiEvidence {
    private static final Map<BufferedImage, JsonObject> FRAMES = new WeakHashMap<>();
    private static final Map<Identifier, Source> SOURCES = new HashMap<>();
    private static int sequence;

    private NativeGuiEvidence() {}

    record Source(BufferedImage image, JsonObject proof) {}

    static Source source(Minecraft minecraft, Identifier id) {
        return SOURCES.computeIfAbsent(id, key -> {
            try (var input = minecraft.getResourceManager().open(id)) {
                byte[] bytes = input.readAllBytes();
                BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (image == null) throw new IOException("Invalid reference artwork " + id);
                JsonObject proof = new JsonObject();
                proof.addProperty("resource", id.toString());
                proof.addProperty("sha256", sha256(bytes));
                proof.addProperty("width", image.getWidth()); proof.addProperty("height", image.getHeight());
                return new Source(image, proof);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load independent source artwork " + id, failure);
            }
        });
    }

    static final class State extends GuiRenderState {
        private final JsonArray texts = new JsonArray();
        private final JsonArray blits = new JsonArray();
        private final JsonArray fills = new JsonArray();

        @Override public void addText(GuiTextRenderState text) {
            JsonObject row = new JsonObject();
            StringBuilder value = new StringBuilder();
            text.text.accept((index, style, codepoint) -> { value.appendCodePoint(codepoint); return true; });
            row.addProperty("text", value.toString());
            row.addProperty("x", text.x);
            row.addProperty("y", text.y);
            row.addProperty("color", text.color);
            row.addProperty("dropShadow", text.dropShadow);
            row.add("pose", matrix(text.pose));
            texts.add(row);
            super.addText(text);
        }

        @Override public void addGuiElement(GuiElementRenderState state) {
            if (state instanceof ColoredRectangleRenderState fill) {
                if (fills.size() >= 10000) throw new IllegalStateException("Unbounded native color extraction");
                JsonObject row = new JsonObject();
                row.addProperty("x0", fill.x0()); row.addProperty("y0", fill.y0());
                row.addProperty("x1", fill.x1()); row.addProperty("y1", fill.y1());
                row.addProperty("col1", fill.col1()); row.addProperty("col2", fill.col2());
                row.addProperty("pipeline", fill.pipeline().getLocation().toString());
                row.add("pose", matrix(fill.pose()));
                fills.add(row);
            }
            if (state instanceof BlitRenderState blit) {
                if (blits.size() >= 10000) throw new IllegalStateException("Unbounded native GUI extraction");
                JsonObject row = new JsonObject();
                row.addProperty("x0", blit.x0()); row.addProperty("y0", blit.y0());
                row.addProperty("x1", blit.x1()); row.addProperty("y1", blit.y1());
                row.addProperty("u0", blit.u0()); row.addProperty("v0", blit.v0());
                row.addProperty("u1", blit.u1()); row.addProperty("v1", blit.v1());
                row.addProperty("color", blit.color());
                row.addProperty("pipeline", blit.pipeline().getLocation().toString());
                row.addProperty("textureViewIdentity", System.identityHashCode(blit.textureSetup().texure0()));
                row.add("pose", matrix(blit.pose()));
                if (blit.scissorArea() != null) {
                    JsonArray clip = new JsonArray();
                    clip.add(blit.scissorArea().left()); clip.add(blit.scissorArea().top());
                    clip.add(blit.scissorArea().width()); clip.add(blit.scissorArea().height());
                    row.add("scissor", clip);
                }
                blits.add(row);
            }
            super.addGuiElement(state);
        }

        JsonObject extracted() {
            JsonObject result = new JsonObject();
            result.add("texts", texts.deepCopy());
            result.add("blits", blits.deepCopy());
            result.add("fills", fills.deepCopy());
            return result;
        }
    }

    private static JsonArray matrix(org.joml.Matrix3x2fc pose) {
        JsonArray result = new JsonArray();
        result.add(pose.m00()); result.add(pose.m01()); result.add(pose.m10());
        result.add(pose.m11()); result.add(pose.m20()); result.add(pose.m21());
        return result;
    }

    static void retain(BufferedImage image, JsonObject extraction) {
        if (++sequence > 2000) throw new IllegalStateException("Unbounded native framebuffer capture");
        String stem = "native-gui-evidence/frame-" + String.format(java.util.Locale.ROOT, "%04d", sequence);
        try {
            Path png = Path.of(stem + ".png"), rgba = Path.of(stem + ".rgba"), nativeState = Path.of(stem + "-native.json");
            Files.createDirectories(png.getParent());
            try (var output = Files.newOutputStream(png, StandardOpenOption.CREATE_NEW)) {
                if (!ImageIO.write(image, "png", output)) throw new IOException("No PNG writer");
            }
            try (var output = new java.io.BufferedOutputStream(Files.newOutputStream(rgba, StandardOpenOption.CREATE_NEW))) {
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    int color = image.getRGB(x, y);
                    output.write(color >>> 16 & 255); output.write(color >>> 8 & 255);
                    output.write(color & 255); output.write(color >>> 24 & 255);
                }
            }
            extraction.addProperty("protocol", "NATIVE64_GUI_FRAMEBUFFER_FENCED_V1");
            extraction.addProperty("width", image.getWidth());
            extraction.addProperty("height", image.getHeight());
            Files.writeString(nativeState, extraction.toString(), StandardOpenOption.CREATE_NEW);
            JsonObject frame = new JsonObject();
            frame.addProperty("width", image.getWidth()); frame.addProperty("height", image.getHeight());
            frame.add("png", artifact(png)); frame.add("rgba", artifact(rgba)); frame.add("native", artifact(nativeState));
            FRAMES.put(image, frame);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot freeze native GUI framebuffer evidence", failure);
        }
    }

    static JsonObject frame(BufferedImage image) {
        JsonObject result = FRAMES.get(image);
        if (result == null) throw new IllegalStateException("Image lacks native fenced extraction/framebuffer custody");
        return result.deepCopy();
    }

    private static JsonObject artifact(Path path) throws IOException {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("file", path.toString().replace('\\', '/'));
            result.addProperty("sha256", sha256(Files.readAllBytes(path)));
            return result;
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
