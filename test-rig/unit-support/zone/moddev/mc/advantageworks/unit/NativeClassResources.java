package zone.moddev.mc.advantageworks.unit;

import java.io.InputStream;

/** Reads original dependency bytes through their owning module, before test ASM rewrites. */
public final class NativeClassResources {
    private NativeClassResources() { }
    public static InputStream open(String resource) {
        String path = resource.startsWith("/") ? resource : "/" + resource;
        if (!path.endsWith(".class")) throw new IllegalArgumentException("Expected a class resource");
        String binary = path.substring(1, path.length() - 6).replace('/', '.');
        try {
            Class<?> owner = Class.forName(binary, false, NativeClassResources.class.getClassLoader());
            return owner.getResourceAsStream(path);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Missing native dependency " + binary, failure);
        }
    }
}
