"""Resource-only native66 fixture conditions and exact loaded-pair verification."""

import hashlib
import json
import re
from zipfile import ZipFile

import native117_recipes as historical

PROTOCOL = "NATIVE66_OPTIONAL_RECIPE_TAG_DISCOVERY_V2"
MODES = {"NORMAL", "TECH_PROGRESSION", "APOCALYPTIC"}
IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
MODE_TYPES = {"basepower:recipe_mode", "basesteam:recipe_mode", "baseelectric:recipe_mode",
              "baseelectric:not_recipe_mode"}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def condition_tags(condition):
    if condition is None:
        return set()
    require(type(condition) is dict and type(condition.get("type")) is str, "Malformed fixture condition")
    kind = condition["type"]
    if kind == "forge:and":
        require(set(condition) == {"type", "values"} and type(condition["values"]) is list
                and all(child is not None for child in condition["values"]),
                "Malformed AND condition")
        return set().union(*(condition_tags(child) for child in condition["values"]))
    if kind in {"basepower:tag_empty", "basepower:tag_not_empty"}:
        require(set(condition) == {"type", "tag"} and type(condition["tag"]) is str
                and IDENTIFIER.fullmatch(condition["tag"]), "Malformed tag condition")
        return {condition["tag"]}
    if kind in MODE_TYPES:
        require(set(condition) == {"type", "mode"} and type(condition["mode"]) is str
                and condition["mode"] in MODES, "Malformed mode condition")
        return set()
    require(kind == "basesteam:musket_enabled" and set(condition) == {"type"},
            "Unsupported fixture condition: " + kind)
    return set()


def evaluate(condition, context):
    condition_tags(condition)
    if condition is None:
        return True
    kind = condition["type"]
    if kind == "forge:and":
        # Evaluate every branch so false early operands cannot conceal missing facts.
        return all([evaluate(child, context) for child in condition["values"]])
    if kind.startswith("basepower:tag_"):
        require(condition["tag"] in context["tags"], "Missing independent tag facts")
        return (len(context["tags"][condition["tag"]]) == 0) == (kind == "basepower:tag_empty")
    if kind == "basesteam:musket_enabled":
        require(type(context["musketEnabled"]) is bool, "Missing actual musket configuration")
        return context["musketEnabled"]
    require(context["mode"] in MODES, "Unknown actual recipe mode")
    return (context["mode"] == condition["mode"]) != (kind == "baseelectric:not_recipe_mode")


def recipe_fixture(jars):
    definition, sources = historical.recipe_fixture(jars, component_predicates=True, singular_directories=True)
    for row in definition["recipes"]:
        source = sources[row["id"]]
        with ZipFile(source["jar"]) as archive:
            raw_recipe, raw_advancement = archive.read(source["recipe"]), archive.read(source["advancement"])
        recipe, advancement = json.loads(raw_recipe), json.loads(raw_advancement)
        require("forge:condition" not in advancement and "conditions" not in advancement,
                "Discovery advancement must remain loaded in every recipe mode: " + row["id"])
        namespace, stem = row["id"].split(":")
        trigger_tag = f"{namespace}:discovery/{stem}"
        criterion = advancement["criteria"]["has_recipe"]
        require(criterion == {"trigger": "minecraft:recipe_unlocked", "conditions": {"recipes": "#" + trigger_tag}},
                "Recipe-unlocked criterion must name its exact optional discovery tag: " + row["id"])
        require(advancement["rewards"]["recipes"] == [row["id"]], "Recipe reward differs from exact variant")
        names = set(advancement["criteria"])
        require(names == set(row["ingredients"]) | {"has_recipe"}
                and len(advancement["requirements"]) == 1
                and len(advancement["requirements"][0]) == len(names)
                and set(advancement["requirements"][0]) == names, "Discovery criteria must retain their complete OR group")
        tag_path = f"data/{namespace}/tags/recipe/discovery/{stem}.json"
        with ZipFile(source["jar"]) as archive:
            raw_tag = archive.read(tag_path)
        tag = json.loads(raw_tag)
        require(set(tag) in ({"values"}, {"values", "replace"}) and tag.get("replace", False) is False
                and type(tag.get("values")) is list and len(tag["values"]) == 1
                and type(tag["values"][0]) is dict and set(tag["values"][0]) == {"id", "required"}
                and tag["values"][0]["id"] == row["id"] and tag["values"][0]["required"] is False,
                "Discovery tag must contain only its exact optional recipe reference: " + row["id"])
        condition = recipe.get("forge:condition")
        condition_tags(condition)
        row["condition"] = condition
        row["recipeTriggerTag"] = trigger_tag
        source.update(recipeSha256=hashlib.sha256(raw_recipe).hexdigest().upper(),
                      advancementSha256=hashlib.sha256(raw_advancement).hexdigest().upper(),
                      recipeTriggerTag=tag_path, recipeTriggerTagSha256=hashlib.sha256(raw_tag).hexdigest().upper())
    definition["conditionProtocol"] = PROTOCOL
    return definition, sources


