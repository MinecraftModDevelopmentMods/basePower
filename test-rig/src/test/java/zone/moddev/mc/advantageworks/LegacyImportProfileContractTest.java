package zone.moddev.mc.advantageworks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyImportProfileContractTest {
    @Test public void freshOs1ImportsUseTheMigratedFileBasenamesWithoutModernDuplicates() throws Exception {
        JsonObject profile;
        try (FileReader reader = new FileReader("profiles/worldgen-legacy-import.json")) {
            profile = new JsonParser().parse(reader).getAsJsonObject();
        }
        Set<String> retained = new HashSet<>(Arrays.asList(
                "basepower:legacy/basepower_crude_oil_0",
                "baseelectric:legacy/baseelectric_li_ore_0",
                "baseelectric:legacy/baseelectric_sulfur_ore_1"));
        for (String phase : new String[] {"firstProfileAssertions", "restartProfileAssertions"}) {
            Set<String> actual = new HashSet<>();
            int absentModernDefaults = 0;
            for (JsonElement element : profile.getAsJsonArray(phase)) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("present").getAsBoolean()) {
                    assertTrue(row.get("expectedBoolean").getAsBoolean());
                    assertTrue(actual.add(row.get("entry").getAsString()));
                } else {
                    assertFalse(row.get("entry").getAsString().contains("legacy/"));
                    absentModernDefaults++;
                }
            }
            assertEquals(retained, actual);
            assertEquals(3, absentModernDefaults);
        }
    }
}
