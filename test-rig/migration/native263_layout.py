"""Explicit 26.3 target paths and lossless stopped-save readers, never source fallbacks."""

import copy
import gzip
import io
from pathlib import Path
import re
import uuid

import nbtlib


DATA_VERSION = 5023
VANILLA_DIMENSIONS = ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")
STORAGE_KINDS = ("region", "entities", "poi")
MAX_NBT_BYTES = 64 * 1024 * 1024


def resource_parts(key):
    if not isinstance(key, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", key):
        raise ValueError("Expected an explicit namespaced target resource key")
    namespace, path = key.split(":")
    if namespace in (".", "..") or any(part in ("", ".", "..") for part in path.split("/")):
        raise ValueError("Unsafe target resource path")
    return namespace, path


def dimension_directory(world, key):
    namespace, path = resource_parts(key)
    return Path(world) / "dimensions" / namespace / path


def region_file(world, dimension, chunk_x, chunk_z, kind="region"):
    if type(chunk_x) is not int or type(chunk_z) is not int or kind not in STORAGE_KINDS:
        raise ValueError("Expected integer chunk coordinates and native region/entities/poi storage")
    return dimension_directory(world, dimension) / kind / f"r.{chunk_x // 32}.{chunk_z // 32}.mca"


def shared_file(world, key):
    namespace, path = resource_parts(key)
    return Path(world) / "data" / namespace / (path + ".dat")


def player_identity(value):
    if isinstance(value, uuid.UUID):
        return value
    if not isinstance(value, str):
        raise ValueError("Expected an explicit canonical player UUID")
    try:
        identity = uuid.UUID(value)
    except ValueError as error:
        raise ValueError("Expected an explicit canonical player UUID") from error
    if str(identity) != value:
        raise ValueError("Player UUID must be canonical, not a filename alias")
    return identity


def player_file(world, identity):
    return Path(world) / "players/data" / (str(player_identity(identity)) + ".dat")


def _load(path):
    path = Path(path)
    for part in (path, *path.parents):
        if part.is_symlink() or getattr(part, "is_junction", lambda: False)():
            raise ValueError("Aliased target save path")
    if path.stat().st_nlink != 1 or path.stat().st_size > MAX_NBT_BYTES:
        raise ValueError("Aliased or oversized target save file")
    raw = path.read_bytes()
    if raw.startswith(b"\x1f\x8b"):
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            raw = stream.read(MAX_NBT_BYTES + 1)
    if len(raw) > MAX_NBT_BYTES:
        raise ValueError("Expanded target save exceeds the NBT budget")
    stream = io.BytesIO(raw)
    result = nbtlib.File.parse(stream)
    if stream.read(1):
        raise ValueError("Trailing data in target NBT file")
    return result


def _version(tag):
    value = tag.get("DataVersion")
    if type(value) is not nbtlib.Int or int(value) != DATA_VERSION:
        raise ValueError("Expected native 26.3 DataVersion 5023, without source coercion")


def read_level(world):
    root = _load(Path(world) / "level.dat")
    data = root.get("Data")
    if not isinstance(data, nbtlib.Compound):
        raise ValueError("Target level.dat lacks its native Data compound")
    _version(data)
    return copy.deepcopy(data)


def read_worldgen(world):
    read_level(world)
    root = _load(shared_file(world, "minecraft:world_gen_settings"))
    _version(root)
    if not isinstance(root.get("data"), nbtlib.Compound):
        raise ValueError("Target worldgen SavedData lacks its native data compound")
    return copy.deepcopy(root)


def uuid_tag(value):
    if type(value) is not nbtlib.IntArray or len(value) != 4:
        raise ValueError("Native player authority must be a four-int UUID")
    number = 0
    for word in value:
        number = (number << 32) | (int(word) & 0xffffffff)
    return uuid.UUID(int=number)


def singleplayer_uuid(world):
    data = read_level(world)
    if "Player" in data:
        raise ValueError("Embedded Player is historical authority, not native 26 player storage")
    return uuid_tag(data["singleplayer_uuid"]) if "singleplayer_uuid" in data else None


def read_player(world, identity):
    identity = player_identity(identity)
    data = _load(player_file(world, identity))
    _version(data)
    if uuid_tag(data.get("UUID")) != identity:
        raise ValueError("Saved native player UUID differs from its authoritative path")
    return copy.deepcopy(data)
