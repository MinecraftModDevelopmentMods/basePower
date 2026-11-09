package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class Native117ProgressionJsonTest {
    @Test public void emptyAndSingletonInputsDoNotReflectIntoJdkPrivateCollections() {
        assertEquals("[]", ProgressionProbe.inputJson(Collections.emptyList()).toString());
        assertEquals("[\"minecraft:potato\"]", ProgressionProbe.inputJson(Collections.singletonList("minecraft:potato")).toString());
    }

    @Test public void ingredientOrderAndRepeatedInputsAreRetained() {
        assertEquals("[\"a\",\"b\",\"b\"]", ProgressionProbe.inputJson(Arrays.asList("a", "b", "b")).toString());
    }
}
