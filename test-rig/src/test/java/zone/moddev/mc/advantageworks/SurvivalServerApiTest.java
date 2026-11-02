package zone.moddev.mc.advantageworks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

/** A narrow regression guard; packaged dedicated-server tests remain required. */
public class SurvivalServerApiTest {
    @Test public void merchantFixtureDoesNotCallTheClientOnlyPacketSetter() throws Exception {
        String source=new String(Files.readAllBytes(Paths.get(
                "src/main/java/zone/moddev/mc/advantageworks/NativeSurvivalProgressionProbe.java")),StandardCharsets.UTF_8);
        assertFalse(source.contains(".setRecipes("));
        assertFalse(source.contains("func_70930_a"));
        assertTrue(source.contains("villager.readEntityFromNBT(merchant)"));
    }
}
