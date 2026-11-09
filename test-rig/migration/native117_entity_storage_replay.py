"""Read-only replay of retained audit_stopped evidence, without the source world.

Every allocated region payload is reparsed with native117_entity_storage. PASS
and matching hashes alone cannot replace complete NBT/type/UUID comparisons.
Optional external snapshot/receipt pins bind provenance: a self-contained archive
cannot detect a coherent replacement of every input and every claimed checksum.
This module never writes files, stages a world, calls audit(), or starts a server.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import uuid

from matrix import plain
import native117_entity_storage as native
from native117_format import dimension_id
from native117_stopped_entities import DIMENSIONS, SNAPSHOT


def _sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def _json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate retained JSON key: " + key)
            result[key] = value
        return result

    def invalid(value):
        raise ValueError("Nonfinite retained JSON number: " + value)

    return json.loads(data, object_pairs_hook=unique, parse_constant=invalid)


def _same(expected, actual, label):
    # Python equality would accept forged boolean counters such as True == 1.
    def encoded(value):
        return json.dumps(value, sort_keys=True, allow_nan=False, separators=(",", ":"))
    if encoded(expected) != encoded(actual):
        raise AssertionError("Retained metadata differs from rederived evidence: " + label)


def _tree(root):
    files, directories, pending = {}, set(), [root]
    while pending:
        for path in sorted(pending.pop().iterdir()):
            if (path.is_symlink() or getattr(path, "is_junction", lambda: False)()
                    or not path.resolve().is_relative_to(root)):
                raise ValueError("Retained evidence contains an alias outside its regular tree")
            name = path.relative_to(root).as_posix()
            if path.is_dir():
                directories.add(name)
                pending.append(path)
            elif path.is_file():
                files[name] = path.read_bytes()
            else:
                raise ValueError("Retained evidence contains a non-regular file")
    return files, directories


def _pin(data, expected, label):
    if expected is not None and (not isinstance(expected, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", expected)
                                 or _sha(data) != expected.upper()):
        raise AssertionError("External frozen evidence pin differs: " + label)


def _world(value):
    if not isinstance(value, str):
        raise ValueError("Missing retained world directory")
    path = PureWindowsPath(value) if PureWindowsPath(value).drive else PurePosixPath(value)
    if not path.is_absolute() or ".." in path.parts or path.name != "AdvantageWorksMigration":
        raise ValueError("Noncanonical retained world directory")
    return path


def _dimension(files, prefix, record, dimension, world):
    receipt_name = prefix + "/receipt.json"
    stored = _json(files[receipt_name])
    snapshot_name = prefix + "/snapshot.json"
    _same(record, _json(files[snapshot_name]), snapshot_name)
    expected, rows, roots = native._snapshots(record)
    if any(entry.get("id") != str(rows[entry["uuid"]]["id"]) for entry in record["entities"]):
        raise AssertionError("Retained type attestation differs from actual native NBT")
    source_files, payloads, retained = {}, {}, {snapshot_name}
    saved, root_chunks = {}, {}
    region_names = sorted(name for name in files if name.startswith(prefix + "/regions/"))
    if not region_names:
        raise ValueError("No retained native regions for nonempty dimension")
    for name in region_names:
        basename = name.removeprefix(prefix + "/regions/")
        match = native.REGION_NAME.fullmatch(basename)
        if match is None:
            raise ValueError("Unsafe retained region filename")
        rx, rz = map(int, match.groups())
        if basename != "r.%d.%d.mca" % (rx, rz) or any(not -(1 << 26) <= value < (1 << 26) for value in (rx, rz)):
            raise ValueError("Noncanonical retained region coordinates")
        source_files[basename] = _sha(files[name])
        retained.add(name)
        for coordinates, payload in native._payloads(files[name], rx, rz):
            relative = "chunks/%d.%d.chunk" % coordinates
            retained_name = prefix + "/" + relative
            if retained_name not in files:
                raise AssertionError("Missing retained payload recovered from actual region: " + retained_name)
            if files[retained_name] != payload:
                raise AssertionError("Retained payload differs byte-for-byte from its region allocation")
            retained.add(retained_name)
            payloads[relative] = dict(position=list(coordinates), sha256=_sha(payload), source=basename)
            for entity in native._entity_chunk(payload, coordinates):
                root_id = native._identity(entity)
                if native._chunk(entity) != coordinates:
                    raise AssertionError("Saved root Pos differs from retained entity chunk")
                root_chunks[root_id] = coordinates
                for identity, tag, parent in native._tree(entity):
                    if identity in saved:
                        raise AssertionError("Duplicate UUID in retained entity storage: " + identity)
                    if "Dimension" in tag and dimension_id(plain(tag["Dimension"].unpack())) != dimension:
                        raise AssertionError("Retained NBT dimension differs from observed dimension")
                    saved[identity] = dict(nbt=native._semantic(tag), root=root_id, chunk=coordinates)
    for identity, entity in roots.items():
        if identity not in root_chunks or root_chunks[identity] != native._chunk(entity):
            raise AssertionError("Retained snapshot root is absent from its actual saved Pos chunk")
    matches = {}
    for identity, semantic in expected.items():
        if identity not in saved or saved[identity]["nbt"] != semantic:
            raise AssertionError("Complete retained entity NBT/type differs from stopping snapshot: " + identity)
        entry = saved[identity]
        matches[identity] = dict(rootUuid=entry["root"], chunk=list(entry["chunk"]),
            payload="chunks/%d.%d.chunk" % entry["chunk"], separatelyObserved=identity in rows)
    derived = dict(format=1, status="PASS", scope="EXACT_STOPPED_ENTITY_NBT_ONLY", world=str(world),
        callerMustStopServer=True,
        files={name.removeprefix(prefix + "/"): _sha(files[name]) for name in sorted(retained)},
        sourceFiles=source_files, chunks=payloads, matched=matches, snapshotRecords=len(rows),
        matchedEntities=len(expected), rawEntities=len(saved), unrelatedEntities=len(set(saved) - set(expected)),
        dimension=dimension)
    _same(derived, stored, receipt_name)
    return derived, retained | {receipt_name}


def replay(evidence_folder, *, expected_snapshot_sha256=None, expected_receipt_sha256=None):
    """Rederive complete saved-state proof from a retained audit_stopped folder.

    Returns a JSON-safe result and hashes EVERY retained file, including receipts.
    Raises on malformed, missing, extra, changed or semantically inconsistent data.
    Nothing is written and the original runtime/world need not exist. All-empty
    evidence remains NO_NONEMPTY_DIMENSIONS, never upgraded to an entity PASS.
    """
    given = Path(evidence_folder)
    if given.is_symlink() or getattr(given, "is_junction", lambda: False)():
        raise ValueError("Replay root must not be an alias")
    root = given.resolve(strict=True)
    files, directories = _tree(root)
    if "receipt.json" not in files or SNAPSHOT not in files:
        raise ValueError("Missing retained stopping snapshot or receipt")
    _pin(files[SNAPSHOT], expected_snapshot_sha256, SNAPSHOT)
    _pin(files["receipt.json"], expected_receipt_sha256, "receipt.json")
    stored = _json(files["receipt.json"])
    document = _json(files[SNAPSHOT])
    if (not isinstance(document, dict) or type(document.get("format")) is not int or document["format"] not in (1, 2)
            or document.get("status") != "CAPTURED" or document.get("phase") != "FMLServerStoppingEvent"
            or document.get("minecraftVersion") != "1.17.1" or type(document.get("dataVersion")) is not int
            or document["dataVersion"] != 2730 or document.get("worldName") != "AdvantageWorksMigration"
            or document.get("enumeration") != "ServerLevel.getAllEntities"):
        raise ValueError("Not a complete retained native stopping-event snapshot")
    if not isinstance(document.get("bootId"), str) or str(uuid.UUID(document["bootId"])) != document["bootId"]:
        raise ValueError("Malformed retained boot UUID")
    world = _world(document.get("worldDirectory"))
    records = document.get("dimensions")
    if not isinstance(records, dict) or set(records) != set(DIMENSIONS):
        raise ValueError("Retained snapshot does not cover all three dimensions")
    helper_lifecycle = None
    if document['format'] == 2:
        from native117_helper_contract import CONTRACT, LIFECYCLE, validate, raw_helpers_absent
        helper_lifecycle = validate(document, files[CONTRACT], _json(files[LIFECYCLE]) if LIFECYCLE in files else None)
        raw_helpers_absent(files, helper_lifecycle['helpers'])
    dimensions, matched, count, audited = {}, set(), 0, 0
    retained = {"receipt.json", SNAPSHOT}
    if document['format'] == 2:
        retained.add(CONTRACT)
        if LIFECYCLE in files:
            retained.add(LIFECYCLE)
    for key, (legacy, relative) in DIMENSIONS.items():
        record = records[key]
        if (not isinstance(record, dict) or record.get("worldDimensionKey") != key
                or type(record.get("dimension")) is not int or record["dimension"] != legacy
                or type(record.get("gameTime")) is not int or not isinstance(record.get("entities"), list)
                or type(record.get("entityCount")) is not int or record["entityCount"] != len(record["entities"])):
            raise ValueError("Malformed retained dimension record: " + key)
        count += len(record["entities"])
        for entry in record["entities"]:
            if (not isinstance(entry, dict) or entry.get("worldDimensionKey") != key
                    or entry.get("id") == "minecraft:player" or not isinstance(entry.get("uuid"), str)):
                raise ValueError("Invalid retained non-player dimension observation")
        if not record["entities"]:
            dimensions[key] = dict(status="SKIP_EMPTY_STOP_SNAPSHOT", entityCount=0)
            continue
        derived, paths = _dimension(files, relative or "overworld", record, legacy, world / relative)
        identities = set(derived["matched"])
        if matched & identities:
            raise AssertionError("Duplicate retained entity UUID across dimensions")
        matched.update(identities)
        dimensions[key] = derived
        retained.update(paths)
        audited += 1
    if type(document.get("entityCount")) is not int or document["entityCount"] != count:
        raise AssertionError("Retained snapshot total entity count differs from actual records")
    derived = dict(format=1, status="PASS" if audited else "NO_NONEMPTY_DIMENSIONS",
        scope="STOP_BOUNDARY_SAVED_ENTITY_NBT_ONLY", dimensions=dimensions,
        snapshotSha256=_sha(files[SNAPSHOT]), bootId=document["bootId"],
        auditedDimensions=audited, snapshotRecords=count, matchedEntities=len(matched))
    if document['format'] == 2:
        derived.update(format=2, helperLifecycle=helper_lifecycle)
    _same(derived, stored, "root receipt")
    if set(files) != retained:
        raise AssertionError("Unexpected or missing retained files")
    expected_dirs = {parent.as_posix() for name in retained for parent in PurePosixPath(name).parents if parent != PurePosixPath(".")}
    if directories != expected_dirs:
        raise AssertionError("Unexpected or missing retained directories")
    if _tree(root) != (files, directories):
        raise AssertionError("Retained evidence changed during read-only replay")
    return dict(status=derived["status"], scope="READ_ONLY_STOPPED_ENTITY_STORAGE_REPLAY",
        snapshotSha256=derived["snapshotSha256"], receiptSha256=_sha(files["receipt.json"]),
        files={name: _sha(data) for name, data in sorted(files.items())}, bootId=document["bootId"],
        auditedDimensions=audited, snapshotRecords=count, matchedEntities=len(matched), dimensions=dimensions,
        externallyPinnedSnapshot=expected_snapshot_sha256 is not None, externallyPinnedReceipt=expected_receipt_sha256 is not None)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--snapshot-sha256")
    parser.add_argument("--receipt-sha256")
    args = parser.parse_args()
    print(json.dumps(replay(args.evidence, expected_snapshot_sha256=args.snapshot_sha256,
        expected_receipt_sha256=args.receipt_sha256), sort_keys=True, indent=2))
