"""Sample native normal-world deposits and provider/import policy across a full restart.

Requires a frozen family profile; never changes OreSpawn sources or protected inputs.
"""

import argparse
import copy
from pathlib import Path
import re
import shutil
import traceback

import nbtlib

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native12111 as native


CAMPAIGNS = ("advantage", "legacy-import", "mineralogy")
WORLD = "AdvantageWorksWorldgen"
BLOCKS = {"basepower:crude_oil", "baseelectric:li_ore", "baseelectric:sulfur_ore",
          "mineralogy:crude_oil", "mineralogy:sulfur_ore"}
OILS = {"basepower:crude_oil", "mineralogy:crude_oil"}
MINERALOGY = dict(version="6.1.4.121111", sourceRef="d27b800100213c8166a24a7196adf22d05a99bab",
                  sha256="2ADE7BEE0ACD5B1463752B3D14A01E27D3FFA5448E9407BA8F41DFAFBCA0D954")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class WorldgenServer(native.Server):
    @staticmethod
    def unexpected_line(line):
        return native.Server.unexpected_line(line) or any(marker in line for marker in (
            "Worldgen sample failed", "Advantage Works command failed"))


def campaign_spec(name):
    if name not in CAMPAIGNS:
        raise ValueError("Unknown worldgen campaign: " + name)
    template = native.RIG / "profiles/native1201" / f"worldgen-{name}.json"
    original = native.read_json(template)
    require(original["name"] == "worldgen-" + name and original["levelType"] == "minecraft:normal"
            and original["buildWorks"] is False, "Expected the existing normal-world scenario")
    keys = ("name", "levelSeed", "worldgenSampleRadius", "firstProfileAssertions", "restartProfileAssertions",
            "profileMutations", "firstFileAssertions", "restartFileAssertions", "worldgenExpectations",
            "stableWorldgenCountExclusions")
    spec = {key: copy.deepcopy(original.get(key, [])) for key in keys}
    spec["requiredMods"] = {row["id"] for row in original["mods"]}
    spec["template"] = template
    spec["configFixtures"] = []
    for row in original.get("configFixtures", []):
        destination = Path(row["destination"])
        if destination.name == "poweradvantage.json":
            source = native.RIG.parent / "src/test/resources/orespawn-legacy/poweradvantage.json"
        elif destination.name == "electricadvantage.json":
            source = native.RIG.parent.parent / "ElectricAdvantage/ElectricAdvantage/src/test/resources/orespawn-legacy/electricadvantage.json"
        else:
            raise ValueError("Unreviewed legacy worldgen fixture: " + str(destination))
        spec["configFixtures"].append((source.resolve(strict=True), destination))
    return spec


def select_mods(mods, spec):
    missing = spec["requiredMods"] - mods.keys()
    if missing:
        raise ValueError("Frozen profile lacks worldgen inputs: " + ", ".join(sorted(missing)))
    # Base Metals is part of the frozen family, but does not own sulfur/lithium fallbacks.
    selected = spec["requiredMods"] | ({"basemetals"} & mods.keys())
    return {key: value for key, value in mods.items() if key in selected}


def mineralogy_input(mods, path):
    if path is None:
        require("mineralogy" in mods, "Mineralogy campaign needs the separately pinned public Mineralogy jar")
        value = mods["mineralogy"]
        require(value[0] == MINERALOGY["version"] and value[2].upper() == MINERALOGY["sha256"],
                "Mineralogy profile does not pin the approved public artifact")
    else:
        value = (MINERALOGY["version"], path.resolve(strict=True), MINERALOGY["sha256"])
    native.validate_hash(value[1], MINERALOGY["sha256"])
    native.jar_identity(value[1], "mineralogy", MINERALOGY["version"])
    return {**mods, "mineralogy": value}


def freeze_mineralogy_profile(runtime, profile, profile_sha256, mods):
    frozen = runtime / "frozen-inputs" / mods["mineralogy"][1].name
    frozen.parent.mkdir()
    shutil.copy2(mods["mineralogy"][1], frozen)
    native.validate_hash(frozen, MINERALOGY["sha256"])
    derived = native.read_json(profile)
    derived["mods"] = [row for row in derived["mods"] if row["id"] != "mineralogy"]
    derived["mods"].append(dict(id="mineralogy", version=MINERALOGY["version"], file=frozen.name,
                                path=str(frozen), sha256=MINERALOGY["sha256"]))
    derived["sourceProfileSha256"] = profile_sha256.upper()
    derived["mineralogySourceRef"] = MINERALOGY["sourceRef"]
    derived_path = runtime / "frozen-mineralogy-profile.json"
    write_json(derived_path, derived)
    return dict(path=str(derived_path), sha256=digest(derived_path),
                jar=dict(path=str(frozen), **MINERALOGY))


