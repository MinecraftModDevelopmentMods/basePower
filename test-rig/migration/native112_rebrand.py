"""Build finite witnesses with real 1.10 jars and read them after a native 1.12 upgrade."""

import argparse
import gzip
import io
from pathlib import Path
import shutil
import struct
import time
import traceback
import zlib

import nbtlib

from native112 import output_path, pinned_profile, require_disjoint
from native112_code import code_files, verify_code
from native112_lifecycle import read_json, require_native_boot, verify_staged
from rig_transport import WORLD, Server, digest, fingerprint, stage, write_json

ALIASES = dict(poweradvantage="basepower", steamadvantage="basesteam", electricadvantage="baseelectric")
SOURCE_LAUNCHER = "forge-1.10.2-12.18.3.2511-universal.jar"
DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}
BARE_BLOCKS = {"steamPipe", "electricWire"}
INVENTORIES = {"chest", "destination", "steamCrusher", "electricFurnace", "batteryArray"}
ENERGY = {"steamCrusher", "electricFurnace", "batteryArray"}


def registry(level, key):
    return {int(row["V"]): str(row["K"]) for row in level["FML"]["Registries"]["minecraft:" + key]["ids"]}


def chunk(world, x, z):
    cx, cz = x // 16, z // 16
    region = world / "region" / f"r.{cx // 32}.{cz // 32}.mca"
    with region.open("rb") as stream:
        stream.seek(4 * (cx % 32 + 32 * (cz % 32)))
        sector = int.from_bytes(stream.read(4), "big") >> 8
        if not sector:
            raise AssertionError(f"Witness chunk is not present: {cx},{cz}")
        stream.seek(sector * 4096)
        length = int.from_bytes(stream.read(4), "big")
        method = stream.read(1)
        data = stream.read(length - 1)
    if len(data) != length - 1 or method not in (b"\x01", b"\x02"):
        raise AssertionError(f"Invalid chunk record: {region}")
    return nbtlib.File.parse(io.BytesIO((gzip.decompress if method == b"\x01" else zlib.decompress)(data)))["Level"]


