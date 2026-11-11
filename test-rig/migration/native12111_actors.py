"""Check sealed historical player and dimension fixtures on Forge 61."""

import argparse
import builtins
import copy
from functools import partial
import math
from pathlib import Path
import shutil
import socket
import traceback
from types import SimpleNamespace

import nbtlib

import actors
import dimensions
from matrix import WORLD, digest, fingerprint, plain, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind, capture_actors, capture_dimensions, semantic as historical_semantic
import native117_actors as historical
from native117_dimension_io import parse_dimension_nbt, project_entity
from native117_format import DIMENSIONS, dimension_id
import native117_entity_storage as entity_storage
import native117_helper_contract as helper_contract
import native117_stopped_entities as stopped
import native12111 as native


DATA_VERSION = 4671
HELPER_CONTRACT_SHA = "EBFF575F4777A43C12255250B704C06FF700D5160AFCAFE7252508353CD430C4"
PLAYER_EQUIPMENT_SLOTS = {100: "feet", 101: "legs", 102: "chest", 103: "head", -106: "offhand"}
NATIVE_ATTRIBUTE_ALIASES = {
    "minecraft:" + name: "minecraft:generic." + name
    for name in ("max_health", "knockback_resistance", "movement_speed", "armor",
                 "armor_toughness", "follow_range", "attack_damage")
}
NATIVE_ATTRIBUTE_ALIASES["minecraft:jump_strength"] = "minecraft:horse.jump_strength"
BOAT_VARIANTS = frozenset(("oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "mangrove", "bamboo"))
WOLF_CONTROL_HASHES = {
    512: "809FA56A63DD762CD1046B8E29B68F25FCE3FC1789E1BA164B6AA9DB8BF0F63E",
    1343: "3069BED41A69603E09A6F586BF984D126B80B0DE68CB26C4294B5C665D10347F",
}
ENCHANTMENTS = {
    "Enchantments": "minecraft:enchantments",
    "StoredEnchantments": "minecraft:stored_enchantments",
}


def expected_components(value):
    """Convert the historical expectation, never normalize a native observation away."""
    if isinstance(value, list):
        return [expected_components(item) for item in value]
    if not isinstance(value, dict):
        return value
    if "id" not in value or "Count" not in value:
        return {key: expected_components(item) for key, item in value.items()}
    if type(value["Count"]) is not int or value["Count"] <= 0 or "count" in value or "components" in value:
        raise AssertionError("Malformed or ambiguous historical stack")
    result = {key: item for key, item in value.items() if key not in ("Count", "tag")}
    result["count"] = value["Count"]
    custom = dict(value.get("tag", {}))
    if "BlockEntityTag" in custom:
        raise AssertionError("This actor adapter does not translate nested block-entity stack data")
    components = {}
    if "Damage" in custom:
        components["minecraft:damage"] = custom.pop("Damage")
    for key, component in ENCHANTMENTS.items():
        if key in custom:
            enchantments = custom.pop(key)
            levels = {item["id"]: item["lvl"] for item in enchantments}
            if len(levels) != len(enchantments) or any(not isinstance(key, str) for key in levels):
                raise AssertionError("Historical enchantments must have unique registry names")
            components[component] = levels
    if custom:
        components["minecraft:custom_data"] = custom
    if components:
        result["components"] = components
    return result


def exact_items(expected, observed, path=""):
    if isinstance(expected, dict) and isinstance(observed, dict):
        if "id" in expected and "count" in expected:
            if expected != observed:
                return [dict(path=path + ".exact-item", expected=expected, actual=observed)]
        else:
            return [finding for key in sorted(expected.keys() & observed.keys())
                    for finding in exact_items(expected[key], observed[key], f"{path}.{key}")]
    elif isinstance(expected, list) and isinstance(observed, list):
        return [finding for index, (before, after) in enumerate(zip(expected, observed))
                for finding in exact_items(before, after, f"{path}[{index}]")]
    return []


def source_components(state, version):
    if version in (3839, 3955):
        from native12111_upgrade import expected_components as modern_components
        return modern_components(state, source_version={3839: "1.20.6", 3955: "1.21.1"}[version])
    if version >= 3839:
        raise ValueError("Unreviewed native component player source version: " + str(version))
    return expected_components(state)


def baseline(source, kind):
    source_baseline = bind(historical.baseline,
                           capture_dimensions=bind(capture_dimensions, semantic=player_semantic))
    state, label = source_baseline(source, kind)
    definition = native.read_json(source / ("dimension-fixture.json" if kind == "dimensions" else "actor-fixture.json"))
    player = nbtlib.load(source / WORLD / "playerdata" / (definition["playerUuid"] + ".dat"))
    return source_components(state, int(player.get("DataVersion", -1))), label


def expected_player_equipment(player):
    """Apply native PlayerEquipmentFix's exact placement to expectations only."""
    result = copy.deepcopy(player)
    if "Inventory" not in result:
        return result
    inventory = result["Inventory"]
    if not isinstance(inventory, list):
        raise ValueError("Malformed expected player inventory")
    equipment = result.get("equipment", {})
    if not isinstance(equipment, dict):
        raise ValueError("Malformed expected player equipment")
    slots, remaining, moved = set(), [], {}
    for item in inventory:
        if not isinstance(item, dict) or type(item.get("Slot")) is not int:
            raise ValueError("Missing or malformed expected inventory Slot")
        slot = item["Slot"]
        if slot in slots:
            raise ValueError("Duplicate expected inventory Slot")
        slots.add(slot)
        name = PLAYER_EQUIPMENT_SLOTS.get(slot)
        if name is None:
            remaining.append(item)
        else:
            if "equipment" in result or not isinstance(item.get("id"), str) or type(item.get("count")) is not int or item["count"] <= 0:
                raise ValueError("Ambiguous or malformed expected legacy equipment")
            moved[name] = {key: value for key, value in item.items() if key != "Slot"}
    result["Inventory"] = remaining
    if moved:
        result["equipment"] = moved
    return result


def expected_state(value, kind, survival=False, adapter=True):
    result = historical.expected_state(value, kind, survival, adapter)
    # Conversion leaves the unopened loose file historical. Level.dat host data
    # has already undergone DFU, and every read phase loads/saves the loose player.
    for container in ("file", "adapter", "embedded"):
        if container in result and (container != "file" or adapter):
            result[container] = expected_player_equipment(result[container])
            if kind == "dimensions":
                result[container] = expected_player_respawn(result[container])
    if kind == "dimensions":
        for entity in result.get("entities", {}).values():
            expected_dimension_entity(entity)
    return result


def expected_player_respawn(player):
    result = copy.deepcopy(player)
    fields = ("SpawnX", "SpawnY", "SpawnZ", "SpawnDimension", "SpawnAngle", "SpawnForced")
    if not any(key in result for key in fields):
        return result
    if "respawn" in result or not all(type(result.get(key)) is int for key in fields[:3]):
        raise ValueError("Incomplete or ambiguous expected respawn position")
    yaw = result.get("SpawnAngle", 0.0)
    forced = result.get("SpawnForced", 0)
    if (type(yaw) not in (int, float) or not math.isfinite(yaw)
            or type(forced) not in (int, bool) or forced not in (0, 1)):
        raise ValueError("Malformed expected respawn angle or forced flag")
    respawn = dict(pos=[result[key] for key in fields[:3]],
                   dimension=DIMENSIONS[dimension_id(result.get("SpawnDimension", 0))], yaw=yaw, pitch=0.0)
    if forced:
        respawn["forced"] = 1
    for key in fields:
        result.pop(key, None)
    result["respawn"] = respawn
    return result


def expected_dimension_entity(entity):
    if entity.get("id") in ("minecraft:boat", "minecraft:chest_boat"):
        variant = entity.get("Type", "oak")
        if variant not in BOAT_VARIANTS:
            raise ValueError("Unproven historical boat variant")
        chest = entity["id"] == "minecraft:chest_boat"
        entity["id"] = "minecraft:" + variant + ("_chest" if chest else "") + ("_raft" if variant == "bamboo" else "_boat")
        entity.pop("Type", None)
    if entity.get("id") == "minecraft:horse":
        moved = {}
        for old, slot in (("SaddleItem", "saddle"), ("ArmorItem", "body")):
            if old in entity:
                if "equipment" in entity:
                    raise ValueError("Ambiguous expected horse equipment")
                moved[slot] = entity.pop(old)
        if moved:
            entity["equipment"] = moved
    for passenger in entity.get("Passengers", ()):
        expected_dimension_entity(passenger)


def player_semantic(value, keys, registry):
    if "SpawnX" in keys:
        keys = (*keys, "SpawnAngle")
    result = historical_semantic(value, keys, registry)
    if "Inventory" in keys and "equipment" in value:
        equipment = value["equipment"]
        if not isinstance(equipment, dict):
            raise ValueError("Malformed observed player equipment")
        result["equipment"] = plain(equipment)
    if "SpawnX" in keys and "respawn" in value:
        if not isinstance(value["respawn"], dict):
            raise ValueError("Malformed observed player respawn")
        if any(key in value for key in ("SpawnX", "SpawnY", "SpawnZ", "SpawnDimension", "SpawnAngle", "SpawnForced")):
            raise ValueError("Ambiguous observed native and historical respawn")
        result["respawn"] = plain(value["respawn"])
    return result


def dimension_entity_state(value):
    result = dimensions.entity_state(value)
    if "equipment" in value:
        if not isinstance(value["equipment"], dict):
            raise ValueError("Malformed observed entity equipment")
        if any(key in value for key in ("SaddleItem", "ArmorItem", "body_armor_item", "saddle", "ArmorItems", "HandItems")):
            raise ValueError("Ambiguous observed native and historical entity equipment")
        result["equipment"] = copy.deepcopy(value["equipment"])
    if "Passengers" in value:
        result["Passengers"] = [dimension_entity_state(entry) for entry in value["Passengers"]]
    return result


def leash_position(value):
    if (len(value) != 3 or any(type(coordinate) is not int or not -(1 << 31) <= coordinate < (1 << 31)
                               for coordinate in value)):
        raise ValueError("Expected the native leash knot's block-position int[3]")
    return dict(zip(("X", "Y", "Z"), value))


def dimension_entity_view(value):
    if value.get("id") == "minecraft:horse":
        if "leash" in value:
            if "Leash" in value:
                raise ValueError("Ambiguous saved horse leash")
            value["Leash"] = leash_position(value.pop("leash"))
        if "body_armor_item" in value:
            if "ArmorItem" in value:
                raise ValueError("Ambiguous saved horse armor")
            value["ArmorItem"] = value.pop("body_armor_item")
        for attribute in value.get("Attributes", ()):
            if attribute["Name"] == "minecraft:generic.jump_strength":
                if any(other["Name"] == "horse.jumpStrength" for other in value["Attributes"]):
                    raise ValueError("Ambiguous saved horse jump strength")
                attribute["Name"] = "horse.jumpStrength"
    for passenger in value.get("Passengers", ()):
        dimension_entity_view(passenger)
    return value


def native_dimension_nbt(entry):
    parser = bind(parse_dimension_nbt, project_entity=lambda value, contexts:
                  project_entity(native_attribute_view(value), contexts))
    return dimension_entity_view(parser(entry))


def native_attribute_view(value):
    """Expose the new field spelling to the retained semantic oracle without changing values."""
    result = copy.deepcopy(value)

    def visit(entity):
        if "attributes" in entity:
            if "Attributes" in entity or not isinstance(entity["attributes"], list):
                raise ValueError("Ambiguous native attribute list")
            attributes, names = [], set()
            for original in entity.pop("attributes"):
                if not isinstance(original, dict) or not isinstance(original.get("id"), str):
                    raise ValueError("Malformed native attribute")
                row = dict(original)
                raw_name = row.pop("id")
                name = NATIVE_ATTRIBUTE_ALIASES.get(raw_name, raw_name)
                if name in names or "Name" in row or "Base" in row or "Modifiers" in row:
                    raise ValueError("Duplicate or ambiguous native attribute")
                names.add(name)
                row["Name"] = name
                if "base" in row:
                    row["Base"] = row.pop("base")
                if "modifiers" in row:
                    row["Modifiers"] = row.pop("modifiers")
                attributes.append(row)
            entity["Attributes"] = attributes
        for passenger in entity.get("Passengers", ()):
            visit(passenger)
    visit(result)
    return result


def capture_player_version(capture, runtime, label, evidence):
    state = capture(runtime, label, evidence)
    version = int(nbtlib.load(evidence / "player.dat").get("DataVersion", -1))
    if version != DATA_VERSION:
        if label != "converted" or version >= DATA_VERSION:
            raise AssertionError(f"{label}: native player file was not serialized as data version {DATA_VERSION}: {version}")
        # The unopened loose file has not gone through PlayerDataStorage yet.
        # Compare the retained source representation but keep its original file and version as evidence.
        state["file"] = source_components(state["file"], version)
    write_json(evidence / "player-format.json", dict(dataVersion=version, native=version == DATA_VERSION))
    return state


retain = bind(historical.retain,
              capture_actors=partial(capture_player_version, bind(capture_actors, semantic=player_semantic)),
              capture_dimensions=partial(capture_player_version, bind(capture_dimensions, semantic=player_semantic,
                                                                      entity_state=dimension_entity_state,
                                                                      parse_nbt=native_dimension_nbt)))
command = bind(historical.command, retain=retain)
release = bind(historical.release, command=command)


def helper_snapshot(record):
    expected, rows, roots = entity_storage._snapshots(record)
    for row in rows.values():
        if "leash" not in row:
            continue
        position = row["leash"]
        if "Leash" in row or not isinstance(position, nbtlib.IntArray):
            raise ValueError("Malformed or ambiguous native stopped leash")
        if len(position) == 3:
            row["Leash"] = nbtlib.Compound({key: nbtlib.Int(number)
                                           for key, number in leash_position(list(map(int, position))).items()})
        elif len(position) != 4:
            raise ValueError("Unexpected native stopped leash array length")
    return expected, rows, roots


validate_helpers = bind(helper_contract.validate, native=SimpleNamespace(
    _snapshots=helper_snapshot, _nbt=entity_storage._nbt, _decompress=entity_storage._decompress,
    _identity=entity_storage._identity), CONTRACT_SHA=HELPER_CONTRACT_SHA,
    POLICY="native12111-leash-knot-nosave-v1")
helper_adapter = SimpleNamespace(CONTRACT="native12111-helper-contract.json", LIFECYCLE=helper_contract.LIFECYCLE,
                                 validate=validate_helpers, raw_helpers_absent=helper_contract.raw_helpers_absent)


def stop_audit_import(name, globals=None, locals=None, fromlist=(), level=0):
    # Only this function's local helper import is adapted. The raw region audit
    # still uses the original on-disk NBT and the module is never monkey-patched.
    if level == 0 and name == "native117_helper_contract":
        return helper_adapter
    return builtins.__import__(name, globals, locals, fromlist, level)


audit_stopped = bind(stopped.audit_stopped,
                     __builtins__=dict(vars(builtins), __import__=stop_audit_import))


def verdict(oracle, before, after):
    result = oracle(before, after)
    findings = exact_items(before, after)
    findings.extend(exact_native_maps(before, after))
    result["differences"].extend(findings)
    if findings:
        result["status"] = "FAIL"
        if "entity_status" in result and any(row["path"].startswith(".entities.") for row in findings):
            result["entity_status"] = "FAIL"
        if "player_status" in result and any(not row["path"].startswith(".entities.") for row in findings):
            result["player_status"] = "FAIL"
    return result


def exact_native_maps(expected, actual, path=""):
    findings = []
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key, default in (("equipment", {}), ("respawn", None)):
            before, after = expected.get(key, default), actual.get(key, default)
            if before != after:
                findings.append(dict(path=f"{path}.{key}.exact-map", expected=before, actual=after))
        for key in expected.keys() & actual.keys():
            findings.extend(exact_native_maps(expected[key], actual[key], f"{path}.{key}"))
    elif isinstance(expected, list) and isinstance(actual, list):
        for index, (before, after) in enumerate(zip(expected, actual)):
            findings.extend(exact_native_maps(before, after, f"{path}[{index}]"))
    return findings


def assess_native(before, after, strict, proof, scope, adapter):
    result = dict(status=strict["status"], rawStrictStatus=strict["status"],
                  rawStrictResult=copy.deepcopy(strict), nativeDifferences=[])
    if strict["status"] == "PASS" or proof is None:
        return result
    expected = copy.deepcopy(before)
    wolf = expected.get("entities", {}).get("passenger-wolf", {})
    actual = after.get("entities", {}).get("passenger-wolf", {})
    maximum = wolf.get("Attributes", {}).get("generic.maxHealth", {})
    if (wolf.get("id") != "minecraft:wolf" or actual.get("id") != wolf["id"]
            or wolf.get("Health") != 8 or maximum.get("Base") != 20
            or any(key not in wolf or actual.get(key) != wolf[key]
                   for key in ("UUIDMost", "UUIDLeast", "OwnerUUID"))):
        return result
    # These exact changes were independently reproduced by vanilla DFU and native
    # Wolf load/save on a classpath that contains no Advantage classes.
    controls = {}
    for version, checksum in WOLF_CONTROL_HASHES.items():
        path = Path(proof) / f"legacy-{version}-receipt.json"
        native.validate_hash(path, checksum)
        controls[str(version)] = dict(path=str(path), sha256=checksum, observation=native.read_json(path))
    wolf["Health"] = 16
    maximum["Base"] = 40
    checked = verdict(dimensions.verdict, expected, after)
    if checked["status"] == "PASS":
        result.update(status="PASS_WITH_NATIVE_DIFFERENCE", allOtherFieldsStrictMatch=True,
                      controlledProof=controls, nativeDifferences=copy.deepcopy(strict["differences"]))
    return result


def summarize_native(checks, assessments):
    failures, native_changes = [], []
    for key, check in checks.items():
        if check["status"] == "PASS":
            continue
        assessment = assessments.get(key)
        if (assessment is None or assessment.get("rawStrictResult") != check
                or assessment.get("status") != "PASS_WITH_NATIVE_DIFFERENCE"
                or assessment.get("allOtherFieldsStrictMatch") is not True):
            failures.append(key)
        else:
            native_changes.append(dict(check=key, assessment=assessment))
    return dict(status="FAIL" if not checks or failures else
                "PASS_WITH_NATIVE_DIFFERENCE" if native_changes else "PASS",
                rawStrictStatus="FAIL" if native_changes or failures else "PASS",
                failures=failures, nativeDifferences=native_changes)


def copy_fixture(source, runtime):
    shutil.copytree(source / WORLD, runtime / WORLD)
    shutil.copy2(source / "migration-fixture.json", runtime / "migration-fixture.json")
    config = source / "config"
    if config.is_dir():
        for item in config.rglob("*"):
            destination = runtime / "config" / item.relative_to(config)
            if item.is_file() and destination.exists():
                raise FileExistsError(f"Retained fixture config conflicts with native runtime: {destination}")
        shutil.copytree(config, runtime / "config", dirs_exist_ok=True)


run_route = bind(historical.run_route, baseline=baseline, expected_state=expected_state, command=command, release=release,
                 copy_fixture=copy_fixture, assess=assess_native,
                 summarize_checks=summarize_native,
                 native117_dimension_assessment=SimpleNamespace(__file__=__file__),
                 actors=SimpleNamespace(compare=partial(verdict, actors.compare), freeze_result=actors.freeze_result,
                                        tick_result=actors.tick_result, backup_state=actors.backup_state),
                 dimensions=SimpleNamespace(verdict=partial(verdict, dimensions.verdict),
                                             tick_result=dimensions.tick_result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("fixture", "fixture-receipt", "profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--fixture-receipt-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--kind", choices=("actors", "dimensions"), required=True)
    parser.add_argument("--native-wolf-proof", type=lambda value: Path(value).resolve(strict=True))
    parser.add_argument("--source-stopped-reference", type=lambda value: Path(value).resolve(strict=True))
    parser.add_argument("--source-stopped-reference-sha256")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    if not 26450 <= args.port <= 65535:
        parser.error("Use a disposable localhost port at or above 26450")

    output = output_path(args.output)
    source = args.fixture.resolve(strict=True)
    seed, inventory, mods = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    for path in (source, args.fixture_receipt, args.profile, seed, args.inventory, java,
                 *(entry[1] for entry in mods.values())):
        require_disjoint(output, path)
    native.validate_hash(args.fixture_receipt, args.fixture_receipt_sha256)
    approved = native.read_json(args.fixture_receipt)
    before = fingerprint(source)
    if not before or Path(approved["source"]).resolve() != source or approved["sourceFingerprint"] != before:
        raise AssertionError("Historical fixture no longer matches its approved receipt")
    source_reference, reference_before, reference_helper, active_route = None, None, None, run_route
    if args.source_stopped_reference is not None or args.source_stopped_reference_sha256 is not None:
        if args.kind != "actors" or args.source_stopped_reference is None or args.source_stopped_reference_sha256 is None:
            raise ValueError("Stopped source reference requires actors mode and explicit file plus SHA-256")
        import native12111_stopped_actor_reference as reference_helper
        require_disjoint(output, args.source_stopped_reference.parent)
        source_reference = reference_helper.validate(args.source_stopped_reference, args.source_stopped_reference_sha256,
                                                     source, args.fixture_receipt_sha256)
        reference_before = fingerprint(args.source_stopped_reference.parent)
        def stopped_baseline(actual_source, kind):
            if actual_source.resolve() != source or kind != "actors":
                raise AssertionError("Stopped source reference bound to a different route")
            return copy.deepcopy(source_reference), "sealed-source-stopped-disk"
        active_route = bind(run_route, baseline=stopped_baseline)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", args.port))
    seed_before = fingerprint(seed)
    expected_jars = {path.name: sha.upper() for _, path, sha in mods.values()}
    runtime = output / "server"
    report = output / "native12111-actors-results.json"
    runner = Path(__file__)
    result = dict(status="RUNNING", kind=args.kind, source=str(source), realClientLogin="NOT_TESTED",
                  qualificationStatus=native.EXPLORATORY, minecraft=native.MINECRAFT, forge=native.FORGE,
                  fixtureReceiptSha256=digest(args.fixture_receipt), profileSha256=digest(args.profile),
                  inventorySha256=digest(args.inventory), runnerSha256=digest(runner),
                  historicalOracleSha256=digest(Path(historical.__file__)), javaSha256=digest(java),
                  javaVersion=version, port=args.port, jars=expected_jars, checks={})
    output.mkdir()
    shutil.copy2(runner, output / runner.name)
    if source_reference is not None:
        result["stoppedSourceReference"] = dict(path=str(args.source_stopped_reference),
                sha256=digest(args.source_stopped_reference), helperSha256=digest(Path(reference_helper.__file__)),
                targetObservationsUsed=False, sealedBeforeTargetLoad=True)
        shutil.copy2(Path(reference_helper.__file__), output / "stopped-reference-builder-source.py")
    write_json(report, result)

    def stage(destination, target, port):
        native.stage(destination, *target, port)
        return native.LAUNCHER

    class Server(native.Server):
        def __init__(self, *values):
            native.verify_runtime(runtime, inventory, expected_jars)
            super().__init__(*values)

        def close(self):
            try:
                super().close()
            finally:
                native.verify_runtime(runtime, inventory, expected_jars)

    try:
        args.target_manifest, args.java17 = args.profile, java
        result["checks"] = active_route(
            source, runtime, (seed, inventory, mods), args, stage=stage, server_type=Server,
            stop_audit=partial(audit_stopped, minecraft_version=native.MINECRAFT,
                               data_version=DATA_VERSION, stopping_phase="ServerStoppingEvent"),
            check_log=native.require_clean_log, minecraft=native.MINECRAFT)
        checks = result["checks"]
        result["status"] = "PASS" if checks and all(c["status"] == "PASS" for c in checks.values()) else "FAIL"
        if args.kind == "dimensions":
            result["nativeAssessment"] = native.read_json(runtime / "native117-platform-assessment.json")
            result["status"] = result["nativeAssessment"]["assessment"]["status"]
    except BaseException as failure:
        result.update(status="FAIL", error=repr(failure), traceback=traceback.format_exc())
        checkpoint = runtime / "native117-comparison.json"
        if checkpoint.is_file():
            result["checks"] = native.read_json(checkpoint)
        raise
    finally:
        result["sourceUnchanged"] = fingerprint(source) == before
        result["seedUnchanged"] = fingerprint(seed) == seed_before
        result["inputsUnchanged"] = all(digest(path) == sha for path, sha in (
            (args.profile, result["profileSha256"]), (args.inventory, result["inventorySha256"]),
            (args.fixture_receipt, result["fixtureReceiptSha256"]), (java, result["javaSha256"]),
            (runner, result["runnerSha256"]), (Path(historical.__file__), result["historicalOracleSha256"]),
            *((path, sha.upper()) for _, path, sha in mods.values()),
        ))
        if not all(result[key] for key in ("sourceUnchanged", "seedUnchanged", "inputsUnchanged")):
            result["status"] = "FAIL"
        if source_reference is not None:
            result["stoppedSourceReferenceUnchanged"] = (
                fingerprint(args.source_stopped_reference.parent) == reference_before
                and digest(Path(reference_helper.__file__)) == result["stoppedSourceReference"]["helperSha256"])
            if not result["stoppedSourceReferenceUnchanged"]:
                result["status"] = "FAIL"
        write_json(report, result)
    return int(result["status"] not in ("PASS", "PASS_WITH_NATIVE_DIFFERENCE"))


if __name__ == "__main__":
    raise SystemExit(main())
