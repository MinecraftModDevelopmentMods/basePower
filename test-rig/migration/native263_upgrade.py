"""Strict machine migration to Forge66; historical inputs and oracles stay immutable."""

import argparse
import copy
import importlib
import json
from pathlib import Path
import re
import shutil
import sys
import traceback

import nbtlib

from matrix import WORLD, digest, fingerprint, parse_nbt, plain, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
from native117_entity_storage import _decompress, _entity_chunk, _nbt, _payloads
from native117_format import uuid_longs
from native263_block_states import decode_palette
import native12111_upgrade as previous
import native263_layout as layout


MINECRAFT, FORGE, DATA_VERSION = "26.3", "66.0.0", 5023
SOURCE_VERSIONS = {"1.13.2": 1631, "1.14.4": 1976, "1.15.2": 2230, "1.16.5": 2586,
                   "1.17.1": 2730, "1.18.2": 2975, "1.19.4": 3337,
                   **previous.COMPONENT_SOURCE_VERSIONS, "1.21.11": 4671, "26.1.2": 4790}
LEGACY_ROUTES = ("original110", "released110", "maintained112", "released112")
LEGACY_SOURCE_VERSIONS = dict(original110=512, released110=512, maintained112=1343, released112=1343)
MOD_IDS = frozenset(("basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"))
SCOPE = "MACHINE_MIGRATION_STOPPED_STORAGE_AND_PLAYER_AUTHORITY"
HOOK_SCOPE = "INSTRUMENTED_MIGRATION_HOOK_CONVERSION_RESTART_ONLY"
MAX_FILE = 256 * 1024 * 1024


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate receipt key: " + key)
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite JSON: " + value)))


def engine():
    module = importlib.import_module("native263")
    if (module.MINECRAFT, module.FORGE) != (MINECRAFT, FORGE):
        raise AssertionError("Migration requires the handed-off Forge66.1.0 engine")
    for name in ("Server", "java25", "read_family_target", "stage", "verify_runtime", "LAUNCHER"):
        if not hasattr(module, name):
            raise AssertionError("Missing native263 engine contract: " + name)
    return module