def nibble(array, offset):
    return 0 if array is None else (int(array[offset // 2]) >> (4 * (offset % 2))) & 15


def read_block(world, level, pos):
    x, y, z = pos
    data = chunk(world, x, z)
    section = next(row for row in data["Sections"] if int(row["Y"]) == y // 16)
    offset = (y % 16) * 256 + (z % 16) * 16 + x % 16
    block = (int(section["Blocks"][offset]) & 255) | (nibble(section.get("Add"), offset) << 8)
    tile = next((row for row in data["TileEntities"] if tuple(int(row[k]) for k in "xyz") == pos), None)
    return registry(level, "blocks")[block], nibble(section["Data"], offset), tile


def canonical(id):
    if id == "forge:bucketFilled":
        return "forge:bucketfilled"
    owner, sep, path = id.partition(":")
    return ALIASES.get(owner, owner) + sep + path


def item_witness(tile, enchantments, require_modern):
    rows = []
    for entry in tile["Items"]:
        tag = entry.get("tag", {})
        item = str(entry["id"])
        enchant = [(enchantments[int(ench["id"])], int(ench["lvl"])) for ench in tag.get("ench", ())]
        if require_modern and (canonical(item) != item or any(canonical(name) != name for name, _ in enchant)):
            raise AssertionError(f"Inventory still contains an old registry identity: {item}, {enchant}")
        rows.append(dict(slot=int(entry["Slot"]), item=canonical(item), count=int(entry["Count"]),
                         enchantments=[(canonical(name), rank) for name, rank in enchant],
                         damage=int(entry["Damage"]), charge=float(tag.get("energy", 0)),
                         label=str(tag.get("Witness", ""))))
        if canonical(item) == "forge:bucketfilled":
            rows[-1]["fluid"] = [str(tag["FluidName"]), int(tag["Amount"])]
    return rows


def observe_turbine(world, pos):
    actors = chunk(world, pos[0], pos[2])["Entities"]
    matched = [row for row in actors if "hydroturbine" in str(row["id"]).lower()]
    if len(matched) != 1:
        raise AssertionError(f"Expected one saved turbine, found {len(matched)}")
    entity = matched[0]
    return dict(id=str(entity["id"]), parent=[int(value) for value in entity["parentPos"]],
                uuid=[int(entity[key]) for key in ("UUIDMost", "UUIDLeast")],
                position=[float(value) for value in entity["Pos"]])


def observe(world, positions, *, require_modern=False):
    level = nbtlib.load(world / "level.dat")
    ench = registry(level, "enchantments")
    named = {}
    for name, pos in positions.items():
        id, meta, tile = read_block(world, level, pos)
        if tile is None:
            if name in BARE_BLOCKS:
                named[name] = dict(block=id, meta=meta, tile=None)
                continue
            raise AssertionError(f"Missing witness tile {name} at {pos}")
        result = dict(block=id, meta=meta, tile=str(tile["id"]))
        if name == "tank":
            result["fluid"] = [str(tile["Tank"]["FluidName"]), int(tile["Tank"]["Amount"])]
        if name in INVENTORIES:
            result["items"] = item_witness(tile, ench, require_modern)
        if name in ENERGY:
            result["energy"] = struct.unpack(">f", struct.pack(">i", int(tile["Energy"][0])))[0]
        named[name] = result
    forge = level.get("forge", level.get("Forge", {}))
    return dict(witnesses=named, turbine=observe_turbine(world, positions["hydroGenerator"]),
                oilOwners=[str(x) for x in forge.get("DefaultFluidList", ())],
                registryBlocks=sorted(key for key in registry(level, "blocks").values()
                    if key.split(":", 1)[0] in set(ALIASES) | set(ALIASES.values())),
                registryItems=sorted(key for key in registry(level, "items").values()
                    if key.split(":", 1)[0] in set(ALIASES) | set(ALIASES.values())))


POSITIONS = {"tank": (0, 5, 4), "chest": (2, 5, 4), "steamDrill": (4, 5, 4),
             "electricDrill": (6, 5, 4), "conveyor": (10, 5, 4), "destination": (11, 5, 4),
             "sourceChest": (9, 5, 4), "steamSource": (0, 5, 9), "steamPipe": (1, 5, 9),
             "steamCrusher": (2, 5, 9), "batteryArray": (6, 5, 9),
             "electricWire": (7, 5, 9), "electricFurnace": (8, 5, 9), "hydroGenerator": (12, 5, 12)}


def command(server, text, response):
    server.send(text)
    server.wait(response, 30)


def create(source, profile, java, port, owners):
    launcher = stage(source, profile)
    configure(source, port)
    with_server = Server(source, java, launcher, "source", query_result="cancel")
    try:
        command(with_server, "save-all", "Saved the world")
        data = nbtlib.load(source / WORLD / "level.dat")
        spawn_x, spawn_z = (int(data["Data"]["Spawn" + key]) & ~15 for key in ("X", "Z"))
        positions = {name: (x + spawn_x, y, z + spawn_z) for name, (x, y, z) in POSITIONS.items()}
        ench = {name: number for number, name in registry(data, "enchantments").items()}
        musket_enchantment = ench.get(owners[1] + ":powderless", ench.get("steamadvantage:powderless"))
        if musket_enchantment is None:
            raise AssertionError("Source has no musket enchantment")
        p, s, e = owners
        inventory = ('{Items:[{Slot:0b,id:"%s:sprocket",Count:7b,Damage:0s},'
                     '{Slot:1b,id:"%s:musket",Count:1b,Damage:3s,tag:{ench:[{id:%ds,lvl:1s}],Witness:"musket"}},'
                     '{Slot:2b,id:"%s:lithium_battery",Count:1b,Damage:0s,tag:{energy:391.5f,Witness:"battery"}},'
                     '{Slot:3b,id:"forge:bucketFilled",Count:1b,Damage:0s,tag:{FluidName:"crude_oil",Amount:1000}}]}'
                     % (p, s, musket_enchantment, e))
        fixtures = [
            ("tank", p + ":fluid_metal_tank", 0, '{Tank:{FluidName:"crude_oil",Amount:1337}}'),
            ("chest", "minecraft:chest", 2, inventory),
            ("steamDrill", s + ":steam_drill", 2, '{Facing:2}'),
            ("electricDrill", e + ":electric_drill", 3, '{}'),
            ("conveyor", p + ":item_conveyor", 5, '{}'),
            ("destination", "minecraft:chest", 2, '{}'),
            ("steamSource", p + ":infinite_steam", 0, ''),
            ("steamPipe", s + ":steam_pipe", 0, ''),
            ("steamCrusher", s + ":steam_crusher", 0, '{}'),
            ("batteryArray", e + ":electric_battery_array", 0,
             '{Items:[{Slot:0b,id:"' + e + ':lithium_battery",Count:1b,Damage:0s,tag:{energy:4000f}}]}'),
            ("electricWire", e + ":electric_conduit", 0, ''),
            ("electricFurnace", e + ":electric_furnace", 0, ''),
            ("hydroGenerator", e + ":hydroelectric_generator", 0, ''),
        ]
        fixtures.append(("sourceChest", "minecraft:chest", 2, '{}'))
        for name, block, metadata, nbt in fixtures:
            x, y, z = positions[name]
            command(with_server, f"setblock {x} {y} {z} {block} {metadata} replace {nbt}", "Block placed")
        time.sleep(3)
        command(with_server, "save-all", "Saved the world")
    finally:
        with_server.close()
    return positions


def configure(runtime, port):
    props = runtime / "server.properties"
    lines = [line for line in props.read_text().splitlines() if not line.startswith("server-port=")]
    props.write_text("\n".join(lines) + f"\nserver-port={port}\n", encoding="ascii")


def worldgen_rules(root):
    profile = read_json(root / WORLD / "serverconfig/orespawn-worldgen.json")
    return {name: rule for kind in ("ores", "fluid_deposits")
            for name, rule in profile.get(kind, {}).items()
            if name.split(":", 1)[0] in set(ALIASES.values())}


def change_legacy_oil(config):
    path = config / "orespawn/poweradvantage.json"
    document = read_json(path)
    ore = document["dimensions"][0]["ores"][0]
    ore.update(frequency=0.375, size=24, variation=6, minHeight=19, maxHeight=48)
    write_json(path, document)


def assert_legacy_worldgen(rules):
    # OreSpawn draws from [size - variation, size + variation) in the legacy range.
    expected = {"basepower:crude_oil": (0.375, 18, 29, 19, 47),
                "baseelectric:li_ore": (0.125, 6, 9, 1, 31),
                "baseelectric:sulfur_ore": (1, 8, 23, 1, 31)}
    for block, settings in expected.items():
        active = [rule for rule in rules.values() if rule.get("enabled", True) and rule.get("block") == block]
        if len(active) != 1:
            raise AssertionError(f"Expected one active legacy rule for {block}, found {len(active)}")
        placements = active[0]["dimensions"]
        if set(placements) != DIMENSIONS:
            raise AssertionError(f"Legacy dimension selection changed for {block}: {list(placements)}")
        for dimension, placement in placements.items():
            actual = tuple(placement[key] for key in ("frequency", "min_quantity", "max_quantity", "min_y", "max_y"))
            if actual != settings:
                raise AssertionError(f"Legacy generation changed for {block} in {dimension}: {actual} != {settings}")


def assert_preserved(before, after, *, operated):
    for name, saved in before["witnesses"].items():
        live = after["witnesses"][name]
        if live["block"] != canonical(saved["block"]) or live["meta"] != saved["meta"]:
            raise AssertionError(f"Block identity or metadata changed at {name}: {saved} -> {live}")
        if name in BARE_BLOCKS and saved["tile"] is None:
            if live["tile"] is not None:
                raise AssertionError(f"An unexpected tile appeared at {name}")
            continue
        old_tile = saved["tile"].removeprefix("minecraft:")
        expected_tile = old_tile
        for old, new in ALIASES.items():
            for owner in (old, new):
                if old_tile.startswith(owner + "."):
                    expected_tile = new + ":" + old_tile[len(owner) + 1:]
            if old_tile.startswith(old + ":"):
                expected_tile = canonical(old_tile)
        if expected_tile == "Chest":
            expected_tile = "minecraft:chest"
        if live["tile"] != expected_tile:
            raise AssertionError(f"Unexpected tile identity at {name}: {live['tile']}")
        if live["block"].split(":", 1)[0] in ALIASES.values() and ":" not in live["tile"]:
            raise AssertionError(f"Tile was not saved with a canonical ID at {name}")
        if name == "tank" and live["fluid"] != saved["fluid"]:
            raise AssertionError(f"Tank changed: {saved['fluid']} -> {live['fluid']}")
        if name == "chest" and live["items"] != saved["items"]:
            raise AssertionError(f"Item, charge or enchantment changed: {saved['items']} -> {live['items']}")
        if name == "destination":
            wanted = [] if not operated else [dict(slot=0, item="minecraft:dirt", count=8, damage=0,
                                                    charge=0.0, enchantments=[], label="")]
            if live["items"] != wanted:
                raise AssertionError(f"Unexpected conveyor output: {live['items']}")
        output = {"steamCrusher": "minecraft:cobblestone", "electricFurnace": "minecraft:iron_ingot"}.get(name)
        if output:
            items = [(row["item"], row["count"]) for row in live["items"]]
            if items != ([(output, 1)] if operated else []):
                raise AssertionError(f"Unexpected operation at {name}: {items}")
    saved, live = before["witnesses"], after["witnesses"]
    initial = saved["batteryArray"]["energy"] + saved["electricFurnace"]["energy"]
    remaining = live["batteryArray"]["energy"] + live["electricFurnace"]["energy"]
    if initial != 4000 or abs(initial - remaining - (1600 if operated else 0)) > 0.01:
        raise AssertionError(f"Electric migration lost or created charge: {initial} -> {remaining}")
    if live["steamCrusher"]["energy"] <= 0:
        raise AssertionError("The migrated steam pipe did not supply the crusher")
    for key in ("registryBlocks", "registryItems"):
        missing = {canonical(id) for id in before[key]} - set(after[key])
        if missing:
            raise AssertionError(f"Saved {key} missing: {sorted(missing)}")
    if "basepower:crude_oil" not in after["oilOwners"] or "basepower:refined_oil" not in after["oilOwners"]:
        raise AssertionError("World does not use the new fluid owners")
    expected_turbine = dict(before["turbine"], id="baseelectric:hydroturbine")
    if after["turbine"] != expected_turbine:
        raise AssertionError(f"Saved turbine changed or was replaced: {expected_turbine} -> {after['turbine']}")


def run(args):
    output = output_path(args.output)
    source_pin = read_json(args.source_manifest)
    source_seed = Path(source_pin["seed"])
    jars = [Path(row["path"]) for row in source_pin["jars"]]
    for row, path in zip(source_pin["jars"], jars):
        if digest(path) != row["sha256"]:
            raise AssertionError(f"Source mod input changed: {path}")
    if code_files(source_seed) != source_pin["serverCode"]:
        raise AssertionError("Source engine changed")
    target = pinned_profile(args.target_manifest, "target112")
    for path in [source_seed, args.source_manifest, args.target_manifest, *jars, target[0], *target[2]]:
        require_disjoint(output, path)
    output.mkdir()
    receipt = output / "rebrand-upgrade-results.json"
    source = output / "source"
    runtime = output / "target"
    helpers = [Path(__file__)] + [Path(__file__).with_name(name) for name in (
        "native112.py", "native112_code.py", "native112_lifecycle.py", "native112_diagnostics.py", "rig_transport.py")]
    seals = {str(path): digest(path) for path in helpers}
    report = dict(status="RUNNING", sourceManifestSha256=digest(args.source_manifest),
                  targetManifestSha256=digest(args.target_manifest), protocolSha256=seals, phases={})
    source_hashes = None
    config_hashes = None
    try:
        positions = create(source, (source_seed, SOURCE_LAUNCHER, jars), args.java8, args.port, source_pin["owners"])
        if {jar.name: digest(jar) for jar in (source / "mods").glob("*.jar")} != {
                jar.name: row["sha256"] for jar, row in zip(jars, source_pin["jars"])}:
            raise AssertionError("Staged 1.10 mods differ from the source manifest")
        if code_files(source) != source_pin["serverCode"]:
            raise AssertionError("Staged 1.10 engine differs from the source manifest")
        source_log = source / "console-source.log"
        log = source_log.read_text(encoding="utf-8")
        report["sourceLog"] = dict(sha256=digest(source_log),
                                   errorLines=[line for line in log.splitlines() if "/ERROR]" in line])
        if ("Forge Mod Loader version 12.18.3.2511 for Minecraft 1.10.2 loading" not in log
                or any(marker in log for marker in ("Encountered an unexpected exception",
                                                     "Couldn't process command", "NoSuchMethodError", "NoSuchFieldError"))):
            raise AssertionError("Source Forge 1.10 runtime did not build the fixture cleanly")
        source_hashes = fingerprint(source / WORLD)
        config_hashes = fingerprint(source / "config")
        original = observe(source / WORLD, positions)
        write_json(output / "source-world-hashes.json", source_hashes)
        report["phases"]["source"] = original
        report["positions"] = positions
        if original["witnesses"]["tank"]["fluid"] != ["crude_oil", 1337]:
            raise AssertionError("Source did not save the finite fluid witness")
        if original["witnesses"]["chest"]["items"][-1].get("fluid") != ["crude_oil", 1000]:
            raise AssertionError("Source did not save a full universal bucket")
        if original["turbine"]["parent"] != list(positions["hydroGenerator"]):
            raise AssertionError("Source did not save a turbine connected to its generator")
        launcher = stage(runtime, target)
        configure(runtime, args.port)
        shutil.copytree(source / WORLD, runtime / WORLD)
        shutil.copytree(source / "config", runtime / "config")
        original_cyano = source_pin["owners"][0] == "poweradvantage"
        if original_cyano:
            change_legacy_oil(runtime / "config")
        verify_staged(runtime, target)
        verify_code(runtime, read_json(args.target_manifest)["target112"]["serverCode"])
        first_rules = None
        for label in ("converted", "restarted"):
            server = Server(runtime, args.java8, launcher, label, query_result="cancel")
            try:
                if label == "restarted":
                    for name, item, count in (("sourceChest", "dirt", 8), ("steamCrusher", "stone", 1),
                                              ("electricFurnace", "iron_ore", 1)):
                        x, y, z = positions[name]
                        command(server, f'blockdata {x} {y} {z} ' +
                                ('{Items:[{Slot:0b,id:"minecraft:%s",Count:%db,Damage:0s}]}' % (item, count)),
                                "Block data updated")
                    # Only add raw inputs; no machine, pipe, wiring, or power supply is replaced.
                    time.sleep(15)
                else:
                    # Let the normal initial chunk ticks persist their converted tile data.
                    time.sleep(3)
                command(server, "save-all flush", "Saved the world")
            finally:
                server.close()
            require_native_boot(runtime / f"console-{label}.log")
            result = observe(runtime / WORLD, positions, require_modern=True)
            rules = worldgen_rules(runtime)
            result["worldgenRules"] = rules
            report["phases"][label] = result
            write_json(receipt, report)
            assert_preserved(original, result, operated=label == "restarted")
            if original_cyano:
                assert_legacy_worldgen(rules)
            if first_rules is not None and rules != first_rules:
                raise AssertionError("Owned OreSpawn profile changed across the cold restart")
            header = source / WORLD / "level.dat"
            header_backup = runtime / WORLD / "basepower-migration-backups" / (
                "level.dat." + digest(header).lower() + ".before-base-identity-migration")
            if not header_backup.is_file() or digest(header_backup) != digest(header):
                raise AssertionError("Original Forge header backup is missing or changed")
            first_rules = rules
            verify_staged(runtime, target)
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        if source_hashes is not None:
            report["sourceUnchanged"] = (source_hashes == fingerprint(source / WORLD)
                                         and config_hashes == fingerprint(source / "config"))
            if not report["sourceUnchanged"]:
                report["status"] = "FAIL"
        report["protocolUnchanged"] = seals == {str(path): digest(path) for path in helpers}
        report["manifestsUnchanged"] = (report["sourceManifestSha256"] == digest(args.source_manifest)
                                       and report["targetManifestSha256"] == digest(args.target_manifest))
        if not report["protocolUnchanged"] or not report["manifestsUnchanged"]:
            report["status"] = "FAIL"
        write_json(receipt, report)
    print(report["status"] + ": " + str(receipt), flush=True)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-manifest", "target-manifest", "java8", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    raise SystemExit(run(parser.parse_args()))