def configure_normal_world(runtime, spec, port):
    path = runtime / "server.properties"
    overrides = {"level-name": WORLD, "level-type": "minecraft:normal", "generator-settings": "",
                 "level-seed": spec["levelSeed"], "generate-structures": "true", "server-port": str(port),
                 "pause-when-empty-seconds": "-1"}
    require(all(isinstance(value, str) and "\n" not in value and "\r" not in value
                for value in overrides.values()), "Unsafe normal-world property")
    lines = [line for line in path.read_text(encoding="utf-8").splitlines()
             if line.partition("=")[0] not in overrides]
    path.write_text("\n".join(lines + [f"{key}={value}" for key, value in overrides.items()]) + "\n", encoding="utf-8")


def validate_profile(document, assertions):
    require(document.get("schema_version") == 7, "Expected the pinned native OreSpawn schema 7 profile")
    rows = []
    missing = object()
    for assertion in assertions:
        section = document.get(assertion["section"], {})
        require(isinstance(section, dict), "Profile section is not an object")
        value = section.get(assertion["entry"], missing)
        if assertion.get("field"):
            value = value.get(assertion["field"], missing) if isinstance(value, dict) else missing
        present = value is not missing
        require(present == assertion["present"], "Wrong profile presence: " + assertion["id"])
        if present:
            for field, kind in (("expectedBoolean", bool), ("expectedString", str), ("expectedNumber", (int, float))):
                if field in assertion:
                    require(isinstance(value, kind) and not (field == "expectedNumber" and isinstance(value, bool))
                            and value == assertion[field], "Wrong profile value: " + assertion["id"])
        rows.append(dict(id=assertion["id"], actual=value if present else None, status="PASS"))
    return rows


def validate_initial_hierarchy(document, mods, legacy=False):
    if legacy:
        return
    ores, deposits = document["ores"], document["fluid_deposits"]
    require(deposits["basepower:fluid_deposit/desert_crude_oil"]["source_provider"] == "basepower",
            "Desert oil lost its Power provider identity")
    mineralogy, minerals = "mineralogy" in mods, "baseminerals" in mods
    for material, enabled in (("sulfur", not mineralogy and not minerals), ("lithium", not minerals)):
        rule = ores["baseelectric:ore/" + material]
        require(rule["source_provider"] == "baseelectric" and rule["enabled"] is enabled,
                "Wrong fresh-world sulfur/lithium provider hierarchy: " + material)
    if mineralogy:
        require(deposits["mineralogy:fluid_deposit/crude_oil"]["source_provider"] == "mineralogy",
                "Ocean oil lost its Mineralogy provider identity")


def mutated_profile(document, mutations):
    result = copy.deepcopy(document)
    for mutation in mutations:
        entry = result[mutation["section"]][mutation["entry"]]
        require(isinstance(entry, dict) and mutation["field"] in entry, "Mutation must target an existing profile field")
        values = [mutation[key] for key in ("booleanValue", "stringValue", "numberValue") if key in mutation]
        require(len(values) == 1, "Profile mutation must declare exactly one value")
        entry[mutation["field"]] = values[0]
    return result


