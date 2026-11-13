package zone.moddev.mc.advantageworks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.TreeSet;

/** Independent fixture interpreter; never calls production condition implementations. */
final class Native263RecipeConditions {
    private static final Set<String> MODES = Set.of("NORMAL", "TECH_PROGRESSION", "APOCALYPTIC");

    static Set<String> tags(JsonElement condition) {
        Set<String> result = new TreeSet<>();
        inspect(condition, result);
        return result;
    }

    private static void inspect(JsonElement condition, Set<String> tags) {
        if (condition == null || condition.isJsonNull()) return;
        require(condition.isJsonObject(), "Non-object recipe condition");
        JsonObject object = condition.getAsJsonObject();
        String type = string(object, "type");
        switch (type) {
            case "forge:and" -> {
                keys(object, "type", "values");
                require(object.has("values") && object.get("values").isJsonArray(), "Malformed AND condition");
                for (JsonElement child : object.getAsJsonArray("values")) {
                    require(!child.isJsonNull(), "Null AND operand");
                    inspect(child, tags);
                }
            }
            case "basepower:tag_not_empty", "basepower:tag_empty" -> {
                keys(object, "type", "tag");
                String tag = string(object, "tag");
                require(tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "Invalid condition tag");
                tags.add(tag);
            }
            case "basepower:recipe_mode", "basesteam:recipe_mode", "baseelectric:recipe_mode",
                    "baseelectric:not_recipe_mode" -> {
                keys(object, "type", "mode");
                require(MODES.contains(string(object, "mode")), "Unknown condition mode");
            }
            case "basesteam:musket_enabled" -> keys(object, "type");
            default -> throw new IllegalStateException("Unsupported fixture condition: " + type);
        }
    }

    static boolean evaluate(JsonElement condition, JsonObject context) {
        tags(condition);
        return evaluateChecked(condition, context);
    }

    private static boolean evaluateChecked(JsonElement condition, JsonObject context) {
        if (condition == null || condition.isJsonNull()) return true;
        JsonObject object = condition.getAsJsonObject();
        String type = string(object, "type");
        if (type.equals("forge:and")) {
            boolean result = true;
            for (JsonElement child : object.getAsJsonArray("values")) result &= evaluateChecked(child, context);
            return result;
        }
        if (type.equals("basepower:tag_empty") || type.equals("basepower:tag_not_empty")) {
            JsonElement members = context.getAsJsonObject("tags").get(string(object, "tag"));
            require(members != null && members.isJsonArray(), "Missing independent condition tag facts");
            return members.getAsJsonArray().isEmpty() == type.equals("basepower:tag_empty");
        }
        if (type.equals("basesteam:musket_enabled")) {
            JsonElement enabled = context.get("musketEnabled");
            require(enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean(),
                    "Missing actual musket configuration");
            return enabled.getAsBoolean();
        }
        String mode = string(context, "mode");
        require(MODES.contains(mode), "Unknown actual recipe mode");
        return mode.equals(string(object, "mode")) != type.equals("baseelectric:not_recipe_mode");
    }

    static void requirePair(String id, boolean expected, boolean recipe, boolean advancement) {
        require(recipe == advancement, "Mismatched loaded recipe/advancement pair: " + id);
        require(recipe == expected, "Loaded recipe/advancement pair disagrees with independent condition: " + id);
    }

    private static void keys(JsonObject object, String... keys) {
        require(object.keySet().equals(Set.of(keys)), "Unexpected fixture condition fields");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
                "Missing condition string: " + key);
        return value.getAsString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
