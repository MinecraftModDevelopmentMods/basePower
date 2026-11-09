"""Run recipe discovery and survival progression against the 1.19.4 player jars."""

import argparse
from pathlib import Path

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_recipes import MOD_COUNTS, RecipePruning, progression_campaign, recipe_fixture, unlock_campaign
import native119


class RecipeServer(RecipePruning, native119.Server):
    @staticmethod
    def errors(text):
        return native119.log_findings(text)[0]


class Engine:
    version = (native119.MINECRAFT, native119.FORGE)
    pack_format = 9
    server = native119.Server
    recipe_server = RecipeServer
    result_name = "native119-recipes-results.json"

    @staticmethod
    def stage(runtime, target, port, campaign):
        launcher = native119.stage(runtime, target, port)
        (runtime / ("advantage-recipes.allow" if campaign == "unlock" else "advantage-progression.allow")).touch()
        if campaign == "progression":
            (runtime / "advantage-works.allow").touch()
            (runtime / "migration-operate.allow").touch()
        return launcher


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java17"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, default=25650)
    parser.add_argument("--campaign", choices=("all", "unlock", "progression"), default="all")
    args = parser.parse_args()
    target = native119.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    root = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, *(item["path"] for item in target[2].values())):
        require_disjoint(root, path)
    definition, sources = recipe_fixture([item["path"] for item in target[2].values()])
    entry = {"jars": [{**item, "path": str(item["path"])} for item in target[2].values()]}
    before = fingerprint(target[0])
    report = dict(status="RUNNING", minecraft=native119.MINECRAFT, forge=native119.FORGE, campaign=args.campaign,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(args.java17), target_jars=entry["jars"], recipe_inventory=MOD_COUNTS,
                  unlock_boots=[], progression_cases=[])
    write_json(root / "packaged-recipe-sources.json", sources)
    try:
        if args.campaign in ("all", "unlock"):
            unlock_campaign(root, target, entry, args.java17, args.port, definition, report, engine=Engine)
            if len(report["unlock_boots"]) != 5:
                raise AssertionError("Incomplete 1.19.4 unlock campaign")
        if args.campaign in ("all", "progression"):
            progression_campaign(root, target, entry, args.java17, args.port, report, engine=Engine)
            if len(report["progression_cases"]) != 18:
                raise AssertionError("Incomplete 1.19.4 progression campaign")
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        report["seed_unchanged"] = before == fingerprint(target[0])
        if not report["seed_unchanged"]:
            report["status"] = "FAIL"
        write_json(root / Engine.result_name, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