def validate_sample(document, spec, mods):
    require((document.get("minecraft"), document.get("forge"), document.get("station"), document.get("kind"))
            == (native.MINECRAFT, native.FORGE, "worldgen", "sample"), "Missing native completed worldgen receipt")
    require(all(document.get("mods", {}).get(key) == value[0] for key, value in mods.items()),
            "Sampler mod versions differ from the frozen inputs")
    radius = spec["worldgenSampleRadius"]
    require(document.get("radius") == radius, "Wrong worldgen sampling radius")
    scanned = document.get("chunksScanned")
    require(type(scanned) is int and 0 < scanned <= 3 * (2 * radius + 1) ** 2, "Invalid sampled chunk count")
    centers = document.get("centers", {})
    for biome in (("DESERT", "OCEAN") if "mineralogy" in mods else ("DESERT",)):
        require(isinstance(centers.get(biome), str) and re.fullmatch(r"-?\d+,-?\d+", centers[biome]),
                "Required native biome was not found: " + biome)
    for field, keys in (("counts", BLOCKS), ("biomeViolations", OILS), ("biomeBoundaryBlocks", OILS)):
        values = document.get(field)
        require(isinstance(values, dict) and set(values) == keys
                and all(type(value) is int and value >= 0 for value in values.values()),
                "Incomplete or invalid worldgen measurements: " + field)
    expectations = spec["worldgenExpectations"]
    for key, minimum in expectations["minimumCounts"].items():
        require(document["counts"][key] >= minimum, "Missing actual generated deposit: " + key)
    for key, maximum in expectations["maximumCounts"].items():
        require(document["counts"][key] <= maximum, "Unexpected provider output: " + key)
    if expectations["zeroBiomeViolations"]:
        require(not any(document["biomeViolations"].values()), "Oil generated outside its allowed native biome")
    loading = document.get("chunkLoading", {})
    require(loading.get("protocol") == "native_FULL_off_thread_future_poll_v1"
            and loading.get("maximumInFlight") == 4 and type(loading.get("peakInFlight")) is int
            and 0 < loading["peakInFlight"] <= 4 and loading.get("activeTickets") == 0
            and type(loading.get("requests")) is int and loading["requests"] >= scanned * 2
            and loading.get("releasedTickets") == loading["requests"]
            and loading.get("dispatcherClosed") is True and loading.get("executorShutdown") is True,
            "Native FULL chunk sampling did not release all bounded tickets and its dispatcher")


def validate_restart(first, restart, spec):
    require(first["centers"] == restart["centers"] and first["chunksScanned"] == restart["chunksScanned"],
            "Worldgen sampling coverage moved across restart")
    exclusions = set(spec["stableWorldgenCountExclusions"])
    require(exclusions <= {"basepower:crude_oil"} and
            (not exclusions or spec["name"] == "worldgen-legacy-import"), "Unreviewed restart count exclusion")
    for field in ("counts", "biomeViolations"):
        require({key: value for key, value in first[field].items() if key not in exclusions}
                == {key: value for key, value in restart[field].items() if key not in exclusions},
                "Generated deposit measurements changed across full restart: " + field)


def normal_world(data):
    require(data.get("DataVersion") == 4671 and data.get("LevelName") == WORLD,
            "Expected a stopped native 1.21.11 world")
    settings = data["WorldGenSettings"]
    generator = settings["dimensions"]["minecraft:overworld"]["generator"]
    require(generator.get("type") == "minecraft:noise" and generator.get("settings") == "minecraft:overworld",
            "Worldgen must exercise actual normal terrain, not the flat machine fixture")
    require(type(settings.get("seed")) is int, "Missing saved native seed")
    return copy.deepcopy(settings)


def check_files(runtime, assertions):
    for row in assertions:
        path = (runtime / row["path"]).resolve()
        require(path.is_relative_to(runtime.resolve()), "File assertion escaped the disposable runtime")
        require(path.is_file(), "Missing import witness: " + row["id"])
        if row.get("contains") is not None:
            require(path.read_text(encoding="utf-8").count(row["contains"]) >= row["minimumOccurrences"],
                    "Incomplete import witness: " + row["id"])


