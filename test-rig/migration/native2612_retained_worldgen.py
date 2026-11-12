"""Preserve sealed retained rules, then sample genuinely new native64 normal chunks.

This module is deliberately outside the sealed actor/core/endurance import graph.
Positive fallback block counts never override a failed saved-rule contract.
"""

import copy
from pathlib import Path
import re
import shutil

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native2612 as native
import native2612_layout as layout
import native2612_worldgen as worldgen
from native2612_resources import arguments, preflight, require


MOD_ALIASES = {"electricadvantage": "baseelectric", "poweradvantage": "basepower", "steamadvantage": "basesteam"}
SECTIONS = ("ores", "fluid_deposits")
CONTROLS = ("manage_vanilla_ores", "suppress_all_ore_features", "place_fluid_deposits")
RESULT = "native2612-retained-worldgen-results.json"


def output_id(value):
    if value == "minecraft:quartz_ore":
        return "minecraft:nether_quartz_ore"
    if isinstance(value, str) and ":" in value:
        namespace, suffix = value.split(":", 1)
        return MOD_ALIASES.get(namespace, namespace) + ":" + suffix
    return value


def expected_rule(value):
    result = copy.deepcopy(value)
    for key in ("block", "deep_output"):
        if key in result:
            result[key] = output_id(result[key])
    for row in result.get("outputs", []):
        if isinstance(row, dict) and "block" in row:
            row["block"] = output_id(row["block"])
    return result


def differences(expected, actual, path=""):
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            return [dict(path=path, expected=expected, actual=actual)]
        result = []
        for key, value in expected.items():
            if key not in actual:
                result.append(dict(path=path + "/" + key, expected=value, missing=True))
            else:
                result.extend(differences(value, actual[key], path + "/" + key))
        return result
    if type(expected) is not type(actual) or expected != actual:
        return [dict(path=path, expected=expected, actual=actual)]
    return []


def active(rule):
    return rule.get("enabled", True) and any(row.get("enabled", True) and row.get("frequency", 1) > 0 for section in
        ("dimensions", "dimension_selectors") for row in rule.get(section, {}).values())


def audit_transition(source, destination, registry):
    issues = []
    for key in (*CONTROLS, "history", "migration_history", "user_notes"):
        if key in source:
            issues.extend(dict(kind="SOURCE_AUTHORITY", **row) for row in
                          differences({key: source[key]}, destination))
    for section in SECTIONS:
        original, actual = source.get(section, {}), destination.get(section, {})
        expected_outputs = set()
        for key, original_rule in original.items():
            if not isinstance(original_rule, dict):
                continue
            expected = expected_rule(original_rule)
            expected_output = expected.get("block", output_id(key))
            if expected_output.startswith(("baseelectric:", "basepower:")):
                expected_outputs.add(expected_output)
            if key not in actual:
                issues.append(dict(kind="MISSING_OR_RENAMED_RULE", section=section, rule=key))
                continue
            row = actual[key]
            issues.extend(dict(kind="RULE_PAYLOAD_CHANGED", section=section, rule=key, **delta)
                          for delta in differences(expected, row))
            actual_output = row.get("block", key)
            if actual_output not in registry:
                issues.append(dict(kind="UNKNOWN_OUTPUT", section=section, rule=key, output=actual_output))
        for key, row in actual.items():
            if key not in original and isinstance(row, dict) and active(row) and row.get("block", key) in expected_outputs:
                issues.append(dict(kind="NEW_ACTIVE_DEFAULT", section=section, rule=key, output=row.get("block", key)))
    return dict(status="FAIL" if issues else "PASS", issues=issues,
                ruleKeyPolicy="EXACT_SOURCE_KEYS_NO_SILENT_RENAME", positiveBlockCountsCannotWaiveFailure=True)


