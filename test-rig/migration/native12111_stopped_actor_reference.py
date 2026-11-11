"""Seal an actor reference from stopped source disk only, before any target load."""

import argparse
import copy
import io
from pathlib import Path
import uuid

import nbtlib
import actors
from matrix import WORLD, digest, fingerprint, plain, write_json
from native117 import require_disjoint
import native117_entity_storage as storage
from native117_format import uuid_longs
import native12111 as native

VERSION = 3955
SCOPE = "SOURCE_STOPPED_DISK_ONLY_NATIVE1211_ACTOR_REFERENCE"
WITNESSES = {"cart": "minecraft:chest_minecart", "dropped-musket": "minecraft:item",
             "dropped-oil": "minecraft:item", "owned-wolf": "minecraft:wolf", "hydroturbine": "baseelectric:hydroturbine"}


def read_nbt(path):
    return storage._nbt(storage._decompress(path.read_bytes(), 1))


def identity(words):
    if not isinstance(words, nbtlib.IntArray):
        raise AssertionError("Source player requires a native UUID int array")
    most, least = uuid_longs(list(map(int, words)))
    return str(uuid.UUID(int=((most & ((1 << 64) - 1)) << 64) | (least & ((1 << 64) - 1))))


def encoded(tag):
    output = io.BytesIO()
    nbtlib.File(copy.deepcopy(tag)).write(output)
    return output.getvalue()


def disk_state(source):
    # No serializer snapshot, stopped-event report, or target runtime is read here.
    import native12111_actors as driver
    definition = native.read_json(source / "actor-fixture.json")
    world = source / WORLD
    level = read_nbt(world / "level.dat")
    if type(level["Data"]["DataVersion"]) is not nbtlib.Int or int(level["Data"]["DataVersion"]) != VERSION:
        raise AssertionError("Only an actual stopped native1.21.1 source world is supported")
    player_relative = Path("playerdata") / (definition["playerUuid"] + ".dat")
    player = read_nbt(world / player_relative)
    if (type(player["DataVersion"]) is not nbtlib.Int or int(player["DataVersion"]) != VERSION
            or identity(player["UUID"]) != definition["playerUuid"]):
        raise AssertionError("Wrong source player identity or native version")
    registry = actors.enchantments(source)
    player_state = driver.player_semantic(plain(player.unpack()), actors.PLAYER_KEYS, registry)
    result = dict(file=player_state, adapter=copy.deepcopy(player_state), entities={})
    if "Player" in level["Data"]:
        host = level["Data"]["Player"]
        if identity(host["UUID"]) != definition["playerUuid"]:
            raise AssertionError("Embedded source host is not the actor witness player")
        result["embedded"] = driver.player_semantic(plain(host.unpack()), actors.PLAYER_KEYS, registry)
    assets = {"raw/level.dat": (world / "level.dat").read_bytes(), "raw/player.dat": (world / player_relative).read_bytes(),
              "raw/actor-fixture.json": (source / "actor-fixture.json").read_bytes()}
    metadata, seen = {}, set()
    directory = world / "entities"
    for region in sorted(directory.iterdir()):
        if not region.is_file():
            raise AssertionError("Unexpected directory in source entity storage")
        match = storage.REGION_NAME.fullmatch(region.name)
        if match is None:
            raise AssertionError("Unreviewed source entity storage file: " + region.name)
        raw = region.read_bytes()
        assets["raw/entities/" + region.name] = raw
        for coordinates, payload in storage._payloads(raw, int(match[1]), int(match[2])):
            for root in storage._entity_chunk(payload, coordinates, VERSION):
                if storage._chunk(root) != coordinates:
                    raise AssertionError("Source entity position does not match its physical region slot")
                for identifier, entity, parent in storage._tree(root):
                    if identifier in seen:
                        raise AssertionError("Duplicate source entity UUID in stopped regions")
                    seen.add(identifier)
                    data = entity.get("ForgeData", {})
                    if "AdvWorksActor" not in data:
                        continue
                    witness = str(data["AdvWorksActor"])
                    if (type(data["AdvWorksActor"]) is not nbtlib.String or witness not in WITNESSES
                            or witness in result["entities"] or str(entity["id"]) != WITNESSES[witness]):
                        raise AssertionError("Missing, duplicate or unexpected source actor witness/type")
                    if parent is not None or "Passengers" in entity:
                        raise AssertionError("This bounded stopped actor reference does not model passenger fixtures")
                    if not definition["minX"] <= float(entity["Pos"][0]) <= definition["maxX"]:
                        raise AssertionError("Source witness outside the actor fixture bounds")
                    result["entities"][witness] = driver.historical_semantic(plain(entity.unpack()), actors.ENTITY_KEYS, registry)
                    asset = "raw/witnesses/" + witness + ".nbt"
                    assets[asset] = encoded(entity)
                    metadata[witness] = dict(uuid=identifier, region=region.name, chunk=list(coordinates), rawNbtAsset=asset)
    if set(result["entities"]) != set(WITNESSES):
        raise AssertionError("Stopped source does not contain the exact five actor witnesses")
    return driver.source_components(result, VERSION), metadata, assets


