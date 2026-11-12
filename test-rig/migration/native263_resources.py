"""Native66 recipe/resource adapters; historical engines and assertions stay unchanged.

Unit/preflight success is not packaged-game or graphical qualification.
"""

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import shutil
import types

import nbtlib

from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native117_recipes as recipes
import native263 as native
import native263_layout as layout


FAMILY = frozenset(native.VERSIONS)
MODES = ("NORMAL", "TECH_PROGRESSION", "APOCALYPTIC")
PACK_METADATA = {"min_format": [121, 0], "max_format": [121, 0],
                 "description": "Disposable loot-override witness"}
RESULT = "native263-resources-results.json"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def bind(function, **bindings):
    """Copy an existing assertion/campaign with explicit globals, never monkey-patch it."""
    result = types.FunctionType(function.__code__, dict(function.__globals__, **bindings),
                                function.__name__, function.__defaults__, function.__closure__)
    result.__kwdefaults__ = function.__kwdefaults__
    return result


def artifact(root, record):
    root = Path(root).resolve(strict=True)
    relative = Path(record["file"])
    require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe retained artifact path")
    path = root / relative
    for part in (path, *path.parents):
        require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(), "Aliased retained artifact")
    path = path.resolve(strict=True)
    require(path.is_relative_to(root) and path.is_file() and path.stat().st_nlink == 1,
            "Retained artifact must be an independent in-root file")
    native.validate_hash(path, record["sha256"])
    require(path.stat().st_size > 0, "Empty retained artifact")
    return path


def pixel_pair(root, record, *, matches=True):
    width, height = record["width"], record["height"]
    require(type(width) is int and type(height) is int and 1 <= width <= 4096 and 1 <= height <= 4096,
            "Invalid raw pixel dimensions")
    actual, reference = artifact(root, record["actualRgba"]), artifact(root, record["referenceRgba"])
    require(actual != reference, "Pixel reference must be independently retained")
    left, right = actual.read_bytes(), reference.read_bytes()
    require(len(left) == len(right) == width * height * 4, "Truncated RGBA extraction")
    require(any(left[3::4]) and len(set(left)) > 1, "Blank rendered pixels")
    different = sum(left[index:index + 4] != right[index:index + 4] for index in range(0, len(left), 4))
    require((different == 0) is matches, "Independent rendered pixel comparison failed")
    return different


@dataclass
class Inputs:
    target: tuple
    java: Path
    java_version: str
    hashes: dict
    seed_before: dict

    @property
    def mods(self):
        return self.target[2]

    def unchanged(self):
        require(fingerprint(self.target[0]) == self.seed_before, "Protected SDK seed changed")
        for path, expected in self.hashes.items():
            native.validate_hash(Path(path), expected)
        return True

    def receipt(self):
        return dict(engineProtocol=native.ENGINE_PROTOCOL, minecraft=native.MINECRAFT, forge=native.FORGE, mcp=native.MCP,
                    javaVersion=self.java_version, inputs=dict(self.hashes),
                    target_jars=[dict(id=key, version=version, path=str(path), sha256=sha)
                                 for key, (version, path, sha) in sorted(self.mods.items())])


def preflight(args, helpers=()):
    require((native.MINECRAFT, native.FORGE, native.MCP, native.JAVA_VERSION)
            == ("26.3", "66.0.0", "20260918.230105", "25.0.3+9"), "Wrong core SDK contract")
    native.validate_hash(args.profile, args.profile_sha256)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    require(set(target[2]) == FAMILY, "Require the exact six-jar native66 family")
    java, version = native.java25(args.java)
    root = output_path(args.output)
    sources = [args.profile, args.inventory, java, Path(__file__), Path(native.__file__), Path(layout.__file__),
               *helpers, *(value[1] for value in target[2].values())]
    for path in [target[0], *sources]:
        require_disjoint(root, Path(path).resolve(strict=True))
    return Inputs(target, java, version, {str(Path(path).resolve()): digest(Path(path)) for path in sources},
                  fingerprint(target[0]))


