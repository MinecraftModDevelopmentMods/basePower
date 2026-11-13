package zone.moddev.mc.basepower.resources;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/** CPU extraction only: real native queues/scissors, but no texture upload or GPU rendering. */
public final class NativeGuiExtractionFixture {
    public final Minecraft minecraft;
    public final GuiGraphicsExtractor graphics;
    public final Matrix3x2fStack pose = new Matrix3x2fStack(16);
    private final Map<Identifier, SimpleTexture> textures = new HashMap<>();

    public NativeGuiExtractionFixture(GuiRenderState target) throws Exception {
        // Skip GPU startup only; every extraction/scissor operation runs the native implementation.
        minecraft = allocate(Minecraft.class);
        var manager = allocate(TextureManager.class);
        set(manager, "byPath", textures);
        set(minecraft, "textureManager", manager);
        var gui = allocate(Gui.class);
        set(gui, "hud", allocate(net.minecraft.client.gui.Hud.class));
        set(minecraft, "gui", gui);
        graphics = allocate(GuiGraphicsExtractor.class);
        set(graphics, "minecraft", minecraft);
        set(graphics, "pose", pose);
        set(graphics, "guiRenderState", target);
        var constructor = Class.forName(GuiGraphicsExtractor.class.getName() + "$ScissorStack")
                .getDeclaredConstructor(net.minecraft.client.gui.navigation.ScreenRectangle.class);
        constructor.setAccessible(true);
        set(graphics, "scissorStack", constructor.newInstance(
                new net.minecraft.client.gui.navigation.ScreenRectangle(0, 0, 854, 480)));
    }

    public GpuTextureView texture(Identifier id) throws Exception {
        var texture = allocate(SimpleTexture.class);
        var view = new GpuTextureView() {
            @Override public void close() { throw new UnsupportedOperationException("CPU fixture"); }
            @Override public boolean isClosed() { return false; }
            @Override public com.mojang.renderpearl.api.textures.GpuTexture texture() { return null; }
            @Override public int baseMipLevel() { return 0; }
            @Override public int mipLevels() { return 1; }
            @Override public int getWidth(int mipLevel) { return 64; }
            @Override public int getHeight(int mipLevel) { return 64; }
        };
        set(texture, "textureView", view);
        textures.put(id, texture);
        return view;
    }

    public TextureAtlasSprite sprite(Identifier atlas) throws Exception {
        texture(atlas);
        var contents = allocate(SpriteContents.class);
        set(contents, "width", 16);
        set(contents, "height", 16);
        return new TextureAtlasSprite(atlas, contents, 64, 64, 16, 32, 0);
    }

    public static <T> T allocate(Class<T> type) throws Exception {
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Field instance = allocator.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        return type.cast(allocator.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type));
    }

    public static Object get(Object instance, String name) throws Exception {
        return field(instance, name).get(instance);
    }

    public static void set(Object instance, String name, Object value) throws Exception {
        field(instance, name).set(instance, value);
    }

    private static Field field(Object instance, String name) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
