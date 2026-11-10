"""Native 1.17 recipe exports and progression on new hash-pinned disposable servers.

Historical campaign modules supply assertion data only. No legacy profile, seed,
world, artifact fallback or historical result is used as native runtime evidence.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import socket
import zipfile

import nbtlib

from matrix import WORLD, digest, fingerprint, stage, write_json
from native117_code import Server
from native117 import TEST_FLAT, stage
from native117 import RIG, output_path, pinned_profile, require_clean_log, require_disjoint, runtime_error_lines
from native117_identity import fixture_ids
from native117_readiness import await_ready
from progression import fixture as progression_fixture, validate_tags
from recipe_unlocks import MATERIALS


MOD_COUNTS = {"basepower": 35, "basesteam": 24, "baseelectric": 50}
RECIPE_TYPES = {"minecraft:crafting_shaped", "minecraft:crafting_shapeless", "minecraft:smelting"}
UNLOCK_BOOTS = (
    ("NORMAL", "normal", ("sweep", "persist")),
    ("NORMAL", "normal-restart", ("restore",)),
    ("TECH_PROGRESSION", "tech", ("restore", "sweep", "persist")),
    ("APOCALYPTIC", "apocalyptic", ("restore", "sweep", "persist")),
    ("NORMAL", "normal-return", ("restore",)),
)
MODES = ("NORMAL", "TECH_PROGRESSION", "APOCALYPTIC")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def recipe_fixture(jars, *, component_predicates=False):
    recipes = []
    sources = {}
    for path in jars:
        with zipfile.ZipFile(path) as jar:
            for mod in MOD_COUNTS:
                prefix = f"data/{mod}/recipes/"
                names = sorted(name for name in jar.namelist() if name.startswith(prefix)
                               and name.endswith(".json") and "/" not in name[len(prefix):])
                for name in names:
                    recipe = json.loads(jar.read(name))
                    if recipe["type"] not in RECIPE_TYPES:
                        continue
                    stem = Path(name).stem
                    advancement_name = f"data/{mod}/advancements/recipes/{stem}.json"
                    advancement = json.loads(jar.read(advancement_name))
                    predicates = {}
                    for key, criterion in advancement["criteria"].items():
                        if criterion["trigger"] != "minecraft:inventory_changed":
                            continue
                        items = criterion["conditions"]["items"]
                        if len(items) != 1 or set(items[0]) not in ({"items"}, {"tag"}):
                            raise AssertionError(f"Unsupported inventory predicate: {advancement_name}/{key}")
                        if "items" in items[0]:
                            choices = items[0]["items"]
                            if component_predicates and isinstance(choices, str) and choices.startswith("#") and len(choices) > 1:
                                predicates[key] = {"tag": choices[1:]}
                                continue
                            if (not isinstance(choices, list) or len(choices) != 1
                                    or not isinstance(choices[0], str) or not choices[0]):
                                raise AssertionError(f"Expected one explicit native item: {advancement_name}/{key}")
                            predicates[key] = {"item": choices[0]}
                        else:
                            predicates[key] = items[0]
                    if not predicates:
                        raise AssertionError(f"No ingredient-discovery predicates: {advancement_name}")
                    identity = f"{mod}:{stem}"
                    if identity in sources:
                        raise AssertionError(f"Duplicate packaged recipe: {identity}")
                    sources[identity] = {"jar": str(path), "recipe": name, "advancement": advancement_name}
                    recipes.append({"id": identity, "ingredients": predicates})
    counts = Counter(row["id"].split(":")[0] for row in recipes)
    if counts != MOD_COUNTS:
        raise AssertionError(f"Packaged recipe inventory changed: {dict(counts)}; expected {MOD_COUNTS}")
    return {"recipes": recipes, "materials": fixture_ids(MATERIALS)}, sources


def native_target(manifest):
    entry = read_json(manifest)["target117"]
    required = {"advantageworkstest", "basepower", "basesteam", "baseelectric", "basemetals", "orespawn"}
    if {jar["id"] for jar in entry["jars"]} != required:
        raise ValueError("This campaign requires exactly the native harness and five packaged mods")
    target = pinned_profile(manifest, "target117")
    if any("deobf" in path.name.lower() for path in target[2]):
        raise ValueError("Use packaged native jars, not development candidates")
    return target


def require_free_port(port):
    with socket.socket() as check:
        check.bind(("127.0.0.1", port))


def stage_native(runtime, target, port, campaign):
    require_free_port(port)
    launcher = stage(runtime, target)
    # Replace only the disposable stage's legacy generator and port defaults.
    props = dict(line.split("=", 1) for line in (runtime / "server.properties").read_text().splitlines() if "=" in line)
    props.update({"server-port": str(port), "generator-settings": "", "level-type": TEST_FLAT})
    (runtime / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in props.items()), encoding="utf-8")
    marker = "advantage-recipes.allow" if campaign == "unlock" else "advantage-progression.allow"
    (runtime / marker).touch()
    if campaign == "progression":
        (runtime / "advantage-works.allow").touch()
    if digest(runtime / launcher) != digest(target[0] / launcher):
        raise AssertionError("Staged launcher differs from native input")
    for jar in target[2]:
        if digest(runtime / "mods" / jar.name) != digest(jar):
            raise AssertionError(f"Staged mod differs from pinned input: {jar}")
    if (runtime / WORLD).exists():
        raise AssertionError("Staging must not import an existing world")
    return launcher


def configure(runtime, mode, rubber=True):
    config = runtime / "config/basepower-common.toml"
    config.parent.mkdir(exist_ok=True)
    config.write_text(f'[options]\nrecipeMode = "{mode}"\nplasticEqualsRubber = {str(rubber).lower()}\n', encoding="utf-8")


def validate_unlock(result, definition, operation):
    expected = {row["id"] for row in definition["recipes"]}
    rows = result["cases"]
    if (result["status"] != "PASS" or len(rows) != len(expected)
            or {row["id"] for row in rows} != expected or result["potatoCraftingCases"] != 59):
        raise AssertionError("Incomplete native unlock/potato-crafting report")
    if not result["bookRecipes"]:
        raise AssertionError("Native server exported no recipe-book recipes")
    if operation == "persist" and result["additionalMaterials"]:
        raise AssertionError("Human materials checklist has gaps: " + str(result["additionalMaterials"]))
    if operation == "restore" and result.get("nativeForgeLoginEvent") is not True:
        raise AssertionError("Saved-book restoration did not dispatch the real native Forge login event")
    allowed = {"PASS", "SKIP_NO_INGREDIENT_PROVIDER"}
    if any(row["status"] not in allowed for row in rows):
        raise AssertionError("Failed native discovery case")
    return {"cases": len(rows), "active": sum(row["recipeActive"] for row in rows),
            "passed": sum(row["status"] == "PASS" for row in rows),
            "skipped": sum(row["status"] != "PASS" for row in rows), "potatoCraftingCases": 59}


def validate_progression(result, mode, rubber, *, verify_global_loot=False):
    validate_tags(result, mode, rubber, power="basepower", electric="baseelectric")
    if result["mode"] != mode or result["plasticEqualsRubber"] != rubber:
        raise AssertionError("Native progression ignored its requested mode/rubber configuration")
    if result["status"] != "PASS" or result["blocked"] != 0 or result["blockedRecipes"]:
        raise AssertionError("Native manufacture blocked: " + str(result.get("blockedRecipes")) + "; "
                             + str([row["id"] for row in result["targets"] if row["status"] == "BLOCKED"]))
    if not result["targets"] or not result["reachable"] or not result["executedRecipes"] or not result["processWitnesses"]:
        raise AssertionError("Empty native manufacture evidence")
    if not result["lootDataPackOverrideVerified"]:
        raise AssertionError("Player loot override was not preserved")
    if verify_global_loot and result.get("lootOverrideWithModifiersVerified") is not True:
        raise AssertionError("Missing gameplay loot-modifier observation")


def verify_level(runtime, entry, minecraft="1.17.1", forge="37.1.1"):
    level_path = runtime / WORLD / "level.dat"
    level = nbtlib.load(level_path)
    if str(level["Data"]["Version"]["Name"]) != minecraft:
        raise AssertionError(f"Server did not write a native {minecraft} level header")
    mods = {str(row["ModId"]): str(row["ModVersion"]) for row in level["fml"]["LoadingModList"]}
    if mods.get("forge") != forge or any(mods.get(jar["id"]) != jar["version"] for jar in entry["jars"]):
        raise AssertionError(f"Saved native mod identities differ from the target manifest: {mods}")
    registry = level["fml"]["Registries"]
    if any(not registry.get(name, {}).get("ids") for name in ("minecraft:item", "minecraft:block")):
        raise AssertionError("Missing native item/block registry snapshot; legacy plural registry names are not valid")
    return {"path": str(level_path), "sha256": digest(level_path), "minecraft": minecraft, "mods": mods}


def audit_unlock_log(path, previous_recipes, current_recipes, errors=runtime_error_lines):
    """Witness vanilla's exact mode-conditional saved-book pruning, not arbitrary errors."""
    removed = set(previous_recipes) - set(current_recipes)
    observed = set()
    events = []
    for original in errors(path.read_text(encoding="utf-8", errors="replace")):
        line = re.sub(r"\x1b\[[0-9;]*m", "", original)
        match = re.search(r"\[Server thread/ERROR\] \[(?:minecraft/ServerRecipeBook|net\.minecraft\.stats\.ServerRecipeBook/?)\]: Tried to load unrecognized recipe: ([a-z0-9_]+:[a-z0-9_./-]+) removed now\.$", line)
        if not match or match[1] not in removed:
            raise AssertionError(f"Unexpected native recipe runtime error: {original}")
        observed.add(match[1])
        events.append(original)
    if observed != removed:
        raise AssertionError(f"Saved-book pruning did not match the native packet-set difference: expected {sorted(removed)}, saw {sorted(observed)}")
    return {"expected_removed_ids": sorted(removed), "observed_removed_ids": sorted(observed), "events": events}