def verify_saved_level(runtime, entry, minecraft="26.3", forge="66.0.0"):
    require((minecraft, forge) == (native.MINECRAFT, native.FORGE), "Historical saved-level reader requested")
    world = Path(runtime) / WORLD
    data = layout.read_level(world)
    require(str(data["Version"]["Name"]) == native.MINECRAFT, "Wrong stopped native level version")
    root = layout._load(world / "level.dat")
    fml = root["fml"]
    versions = {}
    for row in fml["LoadingModList"]:
        key, value = str(row["ModId"]), str(row["ModVersion"])
        require(key not in versions, "Duplicate stopped mod identity")
        versions[key] = value
    expected = {row["id"]: row["version"] for row in entry["jars"]}
    require(len(expected) == len(entry["jars"]) and set(expected) == FAMILY,
            "Stopped recipe proof needs all six exact jars")
    expected.update(minecraft=native.MINECRAFT, forge=native.FORGE)
    require(versions == expected, "Stopped native mod identities differ from frozen family")
    registries = fml["Registries"]
    for key in ("minecraft:item", "minecraft:block"):
        require(isinstance(registries[key]["ids"], nbtlib.List) and len(registries[key]["ids"]) > 0,
                "Missing native stopped registry: " + key)
    return dict(path=str(world / "level.dat"), sha256=digest(world / "level.dat"),
                mods=versions, dataVersion=layout.DATA_VERSION)


class RecipeServer(recipes.RecipePruning, native.Server):
    errors = staticmethod(lambda text: native.log_findings(text)[0])

    @staticmethod
    def unexpected_line(line):
        loading_failure = re.search(r"\[[^\]]+/(?:ERROR|FATAL)\]", line) and any(marker in line for marker in (
            "Caught exception during event", "Failed to complete lifecycle event", "Failed to start the minecraft server"))
        return bool(loading_failure) or native.Server.unexpected_line(line)


class Engine:
    version = (native.MINECRAFT, native.FORGE)
    pack_metadata = PACK_METADATA
    require_clean_log = staticmethod(native.require_clean_log)
    loot_directory = "loot_table"
    server = native.Server
    recipe_server = RecipeServer
    result_name = RESULT

    @staticmethod
    def stage(runtime, target, port, campaign):
        require(campaign in ("unlock", "progression"), "Unknown native recipe campaign")
        native.stage(runtime, *target, port)
        (runtime / ("advantage-recipes.allow" if campaign == "unlock" else "advantage-progression.allow")).touch()
        if campaign == "progression":
            for marker in ("advantage-works.allow", "migration-operate.allow"):
                (runtime / marker).touch()
        return native.LAUNCHER


def validate_progression(result, mode, rubber, *, verify_global_loot=False):
    recipes.validate_progression(result, mode, rubber, verify_global_loot=verify_global_loot)
    merchant = result.get("merchantOffersNative66", {})
    require(merchant.get("protocol") == "NATIVE66_LOADED_MERCHANT_CODEC_OFFERS_V1"
            and merchant.get("status") == "PASS" and merchant.get("negativeControlsRejected") == 1,
            "Missing actual native merchant codec/offer and plural-modifier negative control")
    rows = merchant.get("cases", [])
    require(len(rows) == 2 and {row["trade"] for row in rows} == {
        "basepower:emerald_sprockets", "basepower:emerald_bioplastic"}, "Incomplete native merchant offers")
    for row in rows:
        require(row.get("status") == "PASS" and row.get("samples") == 128
                and row.get("counts") == (list(range(1, 5)) if row["trade"].endswith("sprockets") else list(range(4, 9))),
                "Native merchant item/count modifiers did not execute")


unlock_campaign = bind(recipes.unlock_campaign, verify_level=verify_saved_level)
progression_campaign = bind(recipes.progression_campaign, verify_level=verify_saved_level, validate_progression=validate_progression)


