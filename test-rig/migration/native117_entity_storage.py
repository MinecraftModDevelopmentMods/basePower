"""Read-only corroboration of stopped 1.17.1 entity-region saves.

Call audit(record, dimension_directory, NEW_evidence_directory) only after save,
flush and process shutdown. dimension_directory is WORLD, WORLD/DIM-1 or
WORLD/DIM1, not its region/entities child. Player files are outside this API.
The receipt proves exact saved witness NBT, not ticking or migration correctness.

Source: Forge 37.1.1 injected-sources.jar,
net/minecraft/world/level/chunk/storage/EntityStorage.java, SHA-256
1D597C2B42ADBE5C221B4F214386C42FD7C7C77A6BC3CC38F4ECA8B6D26E65C2.
Its storeEntities writes DataVersion, Entities and Position; Position is int[2].
"""

import base64
import hashlib
import io
import json
import math
from pathlib import Path
import re
import uuid
import zlib

import nbtlib

from matrix import plain
from native117_format import actor_nbt, dimension_id, uuid_longs

DATA_VERSION = 2730
MAX_NBT_BYTES = 16 * 1024 * 1024
ENTITY_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
REGION_NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca")


def _sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def _decompress(data, compression):
    if compression == 3:
        result = data
    elif compression in (1, 2):
        decoder = zlib.decompressobj(31 if compression == 1 else 15)
        result = decoder.decompress(data, MAX_NBT_BYTES + 1)
        if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            raise ValueError("Truncated, trailing or excessive compressed entity NBT")
    else:
        raise ValueError("Unsupported entity compression (external .mcc is not supported)")
    if len(result) > MAX_NBT_BYTES:
        raise ValueError("Entity NBT exceeds the explicit 16 MiB audit limit")
    return result


def _nbt(data):
    stream = io.BytesIO(data)
    result = nbtlib.File.parse(stream)
    if stream.read(1) or result.root_name:
        raise ValueError("Trailing bytes or named root in native entity NBT")
    encoded = io.BytesIO()
    result.write(encoded)
    # A duplicate compound key must not disappear through a dictionary parser.
    if encoded.getvalue() != data:
        raise ValueError("Entity NBT cannot round-trip exactly (duplicate keys or unsupported encoding)")
    return result


def _types(value):
    if isinstance(value, nbtlib.Compound):
        result = {key: _types(tag) for key, tag in value.items()}
        if "UUID" in value:
            result.pop("UUID")
            result.update(UUIDMost=nbtlib.Long.tag_id, UUIDLeast=nbtlib.Long.tag_id)
        if "Owner" in value and isinstance(value["Owner"], (nbtlib.List, nbtlib.IntArray)):
            result.pop("Owner")
            result["OwnerUUID"] = nbtlib.String.tag_id
        for key in ("Dimension", "SpawnDimension"):
            if key in result:
                result[key] = "finite-dimension-identity"
        return result
    if isinstance(value, nbtlib.List):
        return [value.tag_id, value.subtype.tag_id, [_types(entry) for entry in value]]
    return value.tag_id


def _semantic(value):
    # No actors.semantic/project_entity/normalized: those are expected-field projections.
    # This preserves every value and tag type except actor_nbt's finite encodings.
    return actor_nbt(plain(value.unpack())), _types(value)


def _identity(entity):
    if (not isinstance(entity, nbtlib.Compound) or not isinstance(entity.get("id"), nbtlib.String)
            or not ENTITY_ID.fullmatch(str(entity["id"]))):
        raise ValueError("Saved entity needs a namespaced id")
    words = entity.get("UUID")
    if not isinstance(words, nbtlib.IntArray) or len(words) != 4 or any(key in entity for key in ("UUIDMost", "UUIDLeast")):
        raise ValueError("Saved entity UUID must be exactly a native int[4]")
    most, least = uuid_longs([int(word) for word in words])
    return str(uuid.UUID(int=((most & ((1 << 64) - 1)) << 64) | (least & ((1 << 64) - 1))))


def _chunk(entity):
    pos = entity.get("Pos")
    if (not isinstance(pos, nbtlib.List) or pos.subtype is not nbtlib.Double or len(pos) != 3
            or any(not math.isfinite(float(value)) for value in pos)):
        raise ValueError("Saved entity Pos must contain three finite native doubles")
    result = (math.floor(float(pos[0]) / 16), math.floor(float(pos[2]) / 16))
    if any(not -(1 << 31) <= coordinate < (1 << 31) for coordinate in result):
        raise ValueError("Saved entity chunk coordinate is outside signed int range")
    return result


