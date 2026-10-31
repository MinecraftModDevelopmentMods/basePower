"""Retain and corroborate the actual FMLServerStoppingEvent capture after close.

Only native117_entity_storage.audit performs entity comparisons; no live record,
expected projection, ignored fields or tick-drift allowance is substituted here.
"""

import hashlib
import json
from pathlib import Path
import uuid

from matrix import WORLD
import native117_entity_storage as entity_storage

SNAPSHOT = "native117-stopped-entities.json"
DIMENSIONS = {"minecraft:overworld": (0, ""), "minecraft:the_nether": (-1, "DIM-1"), "minecraft:the_end": (1, "DIM1")}


def _sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate stopping snapshot JSON key: " + key)
        result[key] = value
    return result


def audit_stopped(runtime, evidence_folder):
    """Call after clean process shutdown; retain a NEW immutable snapshot copy.

    Empty dimensions are explicit skips, not inferred-empty saves. An all-empty
    capture returns NO_NONEMPTY_DIMENSIONS, never a vacuous entity-proof PASS.
    This checks final saved NBT, not the earlier live fixture's expected behavior.
    """
    runtime = Path(runtime).resolve(strict=True)
    source = runtime / SNAPSHOT
    world = (runtime / WORLD).resolve(strict=True)
    evidence = Path(evidence_folder).resolve()
    if (evidence.is_relative_to(world) or world.is_relative_to(evidence)
            or source.is_relative_to(evidence) or evidence == source):
        raise ValueError("Stopped-entity evidence overlaps protected inputs")
    evidence.mkdir(parents=True, exist_ok=False)
    receipt = dict(format=1, status="FAIL", scope="STOP_BOUNDARY_SAVED_ENTITY_NBT_ONLY", dimensions={})
    try:
        if not any((runtime / name).is_file() and not (runtime / name).is_symlink() for name in
                   ("advantage-migration.allow", "advantage-machine-operation.allow")):
            raise ValueError("Runtime is not explicitly marked for Advantage Works observation")
        if source.is_symlink() or not source.is_file():
            raise ValueError("No regular native stopping snapshot")
        data = source.read_bytes()
        receipt["snapshotSha256"] = _sha(data)
        with (evidence / SNAPSHOT).open("xb") as output:
            output.write(data)
        document = json.loads(data, object_pairs_hook=_unique_object)
        if (not isinstance(document, dict) or type(document.get("format")) is not int or document["format"] not in (1, 2)
                or document.get("status") != "CAPTURED" or document.get("phase") != "FMLServerStoppingEvent"
                or document.get("minecraftVersion") != "1.17.1" or type(document.get("dataVersion")) is not int
                or document["dataVersion"] != 2730 or document.get("worldName") != "AdvantageWorksMigration"
                or document.get("enumeration") != "ServerLevel.getAllEntities"):
            raise ValueError("Not a complete native stopping-event snapshot")
        if str(uuid.UUID(document.get("bootId", ""))) != document["bootId"]:
            raise ValueError("Invalid stopping snapshot boot UUID")
        if Path(document.get("worldDirectory", "")).resolve() != world:
            raise AssertionError("Stopping snapshot belongs to another world directory")
        dimensions = document.get("dimensions")
        if not isinstance(dimensions, dict) or set(dimensions) != set(DIMENSIONS):
            raise ValueError("Stopping snapshot must cover all three vanilla dimensions")
        if document['format'] == 2:
            from native117_helper_contract import CONTRACT, LIFECYCLE, validate
            contract = (runtime / CONTRACT).read_bytes()
            (evidence / CONTRACT).write_bytes(contract)
            lifecycle = None
            if document.get('nonpersistentHelperCount'):
                candidates = []
                for path in runtime.glob('dimensions-*.json'):
                    value = json.loads(path.read_bytes(), object_pairs_hook=_unique_object)
                    key = value.get('worldDimensionKey')
                    if (key in dimensions and type(value.get('gameTime')) is int
                            and value['gameTime'] <= dimensions[key]['gameTime']):
                        candidates.append((value['gameTime'], path.name, path, value))
                if not candidates:
                    raise AssertionError('No actual dimension helper lifecycle observation')
                _, _, path, lifecycle = max(candidates, key=lambda row: row[:2])
                (evidence / LIFECYCLE).write_bytes(path.read_bytes())
            receipt.update(format=2, helperLifecycle=validate(document, contract, lifecycle))
        count, matched, audited = 0, set(), 0
        for key, (legacy_id, directory) in DIMENSIONS.items():
            record = dimensions[key]
            if (not isinstance(record, dict) or record.get("worldDimensionKey") != key
                    or type(record.get("dimension")) is not int or record["dimension"] != legacy_id
                    or type(record.get("gameTime")) is not int or not isinstance(record.get("entities"), list)
                    or type(record.get("entityCount")) is not int or record["entityCount"] != len(record["entities"])):
                raise ValueError("Incomplete native dimension stopping record: " + key)
            count += len(record["entities"])
            for entry in record["entities"]:
                if (not isinstance(entry, dict) or entry.get("worldDimensionKey") != key
                        or entry.get("id") == "minecraft:player" or not isinstance(entry.get("uuid"), str)):
                    raise ValueError("Invalid non-player dimension observation")
            if not record["entities"]:
                receipt["dimensions"][key] = dict(status="SKIP_EMPTY_STOP_SNAPSHOT", entityCount=0)
                continue
            # Decode actual recorded NBT, never an expected fixture projection.
            _, native_rows, _ = entity_storage._snapshots(record)
            if any(entry.get("id") != str(native_rows[entry["uuid"]]["id"]) for entry in record["entities"]):
                raise AssertionError("Stopping entity type attestation differs from its complete native NBT")
            result = entity_storage.audit(record, world / directory, evidence / (directory or "overworld"))
            if result.get("status") != "PASS":
                raise AssertionError("Raw entity audit did not pass: " + key)
            identities = set(result["matched"])
            if identities & matched:
                raise AssertionError("Duplicate saved entity UUID across dimensions")
            matched.update(identities)
            receipt["dimensions"][key] = result
            audited += 1
        if type(document.get("entityCount")) is not int or document["entityCount"] != count:
            raise AssertionError("Stopping snapshot entity count differs from dimension records")
        if source.read_bytes() != data:
            raise AssertionError("Stopping snapshot changed during the after-close audit")
        if document['format'] == 2:
            from native117_helper_contract import raw_helpers_absent
            raw_helpers_absent({p.relative_to(evidence).as_posix(): p.read_bytes()
                for p in evidence.rglob('*.mca')}, receipt['helperLifecycle']['helpers'])
        receipt.update(status="PASS" if audited else "NO_NONEMPTY_DIMENSIONS", bootId=document["bootId"],
                       auditedDimensions=audited, snapshotRecords=count, matchedEntities=len(matched))
    except Exception as error:
        receipt["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        with (evidence / "receipt.json").open("x", encoding="utf-8") as output:
            json.dump(receipt, output, indent=2, sort_keys=True)
            output.write("\n")
    return receipt