def verify_packet_exports(runtime):
    rows = []
    for mode in ("normal", "tech", "apocalyptic"):
        packet = runtime / f"recipes-{mode}-sweep.bin"
        manifest_file = packet.with_suffix(".json")
        manifest = native.read_json(manifest_file)
        require(manifest["bookPacketType"] == "ClientboundRecipeBookAddPacket"
                and manifest["tagsPacketType"] == "ClientboundUpdateTagsPacket"
                and type(manifest["itemTagCount"]) is int and manifest["itemTagCount"] > 0,
                "Missing native recipe display/item-tag packet protocol")
        name = manifest["tagsPacketFile"]
        require(isinstance(name, str) and Path(name).name == name and name.startswith("tags-")
                and name.endswith(".bin"), "Unsafe native tag packet path")
        tags = runtime / name
        native.validate_hash(tags, manifest["tagsPacketSha256"])
        require(packet.stat().st_size > 0 and tags.stat().st_size > 0, "Empty native recipe/tag packet")
        rows.append(dict(mode=mode, packet=str(packet), sha256=digest(packet),
                         manifestSha256=digest(manifest_file), tags=str(tags), tagsSha256=digest(tags)))
    return rows


def run(args):
    require(args.campaign in ("all", "unlock", "progression"), "Unknown native recipe campaign")
    inputs = preflight(args, [Path(recipes.__file__)])
    native.require_free_port(args.port)
    root = output_path(args.output)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, **inputs.receipt())
    definition, sources = recipes.recipe_fixture([value[1] for value in inputs.mods.values()],
                                                 component_predicates=True, singular_directories=True)
    root.mkdir(parents=True, exist_ok=False)
    shutil.copy2(Path(__file__), root / "native263-resources-source.py")
    report = dict(status="RUNNING", scope="PACKAGED_RECIPE_SERVER_NOT_GRAPHICAL_ACCEPTANCE",
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  **inputs.receipt(), recipe_inventory=recipes.MOD_COUNTS, unlock_boots=[], progression_cases=[])
    entry = {"jars": report["target_jars"]}
    expected = {path.name: sha for _, path, sha in inputs.mods.values()}
    write_json(root / "packaged-recipe-sources.json", sources)
    try:
        if args.campaign in ("all", "unlock"):
            unlock_campaign(root, inputs.target, entry, inputs.java, args.port, definition, report, engine=Engine)
            require(len(report["unlock_boots"]) == 5 and all(row["status"] == "PASS" for row in report["unlock_boots"]),
                    "Incomplete native66 unlock boots")
            report["packet_exports"] = verify_packet_exports(root / "unlock")
        if args.campaign in ("all", "progression"):
            progression_campaign(root, inputs.target, entry, inputs.java, args.port, report, engine=Engine)
            required = {(mode, rubber, phase) for mode in MODES for rubber in (True, False)
                        for phase in ("initial", "reload", "restart")}
            actual = {(row["mode"], row["rubber"], row["phase"]) for row in report["progression_cases"]}
            require(len(report["progression_cases"]) == 18 and actual == required
                    and all(row["status"] == "PASS" for row in report["progression_cases"]),
                    "Incomplete native66 three-mode progression matrix")
        report["status"] = "PASS"
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        try:
            for campaign in ("unlock", "progression"):
                if (root / campaign).exists():
                    native.verify_runtime(root / campaign, inputs.target[1], expected)
            report["sourceUnchanged"] = inputs.unchanged()
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure))
            raise
        finally:
            write_json(root / RESULT, report)
    return report


def arguments(description=__doc__):
    parser = argparse.ArgumentParser(description=description)
    for key in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + key, type=Path, required=True)
    for key in ("profile-sha256", "inventory-sha256"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--preflight-only", action="store_true")
    return parser


def main():
    parser = arguments()
    parser.add_argument("--campaign", choices=("all", "unlock", "progression"), default="all")
    print(run(parser.parse_args())["status"], flush=True)


if __name__ == "__main__":
    main()