def seal(source, fixture_receipt, fixture_sha, output):
    source = Path(source).resolve(strict=True)
    output = Path(output).resolve()
    native.validate_hash(fixture_receipt, fixture_sha)
    approved = native.read_json(fixture_receipt)
    before = fingerprint(source)
    if Path(approved["source"]).resolve() != source or approved["sourceFingerprint"] != before:
        raise AssertionError("Stopped source differs from its approved fixture receipt")
    require_disjoint(output, source)
    require_disjoint(output, fixture_receipt)
    output.mkdir(parents=True, exist_ok=False)
    state, metadata, assets = disk_state(source)
    for name, data in assets.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as stream:
            stream.write(data)
    if fingerprint(source) != before:
        raise AssertionError("Source changed while sealing its stopped reference")
    document = dict(format=1, scope=SCOPE, source=str(source), sourceDataVersion=VERSION,
                    sourceFingerprint=before, fixtureReceiptSha256=fixture_sha.upper(), sourceUnchanged=True,
                    referenceBuilderSha256=digest(Path(__file__)), callerMustStopSourceServer=True,
                    targetObservationsUsed=False, projection="Unchanged actor oracle keys plus exact complete stack/component payloads",
                    expected=state, actorBindings=metadata, assets={name: digest(output / name) for name in assets})
    write_json(output / "reference.json", document)
    return output / "reference.json"


def validate(path, checksum, source, fixture_sha):
    path, source = Path(path).resolve(strict=True), Path(source).resolve(strict=True)
    native.validate_hash(path, checksum)
    document = native.read_json(path)
    if (document.get("format") != 1 or document.get("scope") != SCOPE or document.get("source") != str(source)
            or document.get("sourceDataVersion") != VERSION or document.get("sourceUnchanged") is not True
            or document.get("targetObservationsUsed") is not False or document.get("fixtureReceiptSha256") != fixture_sha.upper()
            or document.get("referenceBuilderSha256") != digest(Path(__file__))
            or document.get("sourceFingerprint") != fingerprint(source)):
        raise AssertionError("Unbound or changed stopped source reference")
    expected, bindings, assets = disk_state(source)
    if document["expected"] != expected or document["actorBindings"] != bindings or set(document["assets"]) != set(assets):
        raise AssertionError("Stopped golden does not derive exactly from source disk")
    expected_files = {"reference.json", *assets}
    actual_files = {item.relative_to(path.parent).as_posix() for item in path.parent.rglob("*") if item.is_file()}
    if actual_files != expected_files:
        raise AssertionError("Unexpected/missing reference asset (target evidence is forbidden)")
    for name, raw in assets.items():
        artifact = path.parent / name
        native.validate_hash(artifact, document["assets"][name])
        if artifact.read_bytes() != raw:
            raise AssertionError("Stopped raw reference asset differs from source bytes")
    return copy.deepcopy(expected)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "fixture-receipt", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--fixture-receipt-sha256", required=True)
    args = parser.parse_args()
    reference = seal(args.source, args.fixture_receipt, args.fixture_receipt_sha256, args.output)
    print(reference)
    print(digest(reference))
