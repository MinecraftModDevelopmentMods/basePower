package zone.moddev.mc.advantageworks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Preconditions for one synchronous native hammer call, not its outcome. */
final class HammerQueryGate {
    private HammerQueryGate() {}

    static boolean ready(boolean accepted, boolean removed, boolean inputMatches, JsonObject before) {
        if (!accepted || removed || !inputMatches || before == null
                || !flag(before.get("sourceInAabb")) || !flag(before.get("uuidLookupIsSource"))) return false;
        JsonElement items = before.get("items");
        return items != null && items.isJsonArray() && items.getAsJsonArray().size() == 1
                && items.getAsJsonArray().get(0).isJsonObject()
                && flag(items.getAsJsonArray().get(0).getAsJsonObject().get("isSource"));
    }

    static boolean selectorObserved(JsonObject selection) {
        JsonElement status = selection == null ? null : selection.get("status");
        if (status == null || !status.isJsonPrimitive() || !status.getAsJsonPrimitive().isString()) return false;
        return "MATCH".equals(status.getAsString()) || "NO_MATCH".equals(status.getAsString());
    }

    private static boolean flag(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
