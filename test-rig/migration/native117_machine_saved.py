"""Corroborate frozen machine snapshots against stopped native terrain files."""

import gzip
import io
import json
from pathlib import Path
import re
import zlib

import nbtlib

from matrix import WORLD, digest, write_json
from native117_format import decode_palette
from transformer_order import read_region


def chunk(payload):
    if len(payload) < 6 or int.from_bytes(payload[:4], 'big') != len(payload) - 4:
        raise ValueError('Invalid terrain payload length')
    decoders = {1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}
    if payload[4] not in decoders:
        raise ValueError('Unsupported terrain compression')
    root = nbtlib.File.parse(io.BytesIO(decoders[payload[4]](payload[5:])))
    if int(root.get('DataVersion', -1)) != 2730:
        raise ValueError('Machine save must be native 1.17.1 terrain')
    return root['Level']


def state(row):
    match = re.fullmatch(r'Block\{([^}]+)\}(?:\[([^]]*)\])?', row['state'])
    if match is None or match[1] != row['block']:
        raise ValueError('Malformed machine state snapshot')
    properties = {}
    for part in match[2].split(',') if match[2] else ():
        key, value = part.split('=', 1)
        if key in properties:
            raise ValueError('Duplicate state property')
        properties[key] = value
    return dict(Name=match[1], Properties=properties)


def compare_chunk(rows, data):
    from native117_machine_operations import saved_tile_nbt
    sections = {}
    for section in data['Sections']:
        y = int(section['Y'])
        if y in sections:
            raise ValueError('Duplicate saved terrain section')
        sections[y] = decode_palette(section['Palette'].unpack(), section.get('BlockStates', []).tolist()
            if isinstance(section.get('BlockStates'), nbtlib.LongArray) else []) if 'Palette' in section else None
    tiles = {}
    for tile in data['TileEntities']:
        pos = tuple(int(tile[key]) for key in ('x', 'y', 'z'))
        if pos in tiles:
            raise ValueError('Duplicate saved tile position')
        tiles[pos] = tile
    failures = []
    for row in rows:
        x, y, z = pos = tuple(row[key] for key in ('x', 'y', 'z'))
        palette = sections.get(y // 16)
        actual = palette[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if palette else {'Name': 'minecraft:air'}
        actual = dict(Name=actual['Name'], Properties=actual.get('Properties', {}))
        expected = state(row)
        before = saved_tile_nbt(nbtlib.parse_nbt(row['nbt'])) if 'nbt' in row else None
        saved = tiles.get(pos)
        if saved is not None:
            # LevelChunk.getBlockEntityNbtForSaving adds this storage-only flag
            # after BlockEntity.save. Packed or wrongly typed values are not equivalent.
            if not isinstance(saved.get('keepPacked'), nbtlib.Byte) or int(saved['keepPacked']) != 0:
                raise ValueError('Expected the native unpacked-tile storage marker')
            saved = nbtlib.Compound({key: value for key, value in saved.items() if key != 'keepPacked'})
        after = saved_tile_nbt(saved) if saved is not None else None
        if actual != expected or before != after:
            failures.append(dict(pos=pos, expectedState=expected, savedState=actual,
                expectedNbt=before, savedNbt=after))
    return failures


def audit(runtime, fixture, evidence):
    """Read only after shutdown; retain source payloads even when comparison fails."""
    runtime, evidence = Path(runtime).resolve(), Path(evidence).resolve()
    world = runtime / WORLD
    if evidence.is_relative_to(world) or world.is_relative_to(evidence):
        raise ValueError('Evidence overlaps the world')
    evidence.mkdir(parents=True, exist_ok=False)
    report = dict(status='FAIL', cases=len(fixture['cases']), files={}, failures=[],
        scope='Stopped raw terrain state and typed machine NBT; no expected-state writes')
    write_json(evidence / 'snapshot.json', fixture)
    regions, chunks = {}, {}
    try:
        for case in fixture['cases']:
            grouped = {}
            for row in case['positions']:
                key = row['x'] // 16, row['z'] // 16
                grouped.setdefault(key, []).append(row)
            for (cx, cz), rows in grouped.items():
                region = cx // 32, cz // 32
                if region not in regions:
                    path = world / 'region' / ('r.%d.%d.mca' % region)
                    checksum = digest(path)
                    regions[region] = read_region(path)[1]
                    report['files'][str(path)] = checksum
                if (cx, cz) not in chunks:
                    payload = regions[region][(cx & 31) + (cz & 31) * 32]
                    (evidence / ('%d.%d.chunk' % (cx, cz))).write_bytes(payload)
                    chunks[cx, cz] = chunk(payload)
                    if (int(chunks[cx, cz]['xPos']), int(chunks[cx, cz]['zPos'])) != (cx, cz):
                        raise AssertionError('Saved chunk coordinate differs from its region slot')
                failures = compare_chunk(rows, chunks[cx, cz])
                if failures:
                    report['failures'].append(dict(id=case['id'], differences=failures))
        if any(digest(Path(path)) != checksum for path, checksum in report['files'].items()):
            raise AssertionError('Terrain changed during stopped audit')
        report['status'] = 'FAIL' if report['failures'] else 'PASS'
    except BaseException as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        write_json(evidence / 'receipt.json', report)
    return report