def run(args):
    if not 1024 <= args.port <= 65535 or not 300 <= args.sample_timeout <= 3600:
        raise ValueError("Use an unprivileged port and a bounded 300..3600-second sample deadline")
    native.validate_hash(args.profile, args.profile_sha256)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    spec = campaign_spec(args.campaign)
    candidates = mineralogy_input(target[2], args.mineralogy) if args.campaign == "mineralogy" else target[2]
    mods = select_mods(candidates, spec)
    runtime = output_path(args.output)
    protected_paths = [args.profile, args.inventory, java, spec["template"], Path(__file__).resolve(),
                       *(value[1] for value in candidates.values()), *(source for source, _ in spec["configFixtures"])]
    for path in [args.seed, *protected_paths]:
        require_disjoint(runtime, path)
    hashes = {str(path): digest(path) for path in protected_paths}
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", campaign=args.campaign, minecraft=native.MINECRAFT,
                    forge=native.FORGE, inputs=hashes, selectedMods=sorted(mods))
    seed_before = fingerprint(args.seed)
    expected = {value[1].name: value[2].upper() for value in mods.values()}
    report = dict(status="RUNNING", qualificationStatus=native.EXPLORATORY, campaign=args.campaign,
                  minecraft=native.MINECRAFT, forge=native.FORGE, javaVersion=version,
                  inputs=hashes, jars=expected, phases={}, sampleTimeoutSeconds=args.sample_timeout)
    receipt = runtime / "native12111-worldgen-results.json"
    server, failed = None, False
    try:
        native.stage(runtime, target[0], target[1], mods, args.port)
        shutil.copy2(Path(__file__), runtime / "adapter-source.py")
        if args.campaign == "mineralogy":
            report["mineralogyProfile"] = freeze_mineralogy_profile(runtime, args.profile, args.profile_sha256, mods)
            hashes[report["mineralogyProfile"]["jar"]["path"]] = MINERALOGY["sha256"]
        configure_normal_world(runtime, spec, args.port)
        for source, destination in spec["configFixtures"]:
            path = runtime / destination
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, path)
        write_json(receipt, report)
        first, world_settings, pids = None, None, []
        profile_file = runtime / WORLD / "serverconfig/orespawn-worldgen.json"
        for phase in ("initial", "restart"):
            print(f"{args.campaign} {phase}: starting pinned normal-world server", flush=True)
            native.verify_runtime(runtime, target[1], expected)
            server = WorldgenServer(runtime, java, native.LAUNCHER, "worldgen-" + phase)
            pids.append(server.process.pid)
            require(len(set(pids)) == len(pids), "Restart must be a new server process")
            server.send("gamerule doMobSpawning false")
            server.send("advworks sample-worldgen " + str(spec["worldgenSampleRadius"]))
            server.wait("Worldgen sample wrote ", args.sample_timeout)
            sample_file = runtime / "advantage-works-results/worldgen-sample.json"
            sample = native.read_json(sample_file)
            shutil.copy2(sample_file, runtime / f"worldgen-{phase}.json")
            validate_sample(sample, spec, mods)
            print(f"{args.campaign} {phase}: sampled {sample['chunksScanned']} chunks; {sample['counts']}", flush=True)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
            server.close()
            server = None
            level = runtime / WORLD / "level.dat"
            shutil.copy2(level, runtime / f"level-{phase}.dat")
            settings = normal_world(nbtlib.load(level)["Data"].unpack())
            profile = native.read_json(profile_file)
            write_json(runtime / f"orespawn-profile-{phase}.json", profile)
            prefix = "first" if phase == "initial" else "restart"
            assertions = validate_profile(profile, spec[prefix + "ProfileAssertions"])
            check_files(runtime, spec[prefix + "FileAssertions"])
            for source, destination in spec["configFixtures"]:
                require(digest(runtime / destination) == hashes[str(source)], "Legacy import rewrote its original source")
            if first is None:
                validate_initial_hierarchy(profile, mods, legacy=args.campaign == "legacy-import")
                first, world_settings = sample, settings
                if spec["profileMutations"]:
                    write_json(profile_file, mutated_profile(profile, spec["profileMutations"]))
            else:
                validate_restart(first, sample, spec)
                require(settings == world_settings, "Saved normal-world settings changed across restart")
            report["phases"][phase] = dict(status="PASS", pid=pids[-1], profileAssertions=assertions,
                                           sampleSha256=digest(runtime / f"worldgen-{phase}.json"),
                                           stoppedLevelSha256=digest(runtime / f"level-{phase}.dat"))
            write_json(receipt, report)
        report["status"] = "PASS"
    except BaseException as error:
        failed = True
        report.update(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if server is not None:
                server.close()
            if runtime.exists():
                native.verify_runtime(runtime, target[1], expected)
            report["seedUnchanged"] = fingerprint(args.seed) == seed_before
            require(report["seedUnchanged"], "Protected Forge seed changed")
            require(all(digest(Path(path)) == sha for path, sha in hashes.items()), "Frozen source input changed")
        except BaseException as error:
            report.update(status="FAIL", custodyError=repr(error))
            if not failed:
                raise
        finally:
            if runtime.exists():
                write_json(receipt, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--profile-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--campaign", required=True, choices=CAMPAIGNS)
    parser.add_argument("--sample-timeout", type=int, default=1800)
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--mineralogy", type=lambda value: Path(value).resolve(),
                        help="Approved public Mineralogy jar; copied and pinned separately in campaign evidence")
    result = run(parser.parse_args())
    print(result["status"], result["campaign"], flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
