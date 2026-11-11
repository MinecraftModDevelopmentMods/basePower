"""Exercise live recipe discovery and progression against pinned Forge 61 player jars."""

import argparse
from pathlib import Path

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_recipes import (
    MOD_COUNTS, RecipePruning, progression_campaign, recipe_fixture, require_free_port, unlock_campaign,
)
import native1201
import native12111


class RecipeServer(RecipePruning, native12111.Server):
    @staticmethod
    def errors(text):
        return native1201.log_findings(text)[0]


class Engine:
    version = (native12111.MINECRAFT, native12111.FORGE)
    pack_format = 48
    loot_directory = "loot_table"
    server = native12111.Server
    recipe_server = RecipeServer
    result_name = "native12111-recipes-results.json"

    @staticmethod
    def stage(runtime, target, port, campaign):
        native12111.stage(runtime, *target, port)
        (runtime / ("advantage-recipes.allow" if campaign == "unlock" else "advantage-progression.allow")).touch()
        if campaign == "progression":
            (runtime / "advantage-works.allow").touch()
            (runtime / "migration-operate.allow").touch()
        return native12111.LAUNCHER


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--campaign", choices=("all", "unlock", "progression"), default="all")
    args = parser.parse_args()
    target = native12111.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    seed, inventory, mods = target
    if set(mods) != {"basepower", "basesteam", "baseelectric", "advantageworkstest", "basemetals", "orespawn"}:
        raise ValueError("The recipe and processing campaign needs the complete Base Metals profile")
    require_free_port(args.port)
    root = output_path(args.output)
    for path in (args.profile, seed, args.inventory, *(path for _, path, _ in mods.values())):
        require_disjoint(root, path)
    definition, sources = recipe_fixture([path for _, path, _ in mods.values()],
                                        component_predicates=True, singular_directories=True)
    entries = {"jars": [
        dict(id=mod, version=version, path=str(path), sha256=sha.upper())
        for mod, (version, path, sha) in mods.items()
    ]}
    expected = {path.name: sha.upper() for _, path, sha in mods.values()}
    before = fingerprint(seed)
    report = dict(status="RUNNING", minecraft=native12111.MINECRAFT, forge=native12111.FORGE,
                  campaign=args.campaign, profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(args.java), target_jars=entries["jars"], recipe_inventory=MOD_COUNTS,
                  unlock_boots=[], progression_cases=[])
    write_json(root / "packaged-recipe-sources.json", sources)
    try:
        if args.campaign in ("all", "unlock"):
            unlock_campaign(root, target, entries, args.java, args.port, definition, report, engine=Engine)
            if len(report["unlock_boots"]) != 5:
                raise AssertionError("Incomplete Forge 61 unlock campaign")
            native12111.verify_runtime(root / "unlock", inventory, expected)
        if args.campaign in ("all", "progression"):
            progression_campaign(root, target, entries, args.java, args.port, report, engine=Engine)
            if len(report["progression_cases"]) != 18:
                raise AssertionError("Incomplete Forge 61 progression campaign")
            native12111.verify_runtime(root / "progression", inventory, expected)
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        report["seed_unchanged"] = before == fingerprint(seed)
        if not report["seed_unchanged"]:
            report["status"] = "FAIL"
        write_json(root / Engine.result_name, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