def _tree(entity, parent=None, seen=None):
    seen = set() if seen is None else seen
    identity = _identity(entity)
    if identity in seen:
        raise AssertionError("Duplicate UUID in saved passenger tree: " + identity)
    seen.add(identity)
    _chunk(entity)
    yield identity, entity, parent
    if "Passengers" in entity:
        passengers = entity["Passengers"]
        if not isinstance(passengers, nbtlib.List) or passengers.subtype not in (nbtlib.Compound, nbtlib.End):
            raise ValueError("Passengers must be a native compound list")
        for passenger in passengers:
            yield from _tree(passenger, identity, seen)


def _payloads(data, rx, rz):
    # Forge 37.1.1 RegionFile opens read probes with CREATE. An EOF header read
    # is valid, and close's ceil(size/4096) padding leaves size zero unchanged.
    # This is not a truncated header: any NONEMPTY short region still fails.
    # RegionFile.java source SHA-256: 4C1A925B2868584E3F94198B146C7BE59D0000CAA88210E2381AF2FA9802D72B.
    if not data:
        return
    if len(data) < 8192 or len(data) % 4096:
        raise ValueError("Malformed entity region length")
    occupied = {0, 1}
    for slot in range(1024):
        location = int.from_bytes(data[slot * 4:slot * 4 + 4], "big")
        offset, count = location >> 8, location & 255
        if not offset and not count:
            continue
        if offset < 2 or not count or offset + count > len(data) // 4096:
            raise ValueError("Invalid entity region sector allocation")
        sectors = set(range(offset, offset + count))
        if occupied & sectors:
            raise ValueError("Overlapping entity region sector allocation")
        occupied.update(sectors)
        start = offset * 4096
        length = int.from_bytes(data[start:start + 4], "big")
        if length < 2 or length + 4 > count * 4096:
            raise ValueError("Malformed entity chunk payload length")
        yield (rx * 32 + (slot & 31), rz * 32 + (slot >> 5)), data[start:start + 4 + length]


def _entity_chunk(payload, coordinates, version=DATA_VERSION):
    if len(payload) < 6 or int.from_bytes(payload[:4], "big") != len(payload) - 4:
        raise ValueError("Malformed entity chunk payload")
    root = _nbt(_decompress(payload[5:], payload[4]))
    if set(root) != {"DataVersion", "Position", "Entities"}:
        raise ValueError("Unexpected EntityStorage root keys (terrain Level is not entity storage)")
    if not isinstance(root["DataVersion"], nbtlib.Int) or int(root["DataVersion"]) != version:
        raise ValueError(f"EntityStorage must have native DataVersion {version}")
    position = root["Position"]
    if not isinstance(position, nbtlib.IntArray) or len(position) != 2 or tuple(map(int, position)) != coordinates:
        raise ValueError("EntityStorage Position int[2] differs from its actual region slot")
    entities = root["Entities"]
    if not isinstance(entities, nbtlib.List) or entities.subtype not in (nbtlib.Compound, nbtlib.End):
        raise ValueError("EntityStorage Entities must be a native compound list")
    return entities


def _snapshots(record):
    if not isinstance(record, dict) or not isinstance(record.get("entities"), list) or not record["entities"]:
        raise ValueError("A nonempty actual native entity snapshot record is required")
    expected, rows, metadata, children = {}, {}, {}, set()
    for entry in record["entities"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("nbtBase64"), str):
            raise ValueError("Snapshot needs actual nbtBase64, not a semantic projection")
        entity = _nbt(_decompress(base64.b64decode(entry["nbtBase64"], validate=True), 1))
        identity = _identity(entity)
        if identity in rows:
            raise AssertionError("Duplicate snapshot UUID record: " + identity)
        if "uuid" in entry and entry["uuid"] != identity:
            raise AssertionError("Snapshot UUID attestation differs from actual NBT")
        rows[identity] = entity
        metadata[identity] = entry
        for key, tag, parent in _tree(entity):
            semantic = _semantic(tag)
            if key in expected and expected[key] != semantic:
                raise AssertionError("Inconsistent overlapping snapshot passenger NBT: " + key)
            expected[key] = semantic
            if parent is not None:
                children.add(key)
    roots = {key: tag for key, tag in rows.items() if key not in children}
    if not roots:
        raise ValueError("Snapshot has no serialized entity roots")
    for identity, entry in metadata.items():
        if identity in roots and entry.get("vehicleUuid") is not None:
            raise ValueError("Snapshot omits a passenger's serialized vehicle root")
    return expected, rows, roots


