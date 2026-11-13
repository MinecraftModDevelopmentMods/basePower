"""Resource-only native66 fixture conditions and exact loaded-pair verification."""

import hashlib
import json
import re
from zipfile import ZipFile

import native117_recipes as historical

PROTOCOL = "NATIVE66_INDEPENDENT_CONDITIONAL_PAIR_V1"
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
        require(recipe.get("forge:condition") == advancement.get("forge:condition"),
                "Packaged recipe/advancement conditions differ: " + row["id"])
        condition = recipe.get("forge:condition")
        condition_tags(condition)
        row["condition"] = condition
        source.update(recipeSha256=hashlib.sha256(raw_recipe).hexdigest().upper(),
                      advancementSha256=hashlib.sha256(raw_advancement).hexdigest().upper())
    definition["conditionProtocol"] = PROTOCOL
    return definition, sources


def validate_unlock(result, definition, operation):
    counts = historical.validate_unlock(result, definition, operation)
    require(definition.get("conditionProtocol") == result.get("conditionProtocol") == PROTOCOL,
            "Missing independent native condition/pair protocol")
    require(result.get("operation") == operation, "Wrong native recipe operation")
    expected_labels = {f"{label}-{op}": mode for mode, label, operations in historical.UNLOCK_BOOTS for op in operations}
    context = result["conditionContext"]
    require(set(context) == {"mode", "musketEnabled", "tags"}
            and context["mode"] == expected_labels.get(result.get("label"))
            and type(context["musketEnabled"]) is bool, "Native condition context differs from requested boot")
    tags = set().union(*(condition_tags(row["condition"]) for row in definition["recipes"]))
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
        require(all(type(row.get(key)) is bool and row[key] == expected
                    for key in ("conditionExpected", "recipeActive", "advancementActive")),
                "Loaded recipe/advancement pair differs from independent fixture condition: " + fixture["id"])
        require((fixture["id"] in book_ids) == expected, "Native recipe-book loaded presence differs from condition")
        require(row["status"] == ("PASS" if expected else "SKIP_NO_INGREDIENT_PROVIDER"),
                "Active recipe skipped or inactive recipe claimed as exercised")
        if expected and operation == "sweep":
            require(type(row.get("positiveCriteria")) is int and row["positiveCriteria"] > 0
                    and type(row.get("tagMembers")) is int and row["tagMembers"] >= row["positiveCriteria"],
                    "Active recipe lacks actual positive native criteria/member witnesses")
        elif expected:
            require(row.get("discovered") is True, "Active recipe discovery was not retained")
        else:
            require(row.get("discovered") is False, "Absent conditional advancement claimed discovered")
    return counts