def os1_profile(document, current, legacy):
    """Independent projection of the frozen OS1 data contract, not target observations."""
    require(isinstance(document.get("dimensions"), list), "Expected sealed OS1 dimensions")
    result = dict(ores={}, fluid_deposits={})
    ordinal = 0
    clamp = lambda value, low, high: max(low, min(high, value))
    for dimension in document["dimensions"]:
        for ore in dimension.get("ores", []):
            block = ore["blockID"]
            require(block.startswith(legacy + ":") or block.startswith(current + ":"), "Unexpected OS1 provider owner")
            block = current + block[block.index(":"):]
            size, variation = int(ore.get("size", 8)), max(0, int(ore.get("variation", 0)))
            minimum = clamp(int(ore.get("minHeight", 0)), 0, 255)
            maximum = clamp(int(ore.get("maxHeight", 256)), 0, 256) - 1
            require(maximum >= minimum, "Invalid source OS1 bounds")
            placement = dict(enabled=True, min_y=minimum, max_y=maximum,
                frequency=max(0.0, float(ore.get("frequency", 0.5))), pattern="default", height_distribution="uniform",
                spread=8, vertical_spread=clamp(variation, 1, 64), node_size=clamp(size, 1, 32),
                host_blocks=["minecraft:stone"], host_tags=["forge:stone"],
                host_families=["sedimentary", "metamorphic", "igneous_intrusive", "igneous_volcanic"],
                biome_ids=[value for value in ore.get("biomes", []) if ":" in value], excluded_biome_ids=[],
                biome_dictionary=[value.upper() for value in ore.get("biomes", []) if re.fullmatch(r"[A-Za-z_]+", value)],
                excluded_biome_dictionary=[], geomes={})
            if variation:
                placement.update(min_quantity=clamp(size - variation, 1, 64), max_quantity=clamp(size + variation - 1, 1, 64))
            else:
                placement["quantity"] = clamp(size, 1, 64)
            rule = dict(block=block, enabled=True, native_generation=False, suppress_vanilla=False, retrogen=False)
            dimension_id = dimension["dimension"]
            if dimension_id == "+":
                rule["dimension_selectors"] = {"orespawn:all_except_nether_end": placement}
            else:
                name = {0: "minecraft:overworld", -1: "minecraft:the_nether", 1: "minecraft:the_end"}.get(dimension_id)
                require(name is not None, "Unreviewed OS1 dimension")
                rule["dimensions"] = {name: placement}
            safe = re.sub(r"[^a-z0-9_]+", "_", block.lower()).strip("_")
            result["ores"][current + ":legacy/" + safe + "_" + str(ordinal)] = rule
            ordinal += 1
    require(ordinal > 0, "Empty OS1 source oracle")
    return result


def require_new_world(world):
    world = Path(world)
    require(not any(world.rglob("*.mca")) and not (world / "level.dat").exists(),
            "New-chunk proof cannot contain preexisting save or region data")


def source_expectation(source):
    source = Path(source)
    for relative in ("AdvantageWorksMigration/serverconfig/orespawn-worldgen.json", "config/orespawn-worldgen.json"):
        path = source / relative
        if path.is_file():
            return native.read_json(path), dict(kind="RETAINED_PROFILE", path=str(path), sha256=digest(path))
    result, files = dict(ores={}, fluid_deposits={}), []
    for current, legacy in (("baseelectric", "electricadvantage"), ("basepower", "poweradvantage")):
        path = source / "config/orespawn" / (legacy + ".json")
        expected = os1_profile(native.read_json(path), current, legacy)
        result["ores"].update(expected["ores"])
        files.append(dict(path=str(path), sha256=digest(path)))
    return result, dict(kind="RAW_OS1_SOURCE_NO_TARGET_DERIVED_ORACLE", files=files)


def registered_blocks(world):
    layout.read_level(world)
    root = layout._load(Path(world) / "level.dat")
    rows = root["fml"]["Registries"]["minecraft:block"]["ids"]
    values = {str(row["K"]) for row in rows}
    require(len(values) == len(rows) and values, "Incomplete or duplicate actual native block registry")
    return values


