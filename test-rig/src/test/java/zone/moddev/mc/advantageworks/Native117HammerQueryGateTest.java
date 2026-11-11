package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native117HammerQueryGateTest {
    private static JsonObject visible() {
        JsonObject before = new JsonObject();
        before.addProperty("sourceInAabb", true);
        before.addProperty("uuidLookupIsSource", true);
        before.addProperty("entityTicking", false);
        before.addProperty("entitiesLoaded", false);
        JsonObject source = new JsonObject();
        source.addProperty("isSource", true);
        JsonArray items = new JsonArray();
        items.add(source);
        before.add("items", items);
        return before;
    }

    @Test public void trackedSourceNeedNotHaveTicked() {
        assertTrue(HammerQueryGate.ready(true, false, true, visible()));
    }
    @Test public void refusedAdditionCannotInvoke() {
        assertFalse(HammerQueryGate.ready(false, false, true, visible()));
    }
    @Test public void removedSourceCannotInvoke() {
        assertFalse(HammerQueryGate.ready(true, true, true, visible()));
    }
    @Test public void alteredInputCannotInvoke() {
        assertFalse(HammerQueryGate.ready(true, false, false, visible()));
    }
    @Test public void tickingFlagsCannotReplaceQueryIdentity() {
        JsonObject before = visible();
        before.addProperty("sourceInAabb", false);
        before.addProperty("entityTicking", true);
        before.addProperty("entitiesLoaded", true);
        assertFalse(HammerQueryGate.ready(true, false, true, before));
    }
    @Test public void uuidLookupDoesNotReplaceAabbIdentity() {
        JsonObject before = visible();
        before.addProperty("uuidLookupIsSource", false);
        assertFalse(HammerQueryGate.ready(true, false, true, before));
    }
    @Test public void sameContentsDecoyDoesNotCount() {
        JsonObject before = visible();
        before.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("isSource", false);
        assertFalse(HammerQueryGate.ready(true, false, true, before));
    }
    @Test public void contaminatedQueryCannotInvoke() {
        JsonObject before = visible();
        before.getAsJsonArray("items").add(new JsonObject());
        assertFalse(HammerQueryGate.ready(true, false, true, before));
        before.getAsJsonArray("items").remove(1);
        before.getAsJsonArray("items").remove(0);
        assertFalse(HammerQueryGate.ready(true, false, true, before));
    }
    @Test public void malformedSnapshotsFailClosed() {
        assertFalse(HammerQueryGate.ready(true, false, true, null));
        JsonObject before = visible();
        before.addProperty("sourceInAabb", "true");
        assertFalse(HammerQueryGate.ready(true, false, true, before));
        before = visible();
        before.remove("items");
        assertFalse(HammerQueryGate.ready(true, false, true, before));
    }
    @Test public void noMatchIsObservedAndMustReachOriginalActionOracle() {
        for (String status : new String[] {"MATCH", "NO_MATCH"}) {
            JsonObject selection = new JsonObject();
            selection.addProperty("status", status);
            assertTrue(HammerQueryGate.selectorObserved(selection));
        }
    }
    @Test public void observerErrorsAreNotMissingRecipes() {
        assertFalse(HammerQueryGate.selectorObserved(null));
        assertFalse(HammerQueryGate.selectorObserved(new JsonObject()));
        for (String status : new String[] {"OBSERVER_ERROR", "unknown", ""}) {
            JsonObject selection = new JsonObject();
            selection.addProperty("status", status);
            assertFalse(HammerQueryGate.selectorObserved(selection));
        }
    }
    @Test public void singleCallStillUsesOriginalActionAndOutputAssertions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/zone/moddev/mc/advantageworks/ProcessProgression.java"));
        String body = source.substring(source.indexOf("static void hammer("), source.indexOf("private static JsonObject hammerRecipeSelection("));
        assertEquals(1, body.split("\\.useOn\\(", -1).length - 1);
        assertTrue(body.indexOf("ADVHAMMER BEFORE") < body.indexOf("HammerQueryGate.ready("));
        assertTrue(body.indexOf("HammerQueryGate.ready(") < body.indexOf(".useOn("));
        assertTrue(body.indexOf(".useOn(") < body.indexOf("ADVHAMMER AFTER"));
        assertTrue(body.contains("attempt.addProperty(\"action\", action == InteractionResult.CONSUME ? \"CONSUME\" : action.toString())"));
        assertTrue(body.contains("action == InteractionResult.CONSUME && source.getItem().isEmpty()"));
        assertTrue(body.contains("player.getMainHandItem().getDamageValue() == 1, \"Live hammer failed \""));
        assertTrue(body.contains("id(entity.getItem()).equals(id(expected))"));
        assertTrue(body.contains("require(produced == expected.getCount()"));
        assertFalse(body.contains("Thread.sleep"));
        assertFalse(body.contains("while ("));
    }
}