class RecipePruning:
    errors = staticmethod(runtime_error_lines)

    def __init__(self, runtime, java, launcher, label, previous):
        self.previous = set(previous)
        self.current = None
        self.pruning = {}
        super().__init__(runtime, java, launcher, label)

    def validate_native_log(self, path):
        if self.current is None:
            return super().validate_native_log(path)
        self.pruning[path.name] = audit_unlock_log(path, self.previous, self.current, self.errors)


class RecipeServer(RecipePruning, Server):
    pass


def unlock_campaign(root, target, entry, java, port, definition, report, *, engine=None):
    stage_runtime = stage_native if engine is None else engine.stage
    server_type = RecipeServer if engine is None else engine.recipe_server
    version = ("1.17.1", "37.1.1") if engine is None else engine.version
    result_name = "native117-recipes-results.json" if engine is None else engine.result_name
    runtime = root / "unlock"
    launcher = stage_runtime(runtime, target, port, "unlock")
    write_json(runtime / "recipe-fixture.json", definition)
    previous_recipes = set()
    for mode, label, operations in UNLOCK_BOOTS:
        configure(runtime, mode)
        row = {"mode": mode, "label": label, "operations": [], "status": "RUNNING"}
        report["unlock_boots"].append(row)
        write_json(root / result_name, report)
        print(f"{version[0]} unlock boot {label}: {', '.join(operations)}", flush=True)
        server = server_type(runtime, java, launcher, label, previous_recipes)
        try:
            for operation in operations:
                name = f"{label}-{operation}"
                server.send(f"advrecipes {operation} {name}")
                server.wait(f"ADVRECIPES COMPLETE {operation} {name}", 120)
                result = read_json(runtime / f"recipes-{name}.json")
                counts = validate_unlock(result, definition, operation)
                packet = runtime / f"recipes-{name}.bin"
                if not packet.is_file() or packet.stat().st_size == 0:
                    raise AssertionError("Missing native server recipe packet")
                row["operations"].append({"operation": operation, "report": f"recipes-{name}.json",
                                          "packet_sha256": digest(packet), **counts})
            server.current = {recipe["id"] for recipe in result["bookRecipes"]}
            server.send("save-all flush")
        finally:
            server.close()
        current_recipes = {recipe["id"] for recipe in result["bookRecipes"]}
        if set(server.pruning) != {f"{kind}-{label}.log" for kind in ("console", "latest", "debug")}:
            raise AssertionError("Recipe pruning was not verified in all three native logs")
        row["vanilla_recipe_pruning"] = server.pruning
        previous_recipes = current_recipes
        verify_level(runtime, entry, *version)
        row["status"] = "PASS"
    artifacts = [runtime / f"recipes-{mode}-sweep.{extension}" for mode in ("normal", "tech", "apocalyptic")
                 for extension in ("bin", "json")]
    report["recipe_fixture"] = {"directory": str(runtime), "registry": verify_level(runtime, entry, *version),
                                "files": [{"file": path.name, "sha256": digest(path)} for path in artifacts]}