def run(args):
    native.validate_hash(args.source_seal, args.source_seal_sha256)
    seal = native.read_json(args.source_seal)
    matches = [row for row in seal["routes"] if row["name"] == args.route]
    require(len(matches) == 1, "Expected an exact approved source route")
    route = matches[0]
    source = Path(route["source"]).resolve(strict=True)
    require(fingerprint(source) == route["sourceFingerprint"], "Sealed source fixture changed")
    expected_profile, oracle = source_expectation(source)
    helpers = [Path(__file__), args.source_seal, Path(worldgen.__file__),
               *(path for path in (source / "config").rglob("*") if path.is_file())]
    inputs = preflight(args, helpers)
    require_disjoint(Path(args.output).resolve(), source)
    require(1 <= args.radius <= 8 and 300 <= args.sample_timeout <= 3600, "Bound native sampling")
    native.require_free_port(args.port)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", oracle=oracle, sourceRoute=args.route, **inputs.receipt())
    runtime = output_path(args.output)
    expected_jars = native.stage(runtime, *inputs.target, args.port)
    for path in (source / "config").rglob("*"):
        if not path.is_file() or path.name == "fml.toml":
            continue
        target = runtime / "config" / path.relative_to(source / "config")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    world = runtime / worldgen.WORLD
    old_world_profile = source / "AdvantageWorksMigration/serverconfig/orespawn-worldgen.json"
    if old_world_profile.is_file():
        target = world / "serverconfig/orespawn-worldgen.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(old_world_profile, target)
    require_new_world(world)
    spec = worldgen.campaign_spec("advantage")
    spec["worldgenSampleRadius"] = args.radius
    enabled = {output_id(row.get("block", key)) for section in SECTIONS for key, row in expected_profile.get(section, {}).items()
               if active(row) and output_id(row.get("block", key)).startswith(("basepower:", "baseelectric:"))}
    spec["worldgenExpectations"] = dict(minimumCounts={key: 1 for key in enabled}, maximumCounts={}, zeroBiomeViolations=False)
    worldgen.configure_normal_world(runtime, spec, args.port)
    report = dict(status="RUNNING", sourceRoute=args.route, oracle=oracle, profileSha256=digest(args.profile),
        sourceSealSha256=digest(args.source_seal), sourceFingerprintBefore=route["sourceFingerprint"],
        newWorldBeforeFirstBoot=True, sourceScope="RELEASED" if args.route.startswith("released") else "OTHER_EXPLICIT_SOURCE_ROUTE",
        ruleKeyPolicy="EXACT_SOURCE_KEYS_PENDING_PARENT_HELPER_CONTRACT", phases={}, **inputs.receipt())
    write_json(runtime / "source-profile-oracle.json", expected_profile)
    server, first, settings = None, None, None
    pids = []
    try:
        for phase in ("initial", "restart"):
            native.verify_runtime(runtime, inputs.target[1], expected_jars)
            server = worldgen.WorldgenServer(runtime, inputs.java, native.LAUNCHER, "retained-worldgen-" + phase)
            pids.append(server.process.pid)
            require(len(pids) == len(set(pids)), "Restart reused a process")
            server.send("gamerule minecraft:spawn_mobs false")
            server.send("save-all flush")
            server.wait("Saved the game", 30)
            profile = native.read_json(world / "serverconfig/orespawn-worldgen.json")
            contract = audit_transition(expected_profile, profile, registered_blocks(world))
            write_json(runtime / ("profile-" + phase + ".json"), profile)
            server.send("advworks sample-worldgen " + str(args.radius))
            server.wait("Worldgen sample wrote ", args.sample_timeout)
            sample = native.read_json(runtime / "advantage-works-results/worldgen-sample.json")
            write_json(runtime / ("sample-" + phase + ".json"), sample)
            worldgen.validate_sample(sample, spec, inputs.mods)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
            server.close()
            server = None
            saved = worldgen.normal_world(world)
            if first is None:
                first, settings = sample, saved
            else:
                worldgen.validate_restart(first, sample, spec)
                require(saved == settings, "Native generator SavedData changed on restart")
            require(any(layout.dimension_directory(world, "minecraft:overworld").rglob("*.mca")), "No actual new native regions saved")
            report["phases"][phase] = dict(status=contract["status"], contract=contract, sampleSha256=digest(runtime / ("sample-" + phase + ".json")), pid=pids[-1])
            write_json(runtime / RESULT, report)
        report["status"] = "PASS" if all(row["status"] == "PASS" for row in report["phases"].values()) else "FAIL"
    except BaseException as failure:
        report.update(status="FAIL", error=repr(failure))
        raise
    finally:
        try:
            if server is not None:
                server.close()
            native.verify_runtime(runtime, inputs.target[1], expected_jars)
            report["inputsUnchanged"] = inputs.unchanged()
            require(fingerprint(source) == route["sourceFingerprint"], "Protected source fixture changed")
            report["sourceUnchanged"] = True
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure))
            raise
        finally:
            write_json(runtime / RESULT, report)
    return report


def main():
    parser = arguments(__doc__)
    parser.add_argument("--source-seal", type=Path, required=True)
    parser.add_argument("--source-seal-sha256", required=True)
    parser.add_argument("--route", required=True)
    parser.add_argument("--radius", type=int, default=3)
    parser.add_argument("--sample-timeout", type=int, default=1800)
    result = run(parser.parse_args())
    print(result["status"], flush=True)
    raise SystemExit(1 if result["status"] == "FAIL" else 0)


if __name__ == "__main__":
    main()
