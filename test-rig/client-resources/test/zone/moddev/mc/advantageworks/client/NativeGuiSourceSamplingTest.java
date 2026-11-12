package zone.moddev.mc.advantageworks.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent device-pixel centers for the native blit's fixed 256-logical UV denominator. */
public class NativeGuiSourceSamplingTest {
    private static BufferedImage source(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setRGB(x, y, 0xFF000000 | x << 9 | y);
        return image;
    }

    @Test public void source512AtScale2PreservesFourDistinctNativePhysicalTexels() {
        BufferedImage image = source(512);
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
            assertEquals(image.getRGB(142 + dx, 86 + dy), MachineGuiLayoutProbe.texel(image, 71, 43, dx, dy, 2));
        assertNotEquals(MachineGuiLayoutProbe.texel(image, 71, 43, 0, 0, 2), MachineGuiLayoutProbe.texel(image, 71, 43, 1, 1, 2));
        assertEquals(image.getRGB(143, 87), MachineGuiLayoutProbe.texel(image, 71, 43, 0, 0, 1));
    }

    @Test public void source256AtScale2RepeatsTheSameTexelWithoutChangingLogicalUvs() {
        BufferedImage image = source(256);
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
            assertEquals(image.getRGB(71, 43), MachineGuiLayoutProbe.texel(image, 71, 43, dx, dy, 2));
    }
}
