package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

public class Native263RecipeConditionsTest {
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private static JsonObject context() {
        return object("{\"mode\":\"NORMAL\",\"musketEnabled\":true,\"tags\":{"
                + "\"forge:power_supply_units\":[\"basepower:power_supply_unit\"],\"forge:ingots/refined_iron\":[]}}");
    }

    private static JsonObject bothTags() {
        return object("{\"type\":\"forge:and\",\"values\":["
                + "{\"type\":\"basepower:tag_not_empty\",\"tag\":\"forge:ingots/refined_iron\"},"
                + "{\"type\":\"basepower:tag_not_empty\",\"tag\":\"forge:power_supply_units\"}]}");
    }

    @Test public void allRequiredTagsMustExistEvenIfAnotherIngredientIsAvailable() {
        JsonObject context = context();
        assertFalse(Native263RecipeConditions.evaluate(bothTags(), context));
        context.getAsJsonObject("tags").getAsJsonArray("forge:ingots/refined_iron").add("example:refined_iron_ingot");
        assertTrue(Native263RecipeConditions.evaluate(bothTags(), context));
    }

    @Test public void absentConditionalPairIsExplicitlyValidButAnyMismatchFails() {
        for (boolean expected : new boolean[] {false, true})
            for (boolean recipe : new boolean[] {false, true})
                for (boolean advancement : new boolean[] {false, true}) {
                    if (recipe == expected && advancement == expected)
                        Native263RecipeConditions.requirePair("basepower:converter", expected, recipe, advancement);
                    else assertThrows(IllegalStateException.class, () ->
                            Native263RecipeConditions.requirePair("basepower:converter", expected, recipe, advancement));
                }
    }

    @Test public void falseBranchCannotHideMissingIndependentTagFacts() {
        JsonObject context = context();
        context.getAsJsonObject("tags").remove("forge:power_supply_units");
        assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.evaluate(bothTags(), context));
    }

    @Test public void unknownConditionCannotHideBehindFalseBranch() {
        JsonObject condition = bothTags();
        condition.getAsJsonArray("values").add(object("{\"type\":\"unit:unknown\"}"));
        assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.evaluate(condition, context()));
    }

    @Test public void modeAndMusketPredicatesUseIndependentConfigurationFacts() {
        for (String namespace : java.util.List.of("basepower", "basesteam", "baseelectric")) {
            JsonObject condition = object("{\"type\":\"" + namespace + ":recipe_mode\",\"mode\":\"NORMAL\"}");
            assertTrue(Native263RecipeConditions.evaluate(condition, context()));
            condition.addProperty("mode", "TECH_PROGRESSION");
            assertFalse(Native263RecipeConditions.evaluate(condition, context()));
        }
        assertFalse(Native263RecipeConditions.evaluate(object("{\"type\":\"baseelectric:not_recipe_mode\",\"mode\":\"NORMAL\"}"), context()));
        assertTrue(Native263RecipeConditions.evaluate(object("{\"type\":\"basesteam:musket_enabled\"}"), context()));
    }

    @Test public void tagEmptyIsNotEquivalentToTagNotEmpty() {
        assertTrue(Native263RecipeConditions.evaluate(object("{\"type\":\"basepower:tag_empty\",\"tag\":\"forge:ingots/refined_iron\"}"), context()));
        assertFalse(Native263RecipeConditions.evaluate(object("{\"type\":\"basepower:tag_empty\",\"tag\":\"forge:power_supply_units\"}"), context()));
    }

    @Test public void malformedConditionsFailClosed() {
        for (String json : java.util.List.of("{}", "{\"type\":\"forge:and\",\"values\":null}",
                "{\"type\":\"forge:and\",\"values\":[null]}", "{\"type\":\"basepower:recipe_mode\",\"mode\":\"invented\"}",
                "{\"type\":\"basepower:tag_not_empty\",\"tag\":true}",
                "{\"type\":\"basesteam:musket_enabled\",\"ignored\":true}"))
            assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.evaluate(object(json), context()));
    }

    @Test public void nativeForgeEmptyAndIsTrueForUnconditionalCookingRecipes() {
        assertTrue(Native263RecipeConditions.evaluate(object("{\"type\":\"forge:and\",\"values\":[]}"), context()));
    }
}
