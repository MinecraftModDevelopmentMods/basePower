"""Verify the actual flattened terrain and block entity data written by 1.19.4."""

import gzip
import io
import shutil
import zlib

import nbtlib

from matrix import WORLD, digest, parse_nbt, plain, write_json
from native117 import native_normalized
from native117_format import decode_palette
from transformer_order import read_region


DATA_VERSION = 3337


def decode_chunk(payload, coordinates):
    if len(payload) < 6 or int.from_bytes(payload[:4], "big") != len(payload) - 4:
        raise ValueError("Malformed 1.19 chunk length")
    decoders = {1: gzip.decompress, 2: zlib.decompress, 3: lambda data: data}
    if payload[4] not in decoders:
        raise ValueError("Unknown chunk compression")
    chunk = plain(nbtlib.File.parse(io.BytesIO(decoders[payload[4]](payload[5:]))).unpack())
    if chunk.get("DataVersion") != DATA_VERSION or "Level" in chunk:
        raise ValueError("Chunk was not written in native 1.19.4 format")
    if (chunk["xPos"], chunk["zPos"]) != coordinates:
        raise ValueError("Saved chunk coordinates do not match the region slot")
    sections = {}
    for section in chunk["sections"]:
        y = section["Y"]
        if y in sections or any(key in section for key in ("Blocks", "Palette", "BlockStates")):
            raise ValueError("Duplicate section or legacy block encoding")
        states = section.get("block_states")
        sections[y] = decode_palette(states["palette"], states.get("data", [])) if states else None
    tiles = {}
    for tile in chunk.get("block_entities", ()):
        pos = (tile["x"], tile["y"], tile["z"])
        if pos in tiles:
            raise AssertionError("Duplicate block entity in saved chunk")
        tiles[pos] = tile
    return sections, tiles


def capture(runtime, label, rows):
    world = runtime / WORLD
    evidence = runtime / "native119-save" / label
    evidence.mkdir(parents=True)
    shutil.copy2(world / "level.dat", evidence / "level.dat")
    level = nbtlib.load(evidence / "level.dat")["Data"]
    if level["DataVersion"] != DATA_VERSION:
        raise AssertionError("Saved level does not identify 1.19.4")
    regions, chunks = {}, {}
    for row in rows:
        x, y, z = pos = tuple(int(row[field]) for field in ("x", "y", "z"))
        key = (x // 16, z // 16)
        if key not in chunks:
            cx, cz = key
            rx, rz = region = (cx // 32, cz // 32)
            if region not in regions:
                _, regions[region] = read_region(world / "region" / f"r.{rx}.{rz}.mca")
            payload = regions[region][(cx & 31) + (cz & 31) * 32]
            (evidence / f"{cx}_{cz}.chunk").write_bytes(payload)
            chunks[key] = decode_chunk(payload, key)
        sections, tiles = chunks[key]
        blocks = sections.get(y // 16)
        block = blocks[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if blocks else {"Name": "minecraft:air"}
        if (block["Name"], block.get("Properties", {})) != (row["actualName"], row["properties"]):
            raise AssertionError(f"Saved block differs from live snapshot at {pos}: {block}")
        if "nbtBase64" in row:
            # These fixtures are frozen while inspected. keepPacked is chunk bookkeeping.
            disk = dict(tiles[pos])
            disk.pop("keepPacked", None)
            if native_normalized(disk) != native_normalized(plain(parse_nbt(row))):
                raise AssertionError(f"Saved block entity differs from live snapshot at {pos}")
        elif pos in tiles:
            raise AssertionError(f"Unexpected saved block entity at {pos}")
    receipt = dict(status="PASS", witnesses=len(rows), nativeDataVersion=DATA_VERSION,
                   worldGenSettings=plain(level["WorldGenSettings"].unpack()),
                   files={file.name: digest(file) for file in evidence.iterdir()})
    write_json(evidence / "receipt.json", receipt)
    return receipt