def prepare_progression(server, runtime, bounds, label, reload, minecraft="1.17.1"):
    if reload:
        server.send("reload")
        server.wait("Reloading!", 60)
        server.wait(" advancements", 120)
    low, high = bounds["min"], bounds["max"]
    server.send(f"forceload add {low[0]} {low[2]} {high[0]} {high[2]}")
    chunks = (low[0] // 16, low[2] // 16, high[0] // 16, high[2] // 16)
    readiness = "progression-" + label
    await_ready(server, runtime, chunks, readiness, minecraft=minecraft)
    return runtime / "advantage-works-results" / f"native-readiness-{readiness}.json"


def progression_campaign(root, target, entry, java, port, report, *, engine=None):
    stage_runtime = stage_native if engine is None else engine.stage
    server_type = Server if engine is None else engine.server
    version = ("1.17.1", "37.1.1") if engine is None else engine.version
    result_name = "native117-recipes-results.json" if engine is None else engine.result_name
    pack_format = 7 if engine is None else engine.pack_format
    runtime = root / "progression"
    launcher = stage_runtime(runtime, target, port, "progression")
    definition = fixture_ids(progression_fixture())
    write_json(runtime / "progression-fixture.json", definition)
    pack = runtime / WORLD / "datapacks/progression-override"
    write_json(pack / "pack.mcmeta", {"pack": {"pack_format": pack_format, "description": "Disposable loot-override witness"}})
    write_json(pack / "data/minecraft/loot_tables/chests/simple_dungeon.json", {
        "pools": [{"name": "test_override", "rolls": 1, "entries": [{"type": "item", "name": "minecraft:dirt"}]}]})
    for mode in MODES:
        for rubber in (True, False):
            configure(runtime, mode, rubber)
            for phase in ("initial", "restart"):
                label = mode.lower() + ("-rubber" if rubber else "-no-rubber") + "-" + phase
                print(f"{version[0]} progression boot {label}", flush=True)
                server = server_type(runtime, java, launcher, label)
                try:
                    for reload in ((False, True) if phase == "initial" else (False,)):
                        name = label + ("-reload" if reload else "")
                        row = {"mode": mode, "rubber": rubber, "phase": "reload" if reload else phase,
                               "report": f"progression-{name}.json", "status": "RUNNING"}
                        report["progression_cases"].append(row)
                        write_json(root / result_name, report)
                        readiness = prepare_progression(server, runtime, definition["machineBounds"], name, reload, version[0])
                        row["readiness"] = {"file": str(readiness.relative_to(runtime)), "sha256": digest(readiness)}
                        server.send(f"advprogression {name}")
                        server.wait(f"ADVPROGRESSION COMPLETE {name}", 240)
                        result = read_json(runtime / row["report"])
                        validate_progression(result, mode, rubber, verify_global_loot=engine is not None)
                        row.update(status="PASS", targets=len(result["targets"]),
                                   witnesses=len(result["processWitnesses"]), blocked=result["blocked"])
                    server.send("save-all flush")
                finally:
                    server.close()
                require_clean_log(runtime / f"console-{label}.log")
                verify_level(runtime, entry, *version)


def run(args):
    manifest = args.target_manifest.resolve()
    target = native_target(manifest)
    entry = read_json(manifest)["target117"]
    root = output_path(args.output)
    for protected in (manifest, target[0], *target[2]):
        require_disjoint(root, protected)
    java = args.java8.resolve()
    if not java.is_file() or not re.search(r'(?m)^JAVA_VERSION="16(?:\.|\+|")', (java.parent.parent / "release").read_text()):
        raise ValueError("Supply an existing Java 16 bin/java.exe")
    require_free_port(args.port)
    definition, sources = recipe_fixture(target[2])
    before = fingerprint(target[0])
    root.mkdir(parents=True, exist_ok=False)
    report = {"status": "RUNNING", "minecraft": "1.17.1", "forge": "37.1.1", "campaign": args.campaign,
              "target_manifest": str(manifest), "target_manifest_sha256": digest(manifest),
              "target_jars": entry["jars"], "java": str(java), "java_sha256": digest(java),
              "unlock_boots": [], "progression_cases": [], "recipe_inventory": dict(MOD_COUNTS),
              "scope": "packaged native server assertions and recipe exports; not graphical/manual acceptance"}
    write_json(root / "packaged-recipe-sources.json", sources)
    write_json(root / "seed-fingerprint-before.json", before)
    try:
        if args.campaign in ("all", "unlock"):
            unlock_campaign(root, target, entry, java, args.port, definition, report)
        if args.campaign in ("all", "progression"):
            progression_campaign(root, target, entry, java, args.port, report)
        if args.campaign in ("all", "unlock") and len(report["unlock_boots"]) != 5:
            raise AssertionError("The native unlock campaign did not complete five boots")
        if args.campaign in ("all", "progression") and len(report["progression_cases"]) != 18:
            raise AssertionError("The native progression campaign did not complete eighteen checks")
        native_target(manifest)
        if digest(manifest) != report["target_manifest_sha256"]:
            raise AssertionError("Pinned manifest changed during the campaign")
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", error=f"{type(failure).__name__}: {failure}")
        raise
    finally:
        after = fingerprint(target[0])
        write_json(root / "seed-fingerprint-after.json", after)
        report["seed_unchanged"] = before == after
        if before != after:
            report.update(status="FAIL", seed_error="Protected installed server seed changed")
        write_json(root / "native117-recipes-results.json", report)
        if before != after:
            raise AssertionError("Protected installed server seed changed")
    print(f"Native117 recipe campaign PASS: {root}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-manifest", type=Path, required=True)
    parser.add_argument("--java16", "--java8", dest="java8", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=RIG / "build" / ("native117-recipes-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")))
    parser.add_argument("--port", type=int, default=25577)
    parser.add_argument("--campaign", choices=("all", "unlock", "progression"), default="all")
    run(parser.parse_args())


if __name__ == "__main__":
    main()
