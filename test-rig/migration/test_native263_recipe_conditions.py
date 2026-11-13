"""Condition/pair negatives and the actual frozen family02 recipe resource inventory."""

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import native263_recipe_conditions as conditions
import native263 as native


def tag(name):
    return {"type": "basepower:tag_not_empty", "tag": "forge:" + name}


def fixture_and_result(active=False, available=True):
    condition = {"type": "forge:and", "values": [tag("ingots/refined_iron"), tag("power_supply_units")]}
    row = {"id": "basepower:converter_tr_electricity_refined_iron", "condition": condition,
           "recipeTriggerTag": "basepower:discovery/converter_tr_electricity_refined_iron",
           "ingredients": {"material": {"tag": "forge:ingots/refined_iron"},
                           "part": {"tag": "forge:power_supply_units"}}}
    context = {"mode": "NORMAL", "musketEnabled": True,
               "tags": {"forge:ingots/refined_iron": ["unit:ingot"] if active else [],
                        "forge:power_supply_units": ["basepower:power_supply_unit"] if available else []}}
    case = {"id": row["id"], "conditionExpected": active, "recipeActive": active, "advancementActive": True,
            "recipeConditionStatus": "CONDITION_ENABLED" if active else "CONDITION_DISABLED",
            "recipeTriggerTag": row["recipeTriggerTag"], "recipeTriggerTagMembers": [row["id"]] if active else [],
            "ingredientProviderAvailable": available,
            "status": ("PASS" if active else "ADVANCEMENT_ONLY") if available else "SKIP_NO_INGREDIENT_PROVIDER", "discovered": available,
            "positiveCriteria": 2 if active else int(available), "tagMembers": 2 if active else int(available)}
    definition = {"conditionProtocol": conditions.PROTOCOL, "recipes": [row]}
    result = {"conditionProtocol": conditions.PROTOCOL, "conditionContext": context,
              "label": "normal-sweep", "operation": "sweep", "status": "PASS", "potatoCraftingCases": 59,
              "cases": [case], "bookRecipes": [{"id": "unit:other"}] + ([{"id": row["id"]}] if active else [])}
    return definition, result