def validate_unlock(result, definition, operation):
    projected = dict(result, cases=[dict(row, status="PASS") if row.get("status") == "ADVANCEMENT_ONLY" else row
                                    for row in result["cases"]])
    counts = historical.validate_unlock(projected, definition, operation)
    require(definition.get("conditionProtocol") == result.get("conditionProtocol") == PROTOCOL,
            "Missing independent native condition/pair protocol")
    require(result.get("operation") == operation, "Wrong native recipe operation")
    expected_labels = {f"{label}-{op}": mode for mode, label, operations in historical.UNLOCK_BOOTS for op in operations}
    context = result["conditionContext"]
    require(set(context) == {"mode", "musketEnabled", "tags"}
            and context["mode"] == expected_labels.get(result.get("label"))
            and type(context["musketEnabled"]) is bool, "Native condition context differs from requested boot")
    tags = set().union(*(condition_tags(row["condition"]) for row in definition["recipes"]))
    tags.update(value["tag"] for row in definition["recipes"] for value in row["ingredients"].values() if "tag" in value)
    require(set(context["tags"]) == tags, "Incomplete or extraneous condition tag facts")
    for members in context["tags"].values():
        require(type(members) is list and all(type(item) is str and IDENTIFIER.fullmatch(item) for item in members)
                and members == sorted(set(members)), "Corrupt native condition tag members")
    cases = {row["id"]: row for row in result["cases"]}
    book_ids = [row["id"] for row in result["bookRecipes"]]
    require(len(book_ids) == len(set(book_ids)), "Duplicate native recipe book entries")
    for fixture in definition["recipes"]:
        row = cases[fixture["id"]]
        expected = evaluate(fixture["condition"], context)
        require(all(type(row.get(key)) is bool and row[key] == expected for key in ("conditionExpected", "recipeActive"))
                and row.get("advancementActive") is True,
                "Recipe condition or always-loaded advancement contract failed: " + fixture["id"])
        require(row.get("recipeTriggerTag") == fixture["recipeTriggerTag"]
                and row.get("recipeTriggerTagMembers") == ([fixture["id"]] if expected else []),
                "Native discovery tag must contain exactly the active recipe or be empty")
        require(row.get("recipeConditionStatus") == ("CONDITION_ENABLED" if expected else "CONDITION_DISABLED"),
                "Recipe condition status differs from independent condition")
        require((fixture["id"] in book_ids) == expected, "Native recipe-book loaded presence differs from condition")
        available = any("item" in value or bool(context["tags"][value["tag"]]) for value in fixture["ingredients"].values())
        require(row.get("ingredientProviderAvailable") is available, "Ingredient-provider facts disagree")
        require(row["status"] == (("PASS" if expected else "ADVANCEMENT_ONLY") if available else "SKIP_NO_INGREDIENT_PROVIDER"),
                "Earnable discovery skipped or absent ingredient provider claimed as exercised")
        if available and operation == "sweep":
            require(type(row.get("positiveCriteria")) is int and row["positiveCriteria"] > 0
                    and type(row.get("tagMembers")) is int and row["tagMembers"] >= row["positiveCriteria"],
                    "Active recipe lacks actual positive native criteria/member witnesses")
        if available:
            require(row.get("discovered") is True, "Earned advancement discovery was not retained")
    counts["passed"] = sum(row["status"] == "PASS" for row in result["cases"])
    counts["advancementOnly"] = sum(row["status"] == "ADVANCEMENT_ONLY" for row in result["cases"])
    return counts
