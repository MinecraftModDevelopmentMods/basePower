package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import io.netty.util.AttributeKey;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native117RecipeTransportTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test public void nativeRecipeKeyRetainsTheExternalIdentifier() {
        var key = RecipeUnlockProbe.recipeKey("basepower:starch_from_potato");
        assertTrue(key.isFor(net.minecraft.core.registries.Registries.RECIPE));
        assertEquals("basepower:starch_from_potato", key.identifier().toString());
    }
    @Test public void nativeChannelActivationSuppliesRealConnectionAttributesWithoutASocket() {
        Connection connection = RecipeUnlockProbe.nativeTransport();
        EmbeddedChannel channel = (EmbeddedChannel) connection.channel();
        try {
            assertTrue(channel.isActive());
            AttributeKey<String> key = AttributeKey.valueOf("advantageworks_unit_transport");
            assertNull(connection.channel().attr(key).get());
            connection.channel().attr(key).set("native");
            assertEquals("native", channel.attr(key).get());
            assertFalse(connection.isMemoryConnection());
        } finally { channel.finishAndReleaseAll(); }
        assertFalse(channel.isActive());
    }
}