class Native263RecipeConditionsTest(unittest.TestCase):
    def test_all_required_tags_not_any_available_ingredient(self):
        definition, result = fixture_and_result()
        self.assertFalse(conditions.evaluate(definition["recipes"][0]["condition"], result["conditionContext"]))
        counts = conditions.validate_unlock(result, definition, "sweep")
        self.assertEqual((0, 1, 0), (counts["passed"], counts["advancementOnly"], counts["skipped"]))
        self.assertFalse(result["cases"][0]["recipeActive"])
        self.assertTrue(result["cases"][0]["advancementActive"])

    def test_active_pair_requires_native_discovery_witnesses(self):
        definition, result = fixture_and_result(True)
        self.assertEqual(1, conditions.validate_unlock(result, definition, "sweep")["passed"])
        for key, value in (("positiveCriteria", 0), ("tagMembers", 0), ("status", "SKIP_NO_INGREDIENT_PROVIDER")):
            bad = copy.deepcopy(result)
            bad["cases"][0][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                conditions.validate_unlock(bad, definition, "sweep")

    def test_missing_active_and_mismatched_and_unexpected_inactive_pairs_fail(self):
        for active in (False, True):
            definition, result = fixture_and_result(active)
            for recipe in (False, True):
                for advancement in (False, True):
                    if recipe == active and advancement:
                        continue
                    bad = copy.deepcopy(result)
                    bad["cases"][0].update(recipeActive=recipe, advancementActive=advancement)
                    with self.subTest(active=active, recipe=recipe, advancement=advancement), self.assertRaises(AssertionError):
                        conditions.validate_unlock(bad, definition, "sweep")

    def test_wrong_skip_or_presence_or_context_cannot_be_accepted(self):
        definition, result = fixture_and_result()
        mutations = [lambda x: x["cases"][0].update(status="SKIP_NO_INGREDIENT_PROVIDER"),
                     lambda x: x["cases"][0].update(conditionExpected=True),
                     lambda x: x["cases"][0].update(discovered=False),
                     lambda x: x["cases"][0].update(recipeTriggerTag="basepower:discovery/wrong"),
                     lambda x: x["cases"][0].update(recipeTriggerTagMembers=["basepower:wrong_recipe"]),
                     lambda x: x["cases"][0].update(ingredientProviderAvailable=False),
                     lambda x: x["bookRecipes"].append({"id": definition["recipes"][0]["id"]}),
                     lambda x: x["conditionContext"].update(mode="TECH_PROGRESSION"),
                     lambda x: x["conditionContext"]["tags"].update({"forge:power_supply_units": ["unit:a", "unit:a"]})]
        for mutation in mutations:
            bad = copy.deepcopy(result)
            mutation(bad)
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                conditions.validate_unlock(bad, definition, "sweep")

    def test_false_first_branch_does_not_hide_missing_facts_or_unknown_condition(self):
        definition, result = fixture_and_result()
        context = result["conditionContext"]
        del context["tags"]["forge:power_supply_units"]
        with self.assertRaises(AssertionError):
            conditions.evaluate(definition["recipes"][0]["condition"], context)
        definition["recipes"][0]["condition"]["values"].append({"type": "unit:unknown"})
        with self.assertRaises(AssertionError):
            conditions.condition_tags(definition["recipes"][0]["condition"])

    def test_modes_and_musket_and_empty_tag_are_independent_inputs(self):
        _, result = fixture_and_result()
        context = result["conditionContext"]
        for namespace in ("basepower", "basesteam", "baseelectric"):
            self.assertTrue(conditions.evaluate({"type": namespace + ":recipe_mode", "mode": "NORMAL"}, context))
            self.assertFalse(conditions.evaluate({"type": namespace + ":recipe_mode", "mode": "APOCALYPTIC"}, context))
        self.assertFalse(conditions.evaluate({"type": "baseelectric:not_recipe_mode", "mode": "NORMAL"}, context))
        self.assertTrue(conditions.evaluate({"type": "basepower:tag_empty", "tag": "forge:ingots/refined_iron"}, context))
        context["musketEnabled"] = False
        self.assertFalse(conditions.evaluate({"type": "basesteam:musket_enabled"}, context))

    def test_actual_native_forge_empty_and_semantics_are_true(self):
        source = native.RIG / ".gradle/mavenizer/repo/net/minecraftforge/forge/26.3-66.0.0/forge-26.3-66.0.0-sources.jar"
        native.validate_hash(source, "4571742629CD247753B109B272C8D6BBC583E6D3D5B2E79FA7DEC1EA09D25F8E")
        with ZipFile(source) as archive:
            primary = archive.read("net/minecraftforge/common/crafting/conditions/AndCondition.java").decode()
        self.assertIn('ICondition.CODEC.listOf().fieldOf("values")', primary)
        self.assertIn("return true;", primary)
        self.assertTrue(conditions.evaluate({"type": "forge:and", "values": []}, {}))

    def test_persist_and_restore_keep_all_original_checklist_and_login_gates(self):
        for operation, label in (("persist", "normal-persist"), ("restore", "normal-restart-restore")):
            definition, result = fixture_and_result(True)
            result.update(operation=operation, label=label, additionalMaterials=[], nativeForgeLoginEvent=True)
            self.assertEqual(1, conditions.validate_unlock(result, definition, operation)["passed"])
            result["cases"][0]["discovered"] = False
            with self.assertRaises(AssertionError):
                conditions.validate_unlock(result, definition, operation)
            result["cases"][0]["discovered"] = True
            if operation == "persist": result["additionalMaterials"] = ["unit:missing_kit_item"]
            else: result["nativeForgeLoginEvent"] = False
            with self.assertRaises(AssertionError):
                conditions.validate_unlock(result, definition, operation)

    def test_future_mode_progress_is_earned_and_restored_even_while_recipe_inactive(self):
        definition, result = fixture_and_result(False)
        result.update(operation="restore", label="normal-restart-restore", nativeForgeLoginEvent=True)
        self.assertEqual(1, conditions.validate_unlock(result, definition, "restore")["advancementOnly"])
        result["cases"][0]["discovered"] = False
        with self.assertRaises(AssertionError):
            conditions.validate_unlock(result, definition, "restore")

    def test_skip_requires_no_ingredient_provider_not_a_false_recipe_condition(self):
        definition, result = fixture_and_result(False, False)
        self.assertEqual(1, conditions.validate_unlock(result, definition, "sweep")["skipped"])
        result["cases"][0]["status"] = "PASS"
        with self.assertRaises(AssertionError):
            conditions.validate_unlock(result, definition, "sweep")

    def packaged(self, *, mutate=None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "unit.jar"
        definition = {"recipes": [{"id": "basepower:unit", "ingredients": {"material": {"tag": "forge:ingots/iron"}}}]}
        sources = {"basepower:unit": {"jar": str(path), "recipe": "recipe", "advancement": "advancement"}}
        resources = {"recipe": {"forge:condition": tag("ingots/iron")}, "advancement": {
            "criteria": {"material": {"trigger": "minecraft:inventory_changed"}, "has_recipe": {
                "trigger": "minecraft:recipe_unlocked", "conditions": {"recipes": "#basepower:discovery/unit"}}},
            "requirements": [["material", "has_recipe"]], "rewards": {"recipes": ["basepower:unit"]}},
            "data/basepower/tags/recipe/discovery/unit.json": {"values": [{"id": "basepower:unit", "required": False}]}}
        if mutate:
            mutate(resources)
        with ZipFile(path, "w") as archive:
            for name, data in resources.items(): archive.writestr(name, json.dumps(data))
        with patch.object(conditions.historical, "recipe_fixture", return_value=(definition, sources)):
            return conditions.recipe_fixture([path])

    def test_packaged_v2_fixture_preserves_condition_and_exact_optional_tag(self):
        definition, sources = self.packaged()
        self.assertEqual(tag("ingots/iron"), definition["recipes"][0]["condition"])
        self.assertEqual("basepower:discovery/unit", definition["recipes"][0]["recipeTriggerTag"])
        self.assertEqual(64, len(sources["basepower:unit"]["recipeTriggerTagSha256"]))

    def test_packaged_conditioned_advancement_or_wrong_optional_tag_fails(self):
        tag_path = "data/basepower/tags/recipe/discovery/unit.json"
        mutations = [lambda x: x["advancement"].update({"forge:condition": tag("ingots/iron")}),
                     lambda x: x[tag_path]["values"][0].update(required=True),
                     lambda x: x[tag_path]["values"][0].update(id="basepower:wrong"),
                     lambda x: x[tag_path].update(replace=True),
                     lambda x: x[tag_path]["values"].append({"id": "basepower:extra", "required": False}),
                     lambda x: x["advancement"]["criteria"]["has_recipe"]["conditions"].update(recipes=["basepower:unit"]),
                     lambda x: x["advancement"]["requirements"].append(["material"]),
                     lambda x: x["advancement"]["rewards"].update(recipes=["basepower:wrong"])]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                self.packaged(mutate=mutation)

    def test_packaged_recipe_advancement_condition_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unit.jar"
            with ZipFile(path, "w") as archive:
                archive.writestr("recipe", json.dumps({"forge:condition": tag("ingots/iron")}))
                archive.writestr("advancement", json.dumps({"forge:condition": tag("ingots/iron")}))
            definition = {"recipes": [{"id": "basepower:unit"}]}
            sources = {"basepower:unit": {"jar": str(path), "recipe": "recipe", "advancement": "advancement"}}
            with patch.object(conditions.historical, "recipe_fixture", return_value=(definition, sources)), self.assertRaises(AssertionError):
                conditions.recipe_fixture([path])

    def test_actual_failed_family02_conditioned_advancements_cannot_qualify_v2(self):
        profile = Path("D:/PowerAdvantage/_verification/base-263-port-20261112/native263-local-candidate-02.json")
        native.validate_hash(profile, "F0263165E2668462CD7A56ABA3F04EA12A7E59C9A379B57B33ED433953F3FC41")
        data = json.loads(profile.read_text())
        for row in data["mods"]:
            native.validate_hash(Path(row["path"]), row["sha256"])
        with self.assertRaises(AssertionError):
            conditions.recipe_fixture([row["path"] for row in data["mods"]])


if __name__ == "__main__":
    unittest.main()
