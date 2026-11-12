"""Deterministic native26 atlas singles, derived from model materials and explicit client sprites."""

import argparse
import json
from pathlib import Path
import struct


POWER = Path(__file__).resolve().parents[2]
ROOTS = {
    "basepower": POWER / "src/main/resources",
    "basesteam": POWER.parent / "SteamAdvantage/SteamAdvantage/src/main/resources",
    "baseelectric": POWER.parent / "ElectricAdvantage/ElectricAdvantage/src/main/resources",
}
MANUAL = {
    "basepower": {"blocks/crude_oil_still", "blocks/crude_oil_flow", "blocks/refined_oil_still", "blocks/refined_oil_flow"},
    "basesteam": {"entity/drill_bit"},
    "baseelectric": {"entity/laser_turret"},
}


def graph(roots=ROOTS):
    textures = {owner: set() for owner in roots}
    for root in roots.values():
        for path in sorted((root / "assets").glob("*/models/**/*.json")):
            model = json.loads(path.read_text(encoding="utf-8"))
            for material in model.get("textures", {}).values():
                if isinstance(material, dict):
                    material = material["sprite"]
                if not isinstance(material, str) or not material:
                    raise ValueError("Invalid model material: " + str(path))
                if material.startswith("#"):
                    continue
                namespace, name = material.split(":", 1) if ":" in material else ("minecraft", material)
                if namespace in textures:
                    textures[namespace].add(name)
    result = {}
    for owner, root in roots.items():
        selected = textures[owner] | MANUAL[owner]
        result[owner] = {"blocks": [], "items": []}
        for name in sorted(selected):
            texture_root = (root / "assets" / owner / "textures").resolve()
            path = (texture_root / (name + ".png")).resolve()
            if not path.is_relative_to(texture_root) or not path.is_file():
                raise ValueError("Referenced owned texture missing: " + owner + ":" + name)
            data = path.read_bytes()
            if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
                raise ValueError("Invalid source PNG: " + str(path))
            width, height = struct.unpack(">II", data[16:24])
            atlas = "items" if name.startswith(("items/", "item/")) else "blocks"
            if atlas == "blocks" and (width < 16 or height < 16):
                raise ValueError("Native default mip4 is incompatible with referenced block sprite: " + owner + ":" + name)
            result[owner][atlas].append(owner + ":" + name)
    return result


def document(resources):
    return {"sources": [{"type": "minecraft:single", "resource": resource} for resource in resources]}


def check(documents, expected):
    for owner, atlases in expected.items():
        for atlas, resources in atlases.items():
            actual = documents[owner, atlas]
            if actual != document(resources):
                raise ValueError("Atlas differs from exact owned material graph: " + owner + "/" + atlas)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    expected = graph()
    documents = {}
    for owner, root in ROOTS.items():
        for atlas, resources in expected[owner].items():
            path = root / "assets/minecraft/atlases" / (atlas + ".json")
            if args.write:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(document(resources), indent=2) + "\n", encoding="utf-8")
            documents[owner, atlas] = json.loads(path.read_text(encoding="utf-8"))
            print(owner, atlas, len(resources))
    check(documents, expected)


if __name__ == "__main__":
    main()
