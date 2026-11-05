"""Check actual saved ore and oil placements on sealed Forge 1.12.2 jars."""

import argparse
from collections import Counter
from pathlib import Path
import traceback

import nbtlib

from native112 import output_path, pinned_profile, require_disjoint
from native112_code import verify_code
from native112_lifecycle import read_json, require_native_boot, verify_staged
from native112_rebrand import chunk, configure, nibble, registry, worldgen_rules
from rig_transport import WORLD, Server, digest, stage, write_json


OIL = "basepower:crude_oil"
ORES = {"baseelectric:sulfur_ore", "baseelectric:li_ore"}
SEED = "1739918491"


def placements(world, dimension):
    names = registry(nbtlib.load(world / "level.dat"), "blocks")
    ids = {number for number, name in names.items() if name in ORES | {OIL}}
    if len(ids) != 3:
        raise AssertionError("World registry is missing an ore or crude oil")
    folder = world if dimension == 0 else world / f"DIM{dimension}"
    counts, heights, biomes = Counter(), {}, {}
    populated = 0
    for region in sorted((folder / "region").glob("r.*.mca")):
        _, rx, rz, _ = region.name.split(".")
        with region.open("rb") as stream:
            header = stream.read(4096)
        for slot in range(1024):
            if not int.from_bytes(header[4 * slot:4 * slot + 3], "big"):
                continue
            x, z = 32 * int(rx) + slot % 32, 32 * int(rz) + slot // 32
            data = chunk(folder, 16 * x, 16 * z)
            if not int(data.get("TerrainPopulated", 0)):
                continue
            populated += 1
            for section in data["Sections"]:
                add = section.get("Add")
                base_y = 16 * int(section["Y"])
                for index, byte in enumerate(section["Blocks"]):
                    block = (int(byte) & 255) | (nibble(add, index) << 8)
                    if block not in ids:
                        continue
                    name = names[block]
                    counts[name] += 1
                    heights.setdefault(name, set()).add(base_y + index // 256)
                    biomes.setdefault(name, set()).add(int(data["Biomes"][index % 256]) & 255)
    return dict(chunks=populated, blocks=dict(counts), heights={name: [min(ys), max(ys)] for name, ys in heights.items()},
                biomes={name: sorted(values) for name, values in biomes.items()})


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "java8", "output"):
        cli.add_argument("--" + name, type=Path, required=True)
    cli.add_argument("--port", type=int, required=True)
    args = cli.parse_args()
    output = output_path(args.output)
    manifest = read_json(args.manifest)
    target = pinned_profile(args.manifest, "target112")
    for path in (args.manifest, target[0], *target[2]):
        require_disjoint(output, path)
    output.mkdir()
    report = dict(status="RUNNING", manifestSha256=digest(args.manifest), runnerSha256=digest(Path(__file__)),
                  seed=SEED, scope="Controlled populated desert and plains chunks with native stone hosts; not a normal-world frequency estimate",
                  worlds={})
    try:
        for label, biome in (("desert", 2), ("plains", 1)):
            runtime = output / label
            launcher = stage(runtime, target)
            configure(runtime, args.port)
            properties = runtime / "server.properties"
            contents = [line for line in properties.read_text().splitlines()
                        if not line.startswith(("generator-settings=", "level-seed="))]
            contents.extend(("level-seed=" + SEED,
                             f"generator-settings=3;minecraft:bedrock,60*minecraft:stone,3*minecraft:sand;{biome};"))
            properties.write_text("\n".join(contents) + "\n", encoding="ascii")
            verify_code(runtime, manifest["target112"]["serverCode"])
            verify_staged(runtime, target)
            first = None
            world = runtime / WORLD
            for phase in ("initial", "restart"):
                server = Server(runtime, args.java8, launcher, phase, query_result="cancel")
                try:
                    server.send("save-all flush")
                    server.wait("Saved the world", 90)
                finally:
                    server.close()
                require_native_boot(runtime / f"console-{phase}.log")
                outcome = placements(world, 0)
                if first is not None and outcome != first:
                    raise AssertionError(f"Saved ore or oil placements changed after restart in {label}")
                first = outcome
            verify_staged(runtime, target)
            report["worlds"][label] = outcome
            rules = worldgen_rules(runtime)
            if (outcome["chunks"] < 100 or set(rules) != {"basepower:fluid_deposit/desert_crude_oil",
                    "baseelectric:ore/sulfur", "baseelectric:ore/lithium"}):
                raise AssertionError(f"Missing populated chunks or fresh default rules in {label}")
            for ore in ORES:
                if outcome["blocks"].get(ore, 0) <= 0 or outcome["biomes"].get(ore) != [biome]:
                    raise AssertionError(f"No real {ore} was placed in {label}")
            if label == "desert":
                if outcome["blocks"].get(OIL, 0) <= 0 or outcome["biomes"].get(OIL) != [biome]:
                    raise AssertionError("No actual crude oil in sampled desert chunks")
                if outcome["heights"][OIL][1] >= 61:
                    raise AssertionError("Oil breached the cover of the desert fixture")
            elif outcome["blocks"].get(OIL, 0) != 0:
                raise AssertionError("Oil generated outside the desert control")
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    write_json(output / "worldgen-results.json", report)
    print(report["status"] + ": " + str(output / "worldgen-results.json"))
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
