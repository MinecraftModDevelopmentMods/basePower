"""Explicit derived fixture copies: native-owned FML config authority and legal battery slots."""

import argparse
import base64
import copy
import gzip
import io
from pathlib import Path
import shutil

import nbtlib
from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind, capture_actors, capture_dimensions
import native2612 as native
import native2612_actors as actors

BATTERIES = {namespace + ":" + item for namespace in ("electricadvantage", "baseelectric")
             for item in ("lithium_battery", "lead_acid_battery", "alkaline_battery", "nickel_hydride_battery")}


def legal_ender_batteries(player):
    """Split only the four known artificial overstacked witnesses, retaining every item and tag."""
    inventory = player.get("EnderItems", ())
    slots = [int(item["Slot"]) for item in inventory]
    if len(slots) != len(set(slots)) or any(not 0 <= slot < 27 for slot in slots):
        raise AssertionError("Malformed historical EnderItems slots")
    free = [slot for slot in range(27) if slot not in slots]
    required = 0
    for item in inventory:
        key = "Count" if "Count" in item else "count"
        count = int(item[key])
        if str(item["id"]) in BATTERIES and count > 1:
            if count != 3 or ("Count" in item and "count" in item):
                raise AssertionError("Unreviewed battery overstack")
            required += 2
    if len(free) < required:
        raise AssertionError("Insufficient free EnderItems slots")
    changes, appended = [], []
    for item in inventory:
        key = "Count" if "Count" in item else "count"
        count = int(item[key])
        if str(item["id"]) not in BATTERIES or count <= 1:
            continue
        original = copy.deepcopy(item)
        item[key] = type(item[key])(1)
        destinations = [int(item["Slot"])]
        for _ in range(2):
            sibling = copy.deepcopy(item)
            destination = free.pop(0)
            sibling["Slot"] = type(item["Slot"])(destination)
            appended.append(sibling)
            destinations.append(destination)
        for sibling in (item, *appended[-2:]):
            old_payload = {key: value for key, value in original.items() if key not in ("Slot", "Count", "count")}
            new_payload = {key: value for key, value in sibling.items() if key not in ("Slot", "Count", "count")}
            if old_payload != new_payload:
                raise AssertionError("Fixture item metadata changed during legal splitting")
        changes.append(dict(id=str(item["id"]), fromSlot=int(item["Slot"]), toSlots=destinations, preservedTotal=count))
    if appended:
        inventory.extend(appended)
    return changes


def derive(source, receipt, checksum, output, kind, legalize=False):
    native.validate_hash(receipt, checksum)
    approval = native.read_json(receipt)
    before = fingerprint(source)
    if Path(approval["source"]).resolve() != source or before != approval["sourceFingerprint"]:
        raise AssertionError("Protected fixture differs from its explicit source receipt")
    output = output_path(output)
    require_disjoint(output, source)
    require_disjoint(output, receipt)
    output.mkdir(parents=True)
    world_before = fingerprint(source / WORLD)
    shutil.copytree(source / WORLD, output / WORLD)
    config_evidence = output / "config-overlay-evidence"
    config_evidence.mkdir()
    for path in (source / "config").rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(source / "config")
        target = config_evidence / "historical-fml.toml" if relative.as_posix() == "fml.toml" else output / "config" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    name = "dimension" if kind == "dimensions" else "actor"
    for filename in (name + "-fixture.json", "migration-fixture.json"):
        shutil.copy2(source / filename, output / filename)
    prefix = "dimension-semantic-" if kind == "dimensions" else "semantic-"
    label = next(label for label in ("runtime-restart", "restarted", "source") if (source / (prefix + label + ".json")).is_file())
    record_name = ("dimensions-" if kind == "dimensions" else "actors-") + label + ".json"
    record = native.read_json(source / record_name)
    definition = native.read_json(source / (name + "-fixture.json"))
    changes = {}
    if legalize:
        player_path = output / WORLD / "playerdata" / (definition["playerUuid"] + ".dat")
        player = nbtlib.load(player_path)
        edits = legal_ender_batteries(player)
        if edits:
            player.save()
            changes["loosePlayer"] = edits
        if record.get("playerAdapter"):
            raw = nbtlib.File.parse(io.BytesIO(gzip.decompress(base64.b64decode(record["player"]["nbtBase64"], validate=True))))
            edits = legal_ender_batteries(raw)
            if edits:
                data = io.BytesIO()
                raw.write(data)
                record["player"]["nbtBase64"] = base64.b64encode(gzip.compress(data.getvalue(), mtime=0)).decode()
                changes["serializerPlayer"] = edits
        level_path = output / WORLD / "level.dat"
        level = nbtlib.load(level_path)
        if "Player" in level["Data"]:
            edits = legal_ender_batteries(level["Data"]["Player"])
            if edits:
                level.save()
                changes["embeddedPlayer"] = edits
    write_json(output / record_name, record)
    capture = bind(capture_dimensions, semantic=actors.player_semantic) if kind == "dimensions" else capture_actors
    write_json(output / (prefix + label + ".json"), capture(output, label))
    world_after = fingerprint(output / WORLD)
    changed = [key for key in world_before if world_before[key] != world_after.get(key)]
    allowed = {"level.dat", str(Path("playerdata") / (definition["playerUuid"] + ".dat"))}
    if set(world_before) != set(world_after) or not set(changed) <= allowed or (not legalize and changed):
        raise AssertionError("Unexpected world change in derived fixture")
    if fingerprint(source) != before:
        raise AssertionError("Protected source changed during derivation")
    retained_fml = config_evidence / "historical-fml.toml"
    report = dict(source=str(output), sourceFingerprint=fingerprint(output), originalSource=str(source),
                  originalReceiptSha256=checksum, originalUnchanged=True, exactWorldFileChanges=changed,
                  legalBatterySplits=changes, kind=kind,
                  configOverlay=dict(owner="Forge64 native2612.stage", authority="native target fml.toml; no historical config overwrite",
                                     historicalFmlRetainedSha256=digest(retained_fml) if retained_fml.exists() else None,
                                     nativeFmlContent="versionCheck = false\n"),
                  scope="Explicit derived fixture, not a new old-engine serializer qualification")
    target_receipt = output.with_name(output.name + "-receipt.json")
    if target_receipt.exists():
        raise FileExistsError(target_receipt)
    write_json(target_receipt, report)
    return target_receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "receipt", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--receipt-sha256", required=True)
    parser.add_argument("--kind", choices=("actors", "dimensions"), required=True)
    parser.add_argument("--legal-batteries", action="store_true")
    args = parser.parse_args()
    result = derive(args.source, args.receipt, args.receipt_sha256, args.output, args.kind, args.legal_batteries)
    print(result)
    print(digest(result))
