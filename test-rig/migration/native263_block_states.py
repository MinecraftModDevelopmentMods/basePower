"""26.3 BlockState.CODEC projections, separate from historical palette semantics."""

import copy
import hashlib
import json
import math
from pathlib import Path
import re

from native117_format import decode_palette as padded_palette


IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
PROPERTY = re.compile(r"[a-z0-9_]+")
VALUE = re.compile(r"[a-z0-9_-]+")
CATALOG_FILE = "native263-block-state-catalog.json"


class StateCatalog:
    def __init__(self, document, *, order_probe=False):
        import native263
        versions = dict(native263.VERSIONS, minecraft="26.3", forge="66.0.0")
        if order_probe is True:
            versions["advantageorderprobe"] = "1.0.0.2603001"
        elif order_probe is not False:
            raise ValueError("Order catalog scope must be explicitly boolean")
        required = {"format", "engineProtocol", "minecraft", "forge", "dataVersion", "javaRuntimeVersion",
                    "mods", "modJars", "blockCount", "stateCount", "blocks"}
        if (not isinstance(document, dict) or set(document) != required
                or type(document["format"]) is not int or document["format"] != 1
                or document["engineProtocol"] != "NATIVE66" or document["minecraft"] != "26.3"
                or document["forge"] != "66.0.0" or type(document["dataVersion"]) is not int
                or document["dataVersion"] != 5023 or document["javaRuntimeVersion"] != "25.0.3+9-LTS"
                or document["mods"] != versions):
            raise ValueError("Catalog must describe the exact actual Native66 family and Java runtime")
        jars = document["modJars"]
        if (not isinstance(jars, dict) or len(jars) != (7 if order_probe else 6)
                or any(not isinstance(name, str) or Path(name).name != name or not name.endswith(".jar")
                       or not isinstance(sha, str) or re.fullmatch(r"[A-F0-9]{64}", sha) is None or sha == "0" * 64
                       for name, sha in jars.items())):
            raise ValueError("Catalog must retain all six exact native mod jar hashes")
        blocks = document["blocks"]
        if (not isinstance(blocks, dict) or "minecraft:air" not in blocks or len(blocks) > 100000
                or type(document["blockCount"]) is not int or document["blockCount"] != len(blocks)
                or type(document["stateCount"]) is not int):
            raise ValueError("Missing or inconsistent native block catalog counts")
        count = 0
        for identity, row in blocks.items():
            if (not isinstance(identity, str) or IDENTIFIER.fullmatch(identity) is None
                    or not isinstance(row, dict) or set(row) != {
                        "defaults", "legalValues", "stateCount", "defaultEncoded", "defaultDecoded"}):
                raise ValueError("Unknown or malformed native catalog block")
            defaults, legal = row["defaults"], row["legalValues"]
            if (not isinstance(defaults, dict) or not isinstance(legal, dict) or set(defaults) != set(legal)
                    or len(legal) > 64):
                raise ValueError("Catalog defaults do not cover exactly its legal properties")
            for name, values in legal.items():
                if (not isinstance(name, str) or PROPERTY.fullmatch(name) is None or not isinstance(values, list)
                        or len(values) < 2 or len(values) > 65536
                        or any(not isinstance(value, str) or VALUE.fullmatch(value) is None for value in values)
                        or values != sorted(set(values)) or defaults[name] not in values):
                    raise ValueError("Invalid catalog property values or default")
            states = math.prod(len(values) for values in legal.values())
            if (type(row["stateCount"]) is not int or row["stateCount"] != states
                    or row["defaultEncoded"] != identity
                    or row["defaultDecoded"] != dict(id=identity, properties=defaults)):
                raise ValueError("Catalog native default CODEC round-trip or state count differs")
            count += states
        if document["stateCount"] != count:
            raise ValueError("Catalog total native state count differs")
        self.document = copy.deepcopy(document)
        self.blocks = self.document["blocks"]