def audit(snapshot_record, world_path, evidence_folder, *, data_version=DATA_VERSION):
    """Retain stopped raw regions and require complete NBT equality for every UUID.

    world_path is the explicit dimension directory. All its entity regions are
    scanned, so duplicates in another region cannot hide outside witness chunks.
    Unrelated entities are counted, not claimed as snapshot witnesses. External
    .mcc and oversized/decompression-bomb payloads fail closed rather than skip.
    Returns a JSON-safe receipt; malformed or mismatched data raises and retains
    a FAIL receipt. Existing evidence is never overwritten. No source is written.
    """
    world = Path(world_path).resolve(strict=True)
    evidence = Path(evidence_folder).resolve()
    if not world.is_dir() or evidence.is_relative_to(world) or world.is_relative_to(evidence):
        raise ValueError("Entity evidence must be disjoint from the source dimension")
    evidence.mkdir(parents=True, exist_ok=False)
    receipt = dict(format=1, status="FAIL", scope="EXACT_STOPPED_ENTITY_NBT_ONLY", world=str(world),
                   callerMustStopServer=True, files={}, sourceFiles={}, chunks={}, matched={})

    def retain(relative, data):
        path = evidence / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as output:
            output.write(data)
        receipt["files"][relative] = _sha(data)

    try:
        expected, rows, roots = _snapshots(snapshot_record)
        retain("snapshot.json", (json.dumps(snapshot_record, sort_keys=True, indent=2) + "\n").encode("utf-8"))
        dimension = -1 if world.name == "DIM-1" else 1 if world.name == "DIM1" else 0
        for key in ("dimension", "worldDimensionKey"):
            if key in snapshot_record and dimension_id(snapshot_record[key]) != dimension:
                raise AssertionError("Snapshot dimension disagrees with the explicit dimension directory")
        source = world / "entities"
        if source.is_symlink() or not source.is_dir():
            raise ValueError("Missing regular native entities directory")
        paths = sorted(source.glob("*.mca"))
        if not paths:
            raise ValueError("No native entity regions found")
        saved, root_chunks = {}, {}
        for path in paths:
            match = REGION_NAME.fullmatch(path.name)
            if path.is_symlink() or not path.is_file() or match is None:
                raise ValueError("Unsafe native entity region path")
            rx, rz = map(int, match.groups())
            if path.name != "r.%d.%d.mca" % (rx, rz) or any(not -(1 << 26) <= value < (1 << 26) for value in (rx, rz)):
                raise ValueError("Noncanonical native entity region coordinates")
            data = path.read_bytes()
            receipt["sourceFiles"][path.name] = _sha(data)
            retain("regions/" + path.name, data)
            for coordinates, payload in _payloads(data, rx, rz):
                relative = "chunks/%d.%d.chunk" % coordinates
                retain(relative, payload)
                receipt["chunks"][relative] = dict(position=list(coordinates), sha256=_sha(payload), source=path.name)
                for root in _entity_chunk(payload, coordinates, data_version):
                    root_id = _identity(root)
                    if _chunk(root) != coordinates:
                        raise AssertionError("Root entity saved Pos differs from its EntityStorage chunk")
                    root_chunks[root_id] = coordinates
                    for identity, tag, parent in _tree(root):
                        if identity in saved:
                            raise AssertionError("Duplicate UUID in raw entity storage: " + identity)
                        if "Dimension" in tag and dimension_id(plain(tag["Dimension"].unpack())) != dimension:
                            raise AssertionError("Raw entity NBT dimension differs from source directory")
                        saved[identity] = dict(nbt=_semantic(tag), root=root_id, chunk=coordinates)
        for identity, entity in roots.items():
            coordinates = _chunk(entity)
            if identity not in root_chunks or root_chunks[identity] != coordinates:
                raise AssertionError("Snapshot root UUID is absent from the chunk of its actual saved Pos: " + identity)
        for identity, semantic in expected.items():
            if identity not in saved or saved[identity]["nbt"] != semantic:
                raise AssertionError("Complete saved entity NBT differs from actual snapshot: " + identity)
            entry = saved[identity]
            receipt["matched"][identity] = dict(rootUuid=entry["root"], chunk=list(entry["chunk"]),
                payload="chunks/%d.%d.chunk" % entry["chunk"], separatelyObserved=identity in rows)
        if sorted(source.glob("*.mca")) != paths or any(_sha(path.read_bytes()) != receipt["sourceFiles"][path.name] for path in paths):
            raise AssertionError("Source entity storage changed during the stopped-world audit")
        receipt.update(status="PASS", snapshotRecords=len(rows), matchedEntities=len(expected), rawEntities=len(saved),
                       unrelatedEntities=len(set(saved) - set(expected)), dimension=dimension)
    except Exception as error:
        receipt["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        with (evidence / "receipt.json").open("x", encoding="utf-8") as output:
            json.dump(receipt, output, sort_keys=True, indent=2)
            output.write("\n")
    return receipt
