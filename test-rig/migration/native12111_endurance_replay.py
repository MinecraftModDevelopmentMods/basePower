"""Seal before endurance, freeze stopped files, then replay Forge 61 evidence.

The external seal and freeze SHA pins are mandatory. A retrospective seal
allows useful replay but never a qualified PASS or a claim of pre-run custody.
No command launches Minecraft or modifies a source world. Raw equality requires
a final restart-resumed checkpoint under tick freeze, followed by save/stop.
"""

import argparse
import base64
from contextlib import ExitStack, contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import time

import nbtlib
from nbtlib.literal import parser as literal_parser

import audit_native1201_endurance as checkpoint_audit
from audit_native12111_endurance import Forge61
from audit_endurance import expected_inventory_acknowledgements
import endurance
from matrix import WORLD
from native117_actor_io import bind
from native117_entity_storage import _decompress, _nbt, _payloads
from native117_format import decode_palette
from native117_machine_operations import typed_nbt
from native117_machine_saved import state
import native12111 as native

DATA_VERSION = 4671
MODES = ("fresh12111", "retained-upgrade-native12111")
RECEIPT = "native12111-endurance-results.json"
MAX_FILE = 256 * 1024 * 1024
MAX_JSON = 16 * 1024 * 1024
MAX_FILES = 16384
MAX_TOTAL = 8 * 1024 * 1024 * 1024
MAX_ROWS = 65536
REGION = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def pin(data, expected):
    require(isinstance(expected, str) and re.fullmatch(r"[a-fA-F0-9]{64}", expected)
            and sha(data) == expected.upper(), "External SHA-256 pin differs")


def regular(path):
    path = Path(path).absolute()
    for part in (path, *path.parents):
        if part.is_symlink() or getattr(part, "is_junction", lambda: False)():
            raise ValueError("Aliases are not frozen evidence: " + str(part))
    if not path.is_file():
        raise ValueError("Expected regular file: " + str(path))
    if path.stat().st_nlink != 1:
        raise ValueError("Aliased hard-linked evidence: " + str(path))
    return path


class Budget:
    def __init__(self, seconds=300):
        if type(seconds) is not int or not 1 <= seconds <= 900:
            raise ValueError("Replay budget must be 1..900 seconds")
        self.deadline = time.monotonic() + seconds
        self.bytes = 0
        self.locked = {}

    def check(self):
        if time.monotonic() > self.deadline:
            raise TimeoutError("Bounded replay deadline exceeded")

    def read(self, path, limit=MAX_FILE):
        self.check()
        path = regular(path)
        size = path.stat().st_size
        if size > limit:
            raise ValueError("File exceeds audit bound: " + str(path))
        if path.resolve() in self.locked:
            stream = self.locked[path.resolve()]
            stream.seek(0)
            data = stream.read(size + 1)
        else:
            with path.open("rb") as stream:
                data = stream.read(size + 1)
        self.bytes += len(data)
        if len(data) != size or len(data) > limit or self.bytes > MAX_TOTAL:
            raise ValueError("Changed file or total audit byte bound exceeded")
        self.check()
        return data

    def digest(self, path):
        return sha(self.read(path))

    def json(self, path):
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError("Duplicate JSON key: " + key)
                result[key] = value
            return result

        def invalid(value):
            raise ValueError("Nonfinite JSON: " + value)

        return json.loads(self.read(path, MAX_JSON), object_pairs_hook=unique, parse_constant=invalid)