def load_catalog(runtime, *, read_bytes=None):
    from matrix import digest
    import native263
    runtime = Path(runtime)
    path = runtime / CATALOG_FILE
    for part in (path, *path.parents):
        if part.is_symlink() or getattr(part, "is_junction", lambda: False)():
            raise ValueError("Aliased native block catalog")
    if path.stat().st_nlink != 1:
        raise ValueError("Hardlinked native block catalog")
    raw = read_bytes(path) if read_bytes else path.read_bytes()
    if len(raw) > 16 * 1024 * 1024:
        raise ValueError("Oversized native block catalog")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate native catalog field: " + key)
            result[key] = value
        return result
    document = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique,
                          parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite catalog: " + value)))
    order_probe = False
    if isinstance(document, dict) and isinstance(document.get("mods"), dict) and "advantageorderprobe" in document["mods"]:
        from native263_transformer_order import MOD_ID, VERSION, SCOPE, verify_probe_package
        name = "AdvantageOrderProbe-" + VERSION + ".jar"
        inputs = native263.read_json(runtime / "input-jars.json")
        approved = [row for row in inputs if row.get("file") == name]
        if (len(inputs) != 7 or len(approved) != 1 or approved[0].get("scope") != SCOPE
                or approved[0].get("qualificationAllowed") is not False or document["mods"][MOD_ID] != VERSION):
            raise ValueError("Catalog order sidecar needs its explicit pinned nonqualifying staging protocol")
        native263.validate_hash(runtime / "mods" / name, approved[0]["sha256"])
        verify_probe_package(runtime / "mods" / name)
        order_probe = True
    catalog = StateCatalog(document, order_probe=order_probe)
    jars = {entry.name: hashlib.sha256(read_bytes(entry) if read_bytes else entry.read_bytes()).hexdigest().upper()
            for entry in (runtime / "mods").iterdir() if entry.is_file()}
    if document["modJars"] != jars:
        raise AssertionError("Native block catalog does not bind the actual staged six-jar family")
    catalog.path, catalog.sha256 = path, hashlib.sha256(raw).hexdigest().upper()
    return catalog


def block_state(value, *, catalog):
    """Project only an actual native string or id/properties compound, never an expectation."""
    if isinstance(value, str):
        identity, properties = value, {}
    elif isinstance(value, dict):
        if set(value) - {"id", "properties"} or "id" not in value:
            raise ValueError("Native263 block state requires id/properties, not historical keys")
        identity, properties = value["id"], value.get("properties", {})
        if not isinstance(properties, dict):
            raise ValueError("Native block-state properties must be a compound")
    else:
        raise ValueError("Native block state must be a compact identifier or compound")
    if not isinstance(identity, str) or IDENTIFIER.fullmatch(identity) is None:
        raise ValueError("Native block state requires a namespaced identifier")
    if any(not isinstance(key, str) or PROPERTY.fullmatch(key) is None
           or not isinstance(item, str) or VALUE.fullmatch(item) is None for key, item in properties.items()):
        raise ValueError("Native block-state properties require exact string values")
    if not isinstance(catalog, StateCatalog) or identity not in catalog.blocks:
        raise ValueError("Unknown native palette identity or missing independent registry catalog")
    entry = catalog.blocks[identity]
    for name, item in properties.items():
        if name not in entry["legalValues"] or item not in entry["legalValues"][name]:
            raise ValueError("Unknown native palette property or illegal value")
    resolved = dict(entry["defaults"])
    resolved.update(properties)
    return dict(Name=str(identity), Properties=resolved)


def decode_palette(palette, words, count=4096, *, catalog):
    return padded_palette([palette_state(entry, catalog=catalog) for entry in palette], words, count)


def palette_state(value, *, catalog):
    # Native ListTag.write wraps non-compounds in heterogeneous lists; its reader
    # unwraps exactly one empty-key compound. nbtlib deliberately retains raw NBT.
    if isinstance(value, dict) and set(value) == {""}:
        return block_state(value[""], catalog=catalog)
    return block_state(value, catalog=catalog)
