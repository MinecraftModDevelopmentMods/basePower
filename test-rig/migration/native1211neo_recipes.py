"""Exercise live recipe discovery and progression against pinned NeoForge 1.21.1 player jars."""

import argparse
from pathlib import Path
import nbtlib

from matrix import WORLD, digest, fingerprint, write_json
from native117_actor_io import bind
from native117 import output_path, require_disjoint
from native117_recipes import (
    MOD_COUNTS, RecipePruning, progression_campaign, recipe_fixture as historical_recipe_fixture,
    require_free_port, unlock_campaign,
)
import native1201
import native1211neo


def recipe_fixture(jars, **options):
    definition, sources = historical_recipe_fixture(jars, **options)
    # Published Base Metals uses vanilla copper for its common copper tag.
    definition["materials"] = list(dict.fromkeys([*definition["materials"], "minecraft:copper_ingot"]))
    return definition, sources


def registry_exports(runtime):
    exports = []
    for report in sorted(runtime.glob('recipes-*.json')):
        document = native1211neo.read_json(report)
        row = document['registrySnapshot']
        packet = report.with_suffix('.bin')
        snapshot = report.with_suffix('.registry.bin')
        if (document.get('status') != 'PASS' or row.get('schema') != 1
                or row.get('minecraftVersion') != native1211neo.MINECRAFT
                or row.get('neoForgeVersion') != native1211neo.NEOFORGE
                or row.get('snapshotType') != 'SYNC_TO_CLIENT' or row.get('sameRegistries') is not True
                or row.get('file') != snapshot.name or row.get('packetFile') != packet.name
                or type(row.get('registryCount')) is not int or row['registryCount'] <= 0
                or row.get('recipeCount') != len(document['bookRecipes'])):
            raise AssertionError('Invalid per-packet native registry export: ' + report.name)
        for path, size, sha in ((packet, row['packetBytes'], row['packetSha256']),
                                (snapshot, row['bytes'], row['sha256'])):
            if type(size) is not int or size <= 0 or path.stat().st_size != size:
                raise AssertionError('Missing or truncated native export: ' + path.name)
            native1211neo.validate_hash(path, sha)
        exports.append(dict(report=report.name, reportSha256=digest(report), **row))
    if not exports:
        raise AssertionError('Missing per-packet NeoForge registry handshake exports')
    return exports


def verify_level(runtime, entry, minecraft, neoforge):
    path = runtime / WORLD / "level.dat"
    level = nbtlib.load(path)
    if str(level["Data"]["Version"]["Name"]) != minecraft:
        raise AssertionError("Server did not write the native Minecraft version")
    mods = {str(row["ModId"]): str(row["ModVersion"]) for row in level["fml"]["LoadingModList"]}
    expected = {jar["id"]: jar["version"] for jar in entry["jars"]}
    expected.update(minecraft=minecraft, neoforge=neoforge)
    if mods != expected:
        raise AssertionError(f"Saved NeoForge identities differ from the pinned inputs: {mods} != {expected}")
    return dict(path=str(path), sha256=digest(path), minecraft=minecraft, mods=mods,
                registryExports=registry_exports(runtime) if runtime.name == 'unlock' else [])


unlock_campaign = bind(unlock_campaign, verify_level=verify_level)
progression_campaign = bind(progression_campaign, verify_level=verify_level)


class RecipeServer(RecipePruning, native1211neo.Server):
    @staticmethod
    def errors(text):
        return native1201.log_findings(text)[0]


class Engine:
    version = (native1211neo.MINECRAFT, native1211neo.FORGE)
    pack_format = 48
    loot_directory = "loot_table"
    server = native1211neo.Server
    recipe_server = RecipeServer
    result_name = "native1211neo-recipes-results.json"

    @staticmethod
    def stage(runtime, target, port, campaign):
        native1211neo.stage(runtime, *target, port)
        (runtime / ("advantage-recipes.allow" if campaign == "unlock" else "advantage-progression.allow")).touch()
        if campaign == "progression":
            (runtime / "advantage-works.allow").touch()
            (runtime / "migration-operate.allow").touch()
        return native1211neo.LAUNCHER


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--campaign", choices=("all", "unlock", "progression"), default="all")
    args = parser.parse_args()
    target = native1211neo.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    seed, inventory, mods = target
    if set(mods) != {"basepower", "basesteam", "baseelectric", "advantageworkstest", "basemetals", "orespawn", "mmdlib"}:
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
    report = dict(status="RUNNING", minecraft=native1211neo.MINECRAFT, neoforge=native1211neo.NEOFORGE,
                  campaign=args.campaign, profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(args.java), target_jars=entries["jars"], recipe_inventory=MOD_COUNTS,
                  unlock_boots=[], progression_cases=[])
    write_json(root / "packaged-recipe-sources.json", sources)
    try:
        if args.campaign in ("all", "unlock"):
            unlock_campaign(root, target, entries, args.java, args.port, definition, report, engine=Engine)
            if len(report["unlock_boots"]) != 5:
                raise AssertionError("Incomplete NeoForge 1.21.1 unlock campaign")
            native1211neo.verify_runtime(root / "unlock", inventory, expected)
        if args.campaign in ("all", "progression"):
            progression_campaign(root, target, entries, args.java, args.port, report, engine=Engine)
            if len(report["progression_cases"]) != 18:
                raise AssertionError("Incomplete NeoForge 1.21.1 progression campaign")
            native1211neo.verify_runtime(root / "progression", inventory, expected)
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
