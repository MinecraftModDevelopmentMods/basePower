package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class Native12111DefinitionTest {
    @Test public void retainsEveryExistingStationWithoutEditingItsExpectedBehaviour() {
        Gson gson = new Gson();
        WorksDefinition source = WorksDefinitionLoader.loadNative1211();
        WorksDefinition target = WorksDefinitionLoader.loadNative12111();
        assertEquals("1.21.11", target.minecraft);
        assertEquals(21, target.stations.size());
        assertEquals(gson.toJsonTree(source.stations), gson.toJsonTree(target.stations));
        assertEquals(gson.toJsonTree(source.districts), gson.toJsonTree(target.districts));
    }
}