def write_new(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(document, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write("\n")


def disjoint(output, *inputs):
    output = Path(output).resolve()
    for path in inputs:
        path = Path(path).resolve()
        if output.is_relative_to(path) or path.is_relative_to(output):
            raise ValueError("Output overlaps a protected input")
    if output.exists():
        raise FileExistsError(output)


def tree(root, budget):
    root = Path(root).absolute()
    pending, result, entries = [root], {}, 0
    while pending:
        budget.check()
        for path in sorted(pending.pop().iterdir()):
            entries += 1
            if entries > MAX_FILES:
                raise ValueError("Frozen tree entry bound exceeded")
            if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
                raise ValueError("Alias in frozen tree")
            if path.is_dir():
                pending.append(path)
            else:
                result[path.relative_to(root).as_posix()] = budget.digest(path)
    return result


def oracle_paths():
    # Imported local transitive helpers include both independent semantic oracles.
    root = Path(__file__).resolve().parent
    result = {Path(__file__).resolve()}
    for module in list(sys.modules.values()):
        path = getattr(module, "__file__", None)
        if path and Path(path).suffix == ".py" and (
                Path(path).resolve().parent == root or getattr(module, "__name__", "").startswith("nbtlib")):
            result.add(Path(path).resolve())
    return sorted(result)


def seal(profile, profile_sha256, specification, inventory, inventory_sha256, java, budget, *, retrospective=False):
    pin(budget.read(profile), profile_sha256)
    pin(budget.read(inventory), inventory_sha256)
    document = budget.json(profile)
    require(document["minecraftVersion"] == native.MINECRAFT
            and document["forge"]["version"] == native.FORGE_VERSION, "Wrong native runtime profile")
    mods = document["mods"]
    require(len(mods) == 6 and {row["id"] for row in mods} == {
        "basepower", "basesteam", "baseelectric", "advantageworkstest", "basemetals", "orespawn"},
        "Seal requires all six production jars")
    for row in mods:
        require(Path(row["path"]).name == row["file"], "Artifact filename differs from profile")
        pin(budget.read(row["path"]), row["sha256"])
    executable, version = native.java21(Path(java))
    files = [Path(profile), Path(specification), Path(inventory), executable,
             *(Path(row["path"]) for row in mods), *oracle_paths()]
    return dict(format=1, purpose=("NATIVE61_ENDURANCE_RETROSPECTIVE_SEAL" if retrospective
                                  else "NATIVE61_ENDURANCE_PRE_RUN_SEAL"),
                createdAtUtc=datetime.now(timezone.utc).isoformat(),
                profile=str(Path(profile).resolve()), specification=str(Path(specification).resolve()),
                inventory=str(Path(inventory).resolve()), java=str(executable), javaVersion=version,
                files={str(path.resolve()): budget.digest(path) for path in files},
                oracles=[str(path) for path in oracle_paths()])


def verify_seal(path, expected, budget):
    pin(budget.read(path), expected)
    document = budget.json(path)
    require(document.get("format") == 1 and document.get("purpose") in (
        "NATIVE61_ENDURANCE_PRE_RUN_SEAL", "NATIVE61_ENDURANCE_RETROSPECTIVE_SEAL"), "Wrong input seal")
    require(document["oracles"] == [str(path) for path in oracle_paths()], "Oracle module coverage changed")
    for name, checksum in document["files"].items():
        pin(budget.read(name), checksum)
    return document


def custody(sealed, run, budget):
    setup = min(datetime.fromisoformat(budget.json(
        run / mode / "advantage-works-results/W-01-checkpoint-setup.json")["recordedAtUtc"])
                for mode in MODES)
    created = datetime.fromisoformat(sealed["createdAtUtc"])
    retrospective = sealed["purpose"] == "NATIVE61_ENDURANCE_RETROSPECTIVE_SEAL"
    require(retrospective or created < setup, "Seal was not made before the run; use explicit retrospective mode")
    return dict(preRunCustody=not retrospective, sealCreatedAtUtc=sealed["createdAtUtc"],
                earliestSetupAtUtc=setup.isoformat(),
                coverageGap=("No pre-run oracle custody proof; seal is retrospective" if retrospective else None))


def complete(report):
    require(report.get("status") == "PASS" and report.get("minecraft") == native.MINECRAFT
            and report.get("forge") == native.FORGE and type(report.get("duration_seconds")) is int
            and report["duration_seconds"] == 1800 and type(report.get("interval_seconds")) is int
            and report["interval_seconds"] == 60 and report.get("seedUnchanged") is True
            and report.get("retained_unchanged") is True and set(report.get("profiles", {})) == set(MODES),
            "Only a complete two-profile 30-minute PASS may be frozen/replayed; HELD is not proof")
    require(report.get("checked_stations") == endurance.CHECKED and report.get("active_stations") == endurance.ACTIVE,
            "Incomplete station coverage")
    for mode in MODES:
        row = report["profiles"][mode]
        require(row.get("status") == row.get("restart") == row.get("resumed_processing") == "PASS"
                and type(row.get("endurance_batches")) is int and row["endurance_batches"] == 30
                and type(row.get("verified_checkpoints")) is int and row["verified_checkpoints"] == 33
                and type(row.get("game_ticks")) is int and row["game_ticks"] >= 32400
                and type(row.get("elapsed_seconds")) in (int, float) and math.isfinite(row["elapsed_seconds"])
                and row["elapsed_seconds"] >= 1800,
                "Incomplete endurance profile: " + mode)


@contextmanager
def stopped(runtime, budget):
    text = budget.read(runtime / "console-endurance-restart.log", MAX_JSON).decode("utf-8")
    require("Stopping server" in text and "All dimensions are saved" in text,
            "Missing final orderly shutdown witnesses")
    path = regular(runtime / WORLD / "session.lock")
    with path.open("r+b") as stream:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            budget.locked[path.resolve()] = stream
            try:
                yield
            finally:
                budget.locked.pop(path.resolve())
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            budget.locked[path.resolve()] = stream
            try:
                yield
            finally:
                budget.locked.pop(path.resolve())
                fcntl.flock(stream, fcntl.LOCK_UN)


def freeze(run, seal_path, seal_sha256, receipt_sha256, output, budget):
    run, output = Path(run).resolve(), Path(output).resolve()
    sealed = verify_seal(seal_path, seal_sha256, budget)
    disjoint(output, run, seal_path, *(Path(name) for name in sealed["files"]))
    pin(budget.read(run / RECEIPT), receipt_sha256)
    report = budget.json(run / RECEIPT)
    complete(report)
    provenance = custody(sealed, run, budget)
    output.mkdir()
    result = dict(status="FAIL", format=1, purpose="NATIVE61_STOPPED_ENDURANCE_FREEZE",
                  sealSha256=seal_sha256.upper(), inputReceiptSha256=receipt_sha256.upper(), source=str(run),
                  custody=provenance)
    try:
        with ExitStack() as stack:
            for mode in MODES:
                stack.enter_context(stopped(run / mode, budget))
            files = tree(run, budget)
            for name, checksum in files.items():
                data = budget.read(run / name)
                pin(data, checksum)
                destination = output / "run" / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with destination.open("xb") as stream:
                    stream.write(data)
            require(tree(run, budget) == files and tree(output / "run", budget) == files,
                    "Stopped source or frozen copy changed")
        verify_seal(seal_path, seal_sha256, budget)
        with (output / "seal.json").open("xb") as stream:
            stream.write(budget.read(seal_path))
        result.update(status="PASS", files=files)
    except BaseException as error:
        result["failure"] = repr(error)
        raise
    finally:
        write_new(output / "freeze.json", result)
    return result


def typed_items(value):
    if isinstance(value, nbtlib.Compound):
        if "Items" in value:
            items = value["Items"]
            require(isinstance(items, nbtlib.List) and items.subtype.tag_id in (10, 0),
                    "Items is not a native compound list")
            slots = set()
            for item in items:
                require(isinstance(item.get("Slot"), nbtlib.Byte) and 0 <= int(item["Slot"]) <= 127
                        and int(item["Slot"]) not in slots and isinstance(item.get("count"), nbtlib.Int)
                        and 1 <= int(item["count"]) <= 64 and isinstance(item.get("id"), nbtlib.String)
                        and re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", str(item["id"]))
                        and "Count" not in item and "tag" not in item
                        and ("components" not in item or isinstance(item["components"], nbtlib.Compound)),
                        "Wrong native count/Slot/id/components or duplicate slot")
                slots.add(int(item["Slot"]))
        for child in value.values():
            typed_items(child)
    elif isinstance(value, nbtlib.List):
        for child in value:
            typed_items(child)


class UniqueCompound(nbtlib.Compound):
    def __setitem__(self, key, value):
        if key in self:
            raise ValueError("Duplicate SNBT key: " + key)
        super().__setitem__(key, value)


class UniqueParser(literal_parser.Parser):
    parse_compound = bind(literal_parser.Parser.parse_compound, Compound=UniqueCompound)


parse_unique_snbt = bind(literal_parser.parse_nbt, Parser=UniqueParser)


def row_nbt(row):
    require(not ("nbt" in row and "nbtBase64" in row), "Ambiguous checkpoint NBT")
    if "nbtBase64" in row:
        encoded = row["nbtBase64"]
        require(isinstance(encoded, str) and len(encoded) <= MAX_JSON, "Oversized checkpoint NBT")
        value = nbtlib.Compound(_nbt(_decompress(base64.b64decode(encoded, validate=True), 1)))
    elif "nbt" in row:
        require(isinstance(row["nbt"], str) and len(row["nbt"]) <= MAX_JSON, "Oversized checkpoint SNBT")
        value = parse_unique_snbt(row["nbt"])
    else:
        return None
    require(isinstance(value, nbtlib.Compound), "Checkpoint NBT must be a compound")
    typed_items(value)
    return value


def snapshot(document):
    rows = document.get("snapshot")
    require(isinstance(rows, list) and 0 < len(rows) <= MAX_ROWS, "Missing or excessive checkpoint rows")
    seen = set()
    for row in rows:
        pos = row.get("pos")
        require(isinstance(pos, list) and len(pos) == 3 and all(type(n) is int for n in pos)
                and tuple(pos) not in seen, "Duplicate or malformed checkpoint coordinate")
        seen.add(tuple(pos))
        row_nbt(row)
    return native.parse_endurance_snapshot(document)


def terrain(payload, coordinates):
    require(len(payload) >= 6 and int.from_bytes(payload[:4], "big") == len(payload) - 4,
            "Wrong terrain payload length")
    root = _nbt(_decompress(payload[5:], payload[4]))
    require(isinstance(root.get("DataVersion"), nbtlib.Int) and int(root["DataVersion"]) == DATA_VERSION
            and "Level" not in root and isinstance(root.get("xPos"), nbtlib.Int)
            and isinstance(root.get("zPos"), nbtlib.Int)
            and (int(root["xPos"]), int(root["zPos"])) == coordinates, "Not native DataVersion 4671 terrain")
    sections, tiles = {}, {}
    require(isinstance(root.get("sections"), nbtlib.List) and len(root["sections"]) <= 256
            and isinstance(root.get("block_entities"), nbtlib.List), "Missing typed native chunk lists")
    for section in root["sections"]:
        y = section["Y"]
        require(isinstance(y, nbtlib.Byte) and int(y) not in sections, "Duplicate or wrongly typed section")
        states = section.get("block_states")
        if states is None:
            sections[int(y)] = None
            continue
        words = states.get("data", nbtlib.LongArray([]))
        require(isinstance(words, nbtlib.LongArray), "Palette data is not native longs")
        sections[int(y)] = decode_palette(states["palette"].unpack(), words.tolist())
    for tile in root["block_entities"]:
        require(all(isinstance(tile.get(k), nbtlib.Int) for k in ("x", "y", "z")), "Wrong tile coordinates")
        pos = tuple(int(tile[k]) for k in ("x", "y", "z"))
        require((pos[0] // 16, pos[2] // 16) == coordinates and pos not in tiles, "Duplicate/out-of-chunk tile")
        require(isinstance(tile.get("keepPacked"), nbtlib.Byte) and int(tile["keepPacked"]) == 0,
                "Not an unpacked saved block entity")
        tile = nbtlib.Compound({k: v for k, v in tile.items() if k != "keepPacked"})
        typed_items(tile)
        tiles[pos] = tile
    return sections, tiles


def raw_final(runtime, budget):
    root = _nbt(_decompress(budget.read(runtime / WORLD / "level.dat", MAX_JSON), 1))
    version = root["Data"].get("DataVersion")
    require(isinstance(version, nbtlib.Int) and int(version) == DATA_VERSION, "Wrong saved level DataVersion")
    grouped, count = {}, 0
    for station in endurance.CHECKED:
        document = budget.json(runtime / "advantage-works-results" / f"{station}-checkpoint-restart-resumed.json")
        snapshot(document)
        for row in document["snapshot"]:
            x, _, z = row["pos"]
            grouped.setdefault((x // 16, z // 16), []).append(row)
            count += 1
    regions, differences = {}, []
    for (cx, cz), rows in grouped.items():
        budget.check()
        region = cx // 32, cz // 32
        if region not in regions:
            data = budget.read(runtime / WORLD / "region" / ("r.%d.%d.mca" % region))
            regions[region] = dict(_payloads(data, *region))
        require((cx, cz) in regions[region], "Missing stopped terrain chunk")
        sections, tiles = terrain(regions[region][cx, cz], (cx, cz))
        for row in rows:
            x, y, z = pos = tuple(row["pos"])
            palette = sections.get(y // 16)
            actual = palette[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if palette else {"Name": "minecraft:air"}
            actual = dict(Name=actual["Name"], Properties=actual.get("Properties", {}))
            if actual != state(row) or typed_nbt_or_none(row_nbt(row)) != typed_nbt_or_none(tiles.get(pos)):
                differences.append(dict(pos=list(pos), checkpoint=row,
                                        savedState=actual, savedNbt=typed_nbt_or_none(tiles.get(pos))))
    require(not differences, "Stopped raw terrain/component mismatch: " + json.dumps(differences[:3]))
    return dict(status="PASS", dataVersion=DATA_VERSION, comparedRows=count, comparedChunks=len(grouped))


def typed_nbt_or_none(value):
    return None if value is None else typed_nbt(value)


def feed_audit(runtime, budget):
    counts = {}
    for boot, expected in zip(("endurance", "endurance-restart"), expected_inventory_acknowledgements(30)):
        text = budget.read(runtime / f"console-{boot}.log", MAX_JSON).decode("utf-8")
        native.require_clean_log(runtime / f"console-{boot}.log")
        require(text.count("Replaced a slot at") == expected, "Missing/extra native inventory acknowledgements")
        require(not any(marker in text for marker in (
            "Unknown slot", "Could not replace", "Unknown or incomplete command", "Incorrect argument",
            "Unknown item", "There is no container", "Modified block data")), "Failed or NBT-fed input commands")
        if boot.endswith("restart"):
            require("Advantage Works built" not in text and "Reset 1 station" not in text
                    and not any("Started " + station + " " in text for station in
                                ("S-04", "E-03", "E-02", "S-02", "E-01")), "Loaded networks were rebuilt/restarted")
        counts[boot] = expected
    return dict(status="PASS", inventoryAcknowledgements=counts,
                scope="Native feed acknowledgements; exact products/conservation replayed at every batch. "
                      "Console acknowledgements are not raw pre-processing input snapshots.")


def replay(frozen, freeze_sha256, seal_sha256, budget):
    frozen = Path(frozen).resolve()
    pin(budget.read(frozen / "freeze.json"), freeze_sha256)
    manifest = budget.json(frozen / "freeze.json")
    require(manifest.get("status") == "PASS" and manifest.get("format") == 1
            and manifest.get("purpose") == "NATIVE61_STOPPED_ENDURANCE_FREEZE"
            and manifest.get("sealSha256") == seal_sha256.upper(), "Incomplete or different frozen evidence")
    sealed = verify_seal(frozen / "seal.json", seal_sha256, budget)
    run = frozen / "run"
    require(tree(run, budget) == manifest["files"], "Frozen file inventory changed")
    pin(budget.read(run / RECEIPT), manifest["inputReceiptSha256"])
    report = budget.json(run / RECEIPT)
    complete(report)
    provenance = custody(sealed, run, budget)
    require(manifest.get("custody") == provenance, "Frozen custody attestation changed")
    require(report["profileSha256"] == sealed["files"][sealed["profile"]]
            and report["stationDefinitionSha256"] == sealed["files"][sealed["specification"]]
            and report["inventorySha256"] == sealed["files"][sealed["inventory"]]
            and report["javaSha256"] == sealed["files"][sealed["java"]]
            and report["javaVersion"] == sealed["javaVersion"]
            and report["jars"] == {row["id"]: row["sha256"].upper()
                                    for row in budget.json(sealed["profile"])["mods"]},
            "Run differs from pre-run seal")

    class BoundedForge61(Forge61):
        parse_snapshot = staticmethod(snapshot)

        @staticmethod
        def verify_batch(runtime, label, documents, definitions):
            budget.check()
            Forge61.verify_batch(runtime, label, documents, definitions)

    replay_checkpoints = bind(checkpoint_audit.replay, read_json=budget.json, digest=budget.digest)
    measured = replay_checkpoints(run, Path(sealed["profile"]), Path(sealed["specification"]), adapter=BoundedForge61)
    raw, feeds = {}, {}
    for mode in MODES:
        inventory = budget.json(run / mode / native.INVENTORY)
        require(inventory == budget.json(sealed["inventory"]), "Runtime code inventory differs from sealed seed")
        require(not list((run / mode).glob("native61-launch-environment-*.json")), "Diagnostic launch is not qualified")
        raw[mode], feeds[mode] = raw_final(run / mode, budget), feed_audit(run / mode, budget)
    require(tree(run, budget) == manifest["files"], "Frozen files changed during replay")
    verify_seal(frozen / "seal.json", seal_sha256, budget)
    return dict(status=("PASS" if provenance["preRunCustody"]
                        else "PASS_RETROSPECTIVE_REPLAY_NOT_PRE_RUN_CUSTODY"),
                qualificationAllowed=provenance["preRunCustody"], custody=provenance,
                scope="Independent stopped native61 raw and all-batch checkpoint replay; not client acceptance",
                inputFreezeSha256=freeze_sha256.upper(), inputSealSha256=seal_sha256.upper(),
                inputReceiptSha256=manifest["inputReceiptSha256"], checkpointReplay=measured,
                stoppedRaw=raw, nativeFeeds=feeds)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    sealing = commands.add_parser("seal", help="Before run: pin the candidate, Java and imported local oracles")
    for name in ("profile", "specification", "inventory", "java"):
        sealing.add_argument("--" + name, required=True, type=Path)
    for name in ("profile-sha256", "inventory-sha256"):
        sealing.add_argument("--" + name, required=True)
    sealing.add_argument("--retrospective", action="store_true", help="Never establishes pre-run oracle custody")
    freezing = commands.add_parser("freeze", help="After complete PASS and stop: copy and inventory every file")
    freezing.add_argument("--run", required=True, type=Path)
    freezing.add_argument("--seal", required=True, type=Path)
    freezing.add_argument("--receipt-sha256", required=True)
    replaying = commands.add_parser("replay", help="Read-only replay of the frozen copy")
    replaying.add_argument("--frozen", required=True, type=Path)
    replaying.add_argument("--freeze-sha256", required=True)
    for command in (freezing, replaying):
        command.add_argument("--seal-sha256", required=True)
    for command in (sealing, freezing, replaying):
        command.add_argument("--output", required=True, type=Path)
        command.add_argument("--max-seconds", type=int, default=300)
    args = parser.parse_args()
    budget = Budget(args.max_seconds)
    if args.command == "seal":
        result = seal(args.profile, args.profile_sha256, args.specification, args.inventory,
                      args.inventory_sha256, args.java, budget, retrospective=args.retrospective)
        disjoint(args.output, *(Path(name) for name in result["files"]))
        write_new(args.output, result)
        print(("RETROSPECTIVE_NOT_PRE_RUN " if args.retrospective else "SEALED ") + budget.digest(args.output))
    elif args.command == "freeze":
        freeze(args.run, args.seal, args.seal_sha256, args.receipt_sha256, args.output, budget)
        print("FROZEN " + budget.digest(args.output / "freeze.json"))
    else:
        sealed = budget.json(args.frozen / "seal.json")
        disjoint(args.output, args.frozen, *(Path(name) for name in sealed["files"]))
        result = dict(status="FAIL", inputFreezeSha256=args.freeze_sha256)
        try:
            result = replay(args.frozen, args.freeze_sha256, args.seal_sha256, budget)
        except BaseException as error:
            result["failure"] = repr(error)
            raise
        finally:
            write_new(args.output, result)
        print(result["status"] + ": frozen native61 endurance replay")
        return 0 if result["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