def validate_hash(path, checksum):
    if not isinstance(checksum, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", checksum) or digest(Path(path)) != checksum.upper():
        raise AssertionError("Input checksum differs: " + str(path))


def expected_components(value, *, source_version):
    if not isinstance(source_version, str) or source_version not in SOURCE_VERSIONS:
        raise AssertionError("Unsupported sealed source version: " + str(source_version))
    if source_version in previous.COMPONENT_SOURCE_VERSIONS:
        return previous.expected_components(value, source_version=source_version)
    if SOURCE_VERSIONS[source_version] < 3465:
        return previous.components(copy.deepcopy(value))
    # 1.21.11 and 26.1.2 already have native counts and unwrapped enchantment maps. Never
    # recursively reinterpret unknown component payloads as another item schema.
    def walk(node):
        if isinstance(node, list):
            return [walk(item) for item in node]
        if not isinstance(node, dict):
            return copy.deepcopy(node)
        if "id" not in node or not any(key in node for key in ("Count", "count")):
            return {key: walk(item) for key, item in node.items()}
        if "Count" in node or type(node.get("count")) is not int or node["count"] < 1:
            raise AssertionError("1.21.11 source requires native integer count")
        if not isinstance(node["id"], str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", node["id"]):
            raise AssertionError("Invalid source item identity")
        patch = node.get("components", {})
        if not isinstance(patch, dict):
            raise AssertionError("Source components must be a map")
        for name in ("minecraft:enchantments", "minecraft:stored_enchantments"):
            if name not in patch:
                continue
            levels = patch[name]
            if not isinstance(levels, dict) or any(not isinstance(key, str)
                    or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", key)
                    or type(level) is not int or not 1 <= level <= 255 for key, level in levels.items()):
                raise AssertionError("Non-native 1.21.11 enchantment map")
        return copy.deepcopy(node)
    return walk(value)


def comparison(before, after, *, modern, source_version="1.20.1"):
    return bind(previous.comparison, expected_components=expected_components)(
        before, after, modern=modern, source_version=source_version)


load_operating_chunks = bind(previous.load_operating_chunks, MINECRAFT=MINECRAFT)


def oracle_closure():
    directory = Path(__file__).resolve().parent
    return {Path(module.__file__).resolve(): digest(Path(module.__file__)) for module in tuple(sys.modules.values())
            if getattr(module, "__file__", None) and Path(module.__file__).suffix == ".py"
            and Path(module.__file__).resolve().parent == directory
            and not Path(module.__file__).name.startswith("test_")}


def regular_bytes(path):
    path = Path(path)
    for part in (path, *path.parents):
        if part.is_symlink() or getattr(part, "is_junction", lambda: False)():
            raise ValueError("Aliased stopped-save input")
    if not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > MAX_FILE:
        raise ValueError("Missing, aliased or oversized stopped-save input")
    return path.read_bytes()


def player_authority(world):
    identity = layout.singleplayer_uuid(world)
    if identity is None:
        return dict(status="NO_SINGLEPLAYER_OWNER", playerConversionQualified=False)
    path = layout.player_file(world, identity)
    tag = layout._load(path)
    if layout.uuid_tag(tag.get("UUID")) != identity:
        raise AssertionError("Host UUID differs from its authoritative player file")
    version = tag.get("DataVersion")
    if type(version) is not nbtlib.Int or not 0 < int(version) <= DATA_VERSION:
        raise AssertionError("Unproven host-player serialization version")
    return dict(status="NATIVE_PLAYER" if int(version) == DATA_VERSION else "RETAINED_UNOPENED_SOURCE_PLAYER",
                uuid=str(identity), file=str(path.relative_to(world)), sha256=digest(path),
                dataVersion=int(version), playerConversionQualified=False)


def capture(runtime, label, rows):
    world = Path(runtime) / WORLD
    output = Path(runtime) / "native263-save" / label
    output.mkdir(parents=True, exist_ok=False)
    level, settings = layout.read_level(world), layout.read_worldgen(world)
    shutil.copy2(world / "level.dat", output / "level.dat")
    shutil.copy2(layout.shared_file(world, "minecraft:world_gen_settings"), output / "world_gen_settings.dat")
    chunks, regions = {}, {}
    identities = set()
    for row in rows:
        pos = tuple(row[axis] for axis in ("x", "y", "z"))
        if any(type(value) is not int for value in pos) or (row["name"], *pos) in identities:
            raise AssertionError("Invalid or duplicate live machine witness")
        identities.add((row["name"], *pos))
        x, y, z = pos
        cx, cz = key = x // 16, z // 16
        if key not in chunks:
            region = cx // 32, cz // 32
            if region not in regions:
                path = layout.region_file(world, "minecraft:overworld", cx, cz)
                raw = regular_bytes(path)
                regions[region] = dict(_payloads(raw, *region))
                (output / path.name).write_bytes(raw)
            if key not in regions[region]:
                raise AssertionError("Missing stopped native terrain witness")
            payload = regions[region][key]
            tag = _nbt(_decompress(payload[5:], payload[4]))
            if (type(tag.get("DataVersion")) is not nbtlib.Int or int(tag["DataVersion"]) != DATA_VERSION
                    or "Level" in tag or any(type(tag.get(name)) is not nbtlib.Int for name in ("xPos", "zPos"))
                    or tuple(int(tag[name]) for name in ("xPos", "zPos")) != key):
                raise AssertionError("Saved chunk differs from its native5023 region slot")
            (output / f"{cx}_{cz}.chunk").write_bytes(payload)
            chunk = plain(tag.unpack())
            sections, tiles = {}, {}
            for section in chunk["sections"]:
                section_y = section["Y"]
                if section_y in sections or any(name in section for name in ("Blocks", "Palette", "BlockStates")):
                    raise AssertionError("Duplicate section or historical target palette")
                states = section.get("block_states")
                sections[section_y] = decode_palette(states["palette"], states.get("data", [])) if states else None
            for tile in chunk["block_entities"]:
                location = tuple(tile[axis] for axis in ("x", "y", "z"))
                if location in tiles:
                    raise AssertionError("Duplicate stopped block entity")
                if "keepPacked" in tile:
                    if tile.pop("keepPacked") != 0:
                        raise AssertionError("Unreviewed packed block-entity representation")
                tiles[location] = tile
            chunks[key] = sections, tiles
        sections, tiles = chunks[key]
        palette = sections.get(y // 16)
        block = palette[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if palette else {"Name": "minecraft:air"}
        if (block["Name"], block.get("Properties", {})) != (row["actualName"], row["properties"]):
            raise AssertionError("Stopped block differs from actual live state: " + str(pos))
        if tiles.get(pos) != (plain(parse_nbt(row)) if row.get("nbtBase64") else None):
            raise AssertionError("Stopped block entity differs exactly from live NBT: " + str(pos))
    if not identities:
        raise AssertionError("Empty migration witnesses cannot qualify")
    result = dict(status="PASS", scope=SCOPE, witnesses=len(rows), nativeDataVersion=DATA_VERSION,
                  worldGenSettings=plain(settings.unpack()), playerAuthority=player_authority(world),
                  files={path.name: digest(path) for path in output.iterdir()})
    write_json(output / "receipt.json", result)
    return result


def turbine(world, position):
    x, _, z = position
    cx, cz = x // 16, z // 16
    path = layout.region_file(world, "minecraft:overworld", cx, cz, "entities")
    payloads = dict(_payloads(regular_bytes(path), cx // 32, cz // 32))
    entities = _entity_chunk(payloads[(cx, cz)], (cx, cz), DATA_VERSION)
    found = [tag for tag in entities if str(tag["id"]) == "baseelectric:hydroturbine"]
    if len(found) != 1:
        raise AssertionError("Expected exactly one stopped native turbine")
    tag = found[0]
    words = tag.get("UUID")
    if type(words) is not nbtlib.IntArray or len(words) != 4 or any(key in tag for key in ("UUIDMost", "UUIDLeast")):
        raise AssertionError("Turbine requires its native UUID int[4]")
    return dict(id=str(tag["id"]), parent=list(map(int, tag["parentPos"])),
                position=list(map(float, tag["Pos"])), uuid=list(uuid_longs(words.tolist())))


def copy_source(source, runtime):
    before = fingerprint(Path(source) / WORLD)
    shutil.copytree(Path(source) / WORLD, Path(runtime) / WORLD)
    if fingerprint(Path(runtime) / WORLD) != before:
        raise AssertionError("Disposable world differs from its sealed source")
    overlay = {}
    config = Path(source) / "config"
    if config.is_dir():
        for path in sorted(config.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(config)
            target = Path(runtime) / "config" / relative
            if relative.as_posix() == "fml.toml" and target.is_file():
                overlay[str(relative)] = dict(authority="NATIVE_TARGET_LOADER", sourceSha256=digest(path), targetSha256=digest(target))
                continue
            if target.exists() and regular_bytes(target) != regular_bytes(path):
                raise AssertionError("Unreviewed target/source configuration collision: " + str(relative))
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    write_json(Path(runtime) / "historical-config-custody.json", overlay)


def with_server(runtime, java, label, action, *, server_type=None):
    native = engine()
    server = (server_type or native.Server)(runtime, java, native.LAUNCHER, label)
    try:
        action(server)
        server.send("tick freeze")
        server.wait("The game is frozen", 30)
        server.snapshot(label)
        server.send("save-all flush")
        server.wait("Saved the game", 30)
    except BaseException as failure:
        try:
            server.close()
        except BaseException as shutdown:
            failure.add_note("Shutdown/log audit also failed: " + repr(shutdown))
        raise
    else:
        server.close()
    return read_json(Path(runtime) / f"migration-{label}.json")


def read_source(args):
    if args.source_route not in (*LEGACY_ROUTES, *SOURCE_VERSIONS):
        raise AssertionError("Unsupported explicit source route")
    source = Path(args.source).resolve(strict=True)
    reference = Path(args.legacy_reference).resolve(strict=True) if args.legacy_reference else source
    path = reference / args.source_receipt_name
    validate_hash(path, args.receipt_sha256)
    prior = read_json(path)
    restart = prior["phases"]["restarted"]
    version = prior.get("minecraft", "1.20.1" if args.legacy_reference else None)
    serialized = restart.get("serialized", {})
    if (not isinstance(version, str) or version not in SOURCE_VERSIONS or type(serialized.get("nativeDataVersion")) is not int
            or serialized["nativeDataVersion"] != SOURCE_VERSIONS[version]):
        raise AssertionError("Unproven sealed source version/data-version pair")
    if prior.get("status") != "PASS" or prior.get("sourceUnchanged") is not True or restart.get("status") != "PASS":
        raise AssertionError("Missing passing immutable source and restart proof")
    if not args.legacy_reference and args.source_route in SOURCE_VERSIONS and args.source_route != version:
        raise AssertionError("Source route differs from the sealed receipt platform")
    if args.source_route in LEGACY_ROUTES and not args.legacy_reference:
        raise AssertionError("Raw historical routes require their independent reference")
    snapshot = reference / "migration-restarted.json"
    validate_hash(snapshot, restart["snapshotSha256"])
    if args.legacy_reference:
        route = args.source_route
        allowed = {**LEGACY_SOURCE_VERSIONS, **{name: number for name, number in SOURCE_VERSIONS.items() if number < 3465}}
        if version != "1.20.1" or route not in allowed:
            raise AssertionError("Historical bridge requires a sealed 1.20.1 reference and an older source route")
        portable = lambda values: {key.replace("\\", "/"): value.upper() for key, value in values.items()}
        if source != Path(prior["source"]).resolve() or portable(fingerprint(source)) != portable(prior["sourceFingerprint"]):
            raise AssertionError("Original historical source no longer matches its independent reference")
        level = layout._load(source / WORLD / "level.dat").get("Data", {})
        if type(level.get("DataVersion")) is not nbtlib.Int or int(level["DataVersion"]) != allowed[route]:
            raise AssertionError("Historical route differs from the immutable source level version")
    return source, reference, path, prior, version, read_json(snapshot)


def run(args, *, server_type=None, stage_function=None, hook_only=False):
    if type(hook_only) is not bool:
        raise ValueError("Hook-only scope must be explicitly boolean")
    source, reference, prior_path, prior, version, old_rows = read_source(args)
    native = engine()
    staging = stage_function or native.stage
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    if set(target[2]) != MOD_IDS:
        raise AssertionError("Migration requires exactly six frozen production jars")
    java, java_version = native.java25(args.java)
    root = output_path(args.output)
    for protected in (source, reference, args.seed, args.profile, args.inventory, java, *(row[1] for row in target[2].values())):
        require_disjoint(root, protected)
    before, seed_before, reference_before = fingerprint(source), fingerprint(args.seed), fingerprint(reference)
    protected = {Path(args.profile): digest(args.profile), Path(args.inventory): digest(args.inventory),
                 prior_path: digest(prior_path), java: digest(java), **oracle_closure()}
    protected.update({Path(path): checksum for _, path, checksum in target[2].values()})
    root.mkdir(parents=True, exist_ok=False)
    runtime = root / "runtime"
    receipt = root / "native263-upgrade-results.json"
    result = dict(status="RUNNING", scope=HOOK_SCOPE if hook_only else SCOPE,
                  minecraft=MINECRAFT, forge=FORGE, runtime=str(runtime),
                  qualificationStatus="EXPLORATORY_UNQUALIFIED",
                  route=args.source_route, source=str(source), sourceReceiptSha256=digest(prior_path),
                  profileSha256=digest(args.profile), javaSha256=digest(java), javaVersion=java_version,
                  playerConversionQualified=False, inputHashes={str(path): sha for path, sha in protected.items()}, phases={})
    if hook_only:
        result.update(qualificationAllowed=False, operationNotRun=dict(status="NOT_RUN",
                      reason="Explicit non-consuming instrumented hook scope; not operating qualification"))
    write_json(receipt, result)
    expected = None
    try:
        expected = staging(runtime, *target, args.port)
        copy_source(source, runtime)
        fixture = read_json(reference / "migration-fixture.json")
        write_json(runtime / "migration-fixture.json", fixture)
        positions = {row["name"]: [row[axis] for axis in ("x", "y", "z")] for row in fixture}
        saves = []
        for label in ("converted", "restarted"):
            rows = with_server(runtime, java, label, lambda server: None, server_type=server_type)
            phase = comparison(old_rows, rows, modern=True, source_version=version)
            phase["snapshotSha256"] = digest(runtime / f"migration-{label}.json")
            result["phases"][label] = phase
            write_json(receipt, result)
            if phase["status"] != "PASS":
                raise AssertionError("Strict source witness mismatch: " + repr(phase["differences"][:5]))
            phase["serialized"] = capture(runtime, label, rows)
            phase["turbine"] = turbine(runtime / WORLD, positions["hydroGenerator"])
            if phase["turbine"] != prior["phases"]["restarted"]["turbine"]:
                raise AssertionError("Turbine UUID, parent or position changed")
            saves.append((rows, phase["serialized"]["worldGenSettings"]))
        if saves[0][1] != saves[1][1] or comparison(saves[0][0], saves[1][0], modern=False)["status"] != "PASS":
            raise AssertionError("Machine NBT or full worldgen settings changed on restart")
        if not hook_only:
            operation = bind(previous.operate, stage=staging, copy_source=copy_source,
                             with_server=lambda *a: with_server(*a, server_type=server_type),
                             verify_runtime=native.verify_runtime, load_operating_chunks=load_operating_chunks)
            result["phases"]["operation"] = operation(root / "operation", runtime, target, java, args.port, positions, saves[1][0])
        result["status"] = "PASS"
    except BaseException as failure:
        result.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if expected is not None:
                native.verify_runtime(runtime, target[1], expected)
            result["sourceUnchanged"] = fingerprint(source) == before
            result["seedUnchanged"] = fingerprint(args.seed) == seed_before
            result["referenceUnchanged"] = fingerprint(reference) == reference_before
            result["inputsUnchanged"] = all(digest(path) == checksum for path, checksum in protected.items())
            if not all(result[key] for key in ("sourceUnchanged", "seedUnchanged", "referenceUnchanged", "inputsUnchanged")):
                raise AssertionError("Protected migration inputs changed")
        except Exception as audit:
            result.update(status="FAIL", auditError=repr(audit))
        write_json(receipt, result)
    return int(result["status"] != "PASS")


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "profile", "seed", "inventory", "java", "output"):
        result.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    result.add_argument("--legacy-reference", type=lambda value: Path(value).resolve())
    result.add_argument("--source-route", choices=(*LEGACY_ROUTES, *SOURCE_VERSIONS), required=True)
    result.add_argument("--source-receipt-name", required=True)
    for name in ("receipt-sha256", "inventory-sha256"):
        result.add_argument("--" + name, required=True)
    result.add_argument("--port", type=int, required=True)
    return result


def main():
    cli = parser()
    args = cli.parse_args()
    if not 1024 <= args.port <= 65535 or (args.source_route in LEGACY_ROUTES and not args.legacy_reference):
        cli.error("Require an unprivileged assigned port and an independent reference for raw historical routes")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
