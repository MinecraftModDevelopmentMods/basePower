package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeWorldOpenRendezvousTest {
    private static JsonObject ready() {
        return JsonParser.parseString("{\"scope_results\":{\"child_geometry\":{\"status\":\"PASS\"}},"
                + "\"child_geometry_context\":{\"stage\":\"GENUINELY_WORLDLESS_BEFORE_LOGIN\","
                + "\"levelPresent\":false,\"playerPresent\":false,\"singleplayerServerPresent\":false}} ").getAsJsonObject();
    }

    @Test public void onlySuccessfulWorldlessGeometryCanReleaseTheNativeOpen() {
        assertTrue(ClientResourceProbe.readyForNativeWorldOpen(ready(), false, false, false, false, false, false));
        assertFalse(ClientResourceProbe.readyForNativeWorldOpen(null, false, false, false, false, false, false));
        assertFalse(ClientResourceProbe.readyForNativeWorldOpen(new JsonObject(), false, false, false, false, false, false));
        JsonObject failed = ready();
        failed.getAsJsonObject("scope_results").getAsJsonObject("child_geometry").addProperty("status", "FAIL");
        assertFalse(ClientResourceProbe.readyForNativeWorldOpen(failed, false, false, false, false, false, false));
    }

    @Test public void everyLiveWorldOrLoadingStateBlocksTheRendezvous() {
        for (int index = 0; index < 6; index++) {
            boolean[] state = new boolean[6];
            state[index] = true;
            assertFalse(ClientResourceProbe.readyForNativeWorldOpen(ready(), state[0], state[1], state[2], state[3], state[4], state[5]));
        }
        for (String field : new String[]{"levelPresent", "playerPresent", "singleplayerServerPresent"}) {
            JsonObject forged = ready();
            forged.getAsJsonObject("child_geometry_context").addProperty(field, true);
            assertFalse(ClientResourceProbe.readyForNativeWorldOpen(forged, false, false, false, false, false, false));
        }
        JsonObject wrongPhase = ready();
        wrongPhase.getAsJsonObject("child_geometry_context").addProperty("stage", "AFTER_LOGIN");
        assertFalse(ClientResourceProbe.readyForNativeWorldOpen(wrongPhase, false, false, false, false, false, false));
    }
}
