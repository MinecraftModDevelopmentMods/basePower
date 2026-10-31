"""Synthetic strict-reader controls only; these never claim native runtime qualification."""

import base64
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zlib

import nbtlib as n

import native117_entity_storage as storage


def entity(words=(1, 2, 3, 4), pos=(8.5, 80.0, 8.5), identity="minecraft:chest_minecart"):
    return n.Compound(id=n.String(identity), UUID=n.IntArray(words), Pos=n.List[n.Double](pos),
        Motion=n.List[n.Double]([0, 0, 0]), Rotation=n.List[n.Float]([0, 0]),
        Items=n.List[n.Compound]([n.Compound(id=n.String("minecraft:dirt"), Count=n.Byte(7), Slot=n.Byte(2),
            tag=n.Compound(Private=n.String("retain exactly"), Damage=n.Int(17)))]),
        ForgeData=n.Compound(AdvWorksActor=n.String("cart"), Private=n.List[n.Int]([9, 8, 7])))


def encoded(value):
    stream = io.BytesIO()
    n.File(value).write(stream)
    return stream.getvalue()


def entry(value, **extra):
    return dict(nbtBase64=base64.b64encode(gzip.compress(encoded(value))).decode("ascii"), **extra)


def root(coordinates, entities):
    return n.Compound(DataVersion=n.Int(2730), Position=n.IntArray(coordinates), Entities=n.List[n.Compound](entities))


def payload(value, compression=2):
    raw = encoded(value)
    data = gzip.compress(raw) if compression == 1 else zlib.compress(raw) if compression == 2 else raw
    return struct.pack(">I", len(data) + 1) + bytes([compression]) + data


def put(world, coordinates, entities=None, value=None, compression=2, raw=None):
    cx, cz = coordinates
    directory = world / "entities"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / ("r.%d.%d.mca" % (cx // 32, cz // 32))
    existing = {}
    if path.exists():
        existing = dict(storage._payloads(path.read_bytes(), cx // 32, cz // 32))
    existing[coordinates] = raw if raw is not None else payload(value if value is not None else root(coordinates, entities), compression)
    header, body = bytearray(8192), bytearray()
    offset = 2
    for (x, z), data in sorted(existing.items()):
        slot = (x & 31) + (z & 31) * 32
        count = (len(data) + 4095) // 4096
        header[slot * 4:slot * 4 + 4] = ((offset << 8) | count).to_bytes(4, "big")
        body += data + bytes(count * 4096 - len(data))
        offset += count
    path.write_bytes(header + body)
    return path, existing[coordinates]


class NativeEntityStorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.world = self.base / "World"
        self.world.mkdir()
        (self.world / "level.dat").write_bytes(b"unrelated protected world data")
        self.actor = entity()
        self.record = {"entities": [entry(self.actor)], "frozen": True}
        self.index = 0

    def audit(self, record=None, world=None):
        self.index += 1
        self.evidence = self.base / ("evidence-" + str(self.index))
        return storage.audit(record if record is not None else self.record, world or self.world, self.evidence)

    def test_exact_payloads_unknown_nbt_and_world_bytes_are_retained(self):
        path, raw = put(self.world, (0, 0), [self.actor])
        before = {p.relative_to(self.world).as_posix(): p.read_bytes() for p in self.world.rglob("*") if p.is_file()}
        record = copy.deepcopy(self.record)
        receipt = self.audit()
        self.assertEqual("PASS", receipt["status"])
        self.assertEqual("EXACT_STOPPED_ENTITY_NBT_ONLY", receipt["scope"])
        self.assertEqual(1, receipt["matchedEntities"])
        self.assertEqual(raw, (self.evidence / "chunks/0.0.chunk").read_bytes())
        self.assertEqual(path.read_bytes(), (self.evidence / "regions/r.0.0.mca").read_bytes())
        for name, checksum in receipt["files"].items():
            self.assertEqual(hashlib.sha256((self.evidence / name).read_bytes()).hexdigest().upper(), checksum)
        self.assertEqual(before, {p.relative_to(self.world).as_posix(): p.read_bytes() for p in self.world.rglob("*") if p.is_file()})
        self.assertEqual(record, self.record)

    def test_actual_negative_saved_pos_not_fixture_coordinates_selects_chunk(self):
        self.actor["Pos"] = n.List[n.Double]([-0.25, 80, -512.5])
        self.record = {"entities": [entry(self.actor)], "definition": {"x": 1000, "z": 1000}}
        put(self.world, (-1, -33), [self.actor])
        receipt = self.audit()
        self.assertEqual([[-1, -33]], [row["chunk"] for row in receipt["matched"].values()])
        self.assertIn("chunks/-1.-33.chunk", receipt["files"])

    def test_nether_and_end_dimension_directories(self):
        for name, key, identity in (("DIM-1", "minecraft:the_nether", -1), ("DIM1", "minecraft:the_end", 1)):
            world = self.world / name
            put(world, (0, 0), [self.actor])
            record = dict(entities=[entry(self.actor)], worldDimensionKey=key, dimension=identity)
            with self.subTest(dimension=key):
                self.assertEqual(identity, self.audit(record, world)["dimension"])

    def test_dimension_record_cannot_be_checked_against_the_wrong_world(self):
        put(self.world, (0, 0), [self.actor])
        with self.assertRaisesRegex(AssertionError, "explicit dimension"):
            self.audit(dict(entities=[entry(self.actor)], worldDimensionKey="minecraft:the_nether"))

    def test_recursive_passenger_and_separate_child_record_match_the_stored_root(self):
        child = entity((5, 6, 7, 8), pos=(16.25, 80, 8.5), identity="minecraft:pig")
        self.actor["Passengers"] = n.List[n.Compound]([child])
        put(self.world, (0, 0), [self.actor])
        receipt = self.audit(dict(entities=[entry(self.actor), entry(child)]))
        self.assertEqual(2, receipt["matchedEntities"])
        self.assertEqual(2, receipt["snapshotRecords"])
        self.assertEqual({(0, 0)}, {tuple(row["chunk"]) for row in receipt["matched"].values()})

    def test_overlapping_child_observations_must_have_identical_full_nbt(self):
        child = entity((5, 6, 7, 8), identity="minecraft:pig")
        self.actor["Passengers"] = n.List[n.Compound]([child])
        put(self.world, (0, 0), [self.actor])
        wrong = copy.deepcopy(child)
        wrong["Items"][0]["Count"] = n.Byte(6)
        with self.assertRaisesRegex(AssertionError, "overlapping snapshot"):
            self.audit(dict(entities=[entry(self.actor), entry(wrong)]))

    def test_duplicate_raw_uuid_in_a_chunk_or_another_region_fails(self):
        for other_region in (False, True):
            put(self.world, (0, 0), [self.actor] if other_region else [self.actor, copy.deepcopy(self.actor)])
            if other_region:
                other = copy.deepcopy(self.actor)
                other["Pos"] = n.List[n.Double]([520, 80, 8])
                put(self.world, (32, 0), [other])
            with self.subTest(other_region=other_region), self.assertRaisesRegex(AssertionError, "Duplicate UUID"):
                self.audit()

    def test_duplicate_passenger_uuid_fails(self):
        raw = copy.deepcopy(self.actor)
        raw["Passengers"] = n.List[n.Compound]([copy.deepcopy(self.actor)])
        put(self.world, (0, 0), [raw])
        with self.assertRaisesRegex(AssertionError, "Duplicate UUID"):
            self.audit()

    def test_unrelated_entities_are_counted_not_claimed_as_witnesses(self):
        extra = entity((5, 6, 7, 8), identity="minecraft:pig")
        put(self.world, (0, 0), [self.actor, extra])
        receipt = self.audit()
        self.assertEqual((1, 2, 1), (receipt["matchedEntities"], receipt["rawEntities"], receipt["unrelatedEntities"]))

    def test_inventory_quantity_and_unknown_keys_are_not_ignored(self):
        mutations = [lambda value: value["Items"][0].update(Count=n.Byte(6)),
                     lambda value: value["Items"][0]["tag"].update(Private=n.String("changed")),
                     lambda value: value["ForgeData"].update(Unknown=n.Int(99)),
                     lambda value: value.pop("ForgeData"),
                     lambda value: value["Items"].clear()]
        for mutate in mutations:
            raw = copy.deepcopy(self.actor)
            mutate(raw)
            put(self.world, (0, 0), [raw])
            with self.subTest(mutate=mutate), self.assertRaisesRegex(AssertionError, "Complete saved entity NBT"):
                self.audit()
            self.assertEqual("FAIL", json.loads((self.evidence / "receipt.json").read_text())["status"])

    def test_missing_passengers_and_changed_passenger_inventory_fail(self):
        child = entity((5, 6, 7, 8), identity="minecraft:pig")
        self.actor["Passengers"] = n.List[n.Compound]([child])
        self.record = dict(entities=[entry(self.actor)])
        for remove in (True, False):
            raw = copy.deepcopy(self.actor)
            if remove:
                raw.pop("Passengers")
            else:
                raw["Passengers"][0]["Items"][0]["Count"] = n.Byte(6)
            put(self.world, (0, 0), [raw])
            with self.subTest(remove=remove), self.assertRaisesRegex(AssertionError, "Complete saved entity NBT"):
                self.audit()

    def test_nested_nbt_numeric_tag_types_remain_exact(self):
        raw = copy.deepcopy(self.actor)
        raw["Items"][0]["Count"] = n.Short(7)
        put(self.world, (0, 0), [raw])
        with self.assertRaisesRegex(AssertionError, "Complete saved entity NBT"):
            self.audit()

    def test_only_existing_finite_dimension_and_owner_encodings_can_differ(self):
        raw = copy.deepcopy(self.actor)
        raw["Dimension"] = n.String("minecraft:overworld")
        raw["Owner"] = n.IntArray([5, 6, 7, 8])
        snapshot = copy.deepcopy(raw)
        snapshot["Dimension"] = n.Int(0)
        most, least = storage.uuid_longs([5, 6, 7, 8])
        snapshot.pop("Owner")
        snapshot["OwnerUUID"] = n.String(str(storage.uuid.UUID(int=((most & ((1 << 64) - 1)) << 64) | (least & ((1 << 64) - 1)))))
        put(self.world, (0, 0), [raw])
        self.assertEqual("PASS", self.audit(dict(entities=[entry(snapshot)]))["status"])

    def test_native_uuid_shape_and_entity_namespaced_ids_are_required(self):
        for key, value in (("UUID", n.IntArray([1, 2, 3])), ("UUID", n.List[n.Int]([1, 2, 3, 4])),
                           ("id", n.String("Chest")), ("id", n.String("Minecraft:pig"))):
            raw = copy.deepcopy(self.actor)
            raw[key] = value
            put(self.world, (0, 0), [raw])
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.audit()

    def test_old_uuid_longs_are_not_native_storage(self):
        raw = copy.deepcopy(self.actor)
        raw.pop("UUID")
        raw.update(UUIDMost=n.Long(1), UUIDLeast=n.Long(2))
        put(self.world, (0, 0), [raw])
        with self.assertRaisesRegex(ValueError, "native int"):
            self.audit()

    def test_data_version_position_types_coordinates_and_unknown_root_keys_fail(self):
        cases = [dict(DataVersion=n.Int(2724)), dict(DataVersion=n.Long(2730)),
                 dict(Position=n.IntArray([0])), dict(Position=n.List[n.Int]([0, 0])),
                 dict(Position=n.IntArray([1, 0])), dict(Unknown=n.Int(3)), dict(Level=n.Compound())]
        for replacement in cases:
            value = root((0, 0), [self.actor])
            value.update(replacement)
            put(self.world, (0, 0), value=value)
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                self.audit()

    def test_entities_and_passengers_must_be_compound_lists(self):
        for value in (n.String("none"), n.List[n.String](["not an entity"])):
            put(self.world, (0, 0), value=n.Compound(DataVersion=n.Int(2730), Position=n.IntArray([0, 0]), Entities=value))
            with self.assertRaisesRegex(ValueError, "Entities must"):
                self.audit()
        raw = copy.deepcopy(self.actor)
        raw["Passengers"] = n.String("none")
        put(self.world, (0, 0), [raw])
        with self.assertRaisesRegex(ValueError, "Passengers must"):
            self.audit()

    def test_nonfinite_and_malformed_saved_positions_fail(self):
        for value in (n.List[n.Double]([float("nan"), 80, 0]), n.List[n.Double]([0, 80, float("inf")]),
                      n.List[n.Float]([0, 80, 0]), n.List[n.Double]([0, 80])):
            raw = copy.deepcopy(self.actor)
            raw["Pos"] = value
            put(self.world, (0, 0), [raw])
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "finite native doubles"):
                self.audit()

    def test_root_saved_pos_must_match_the_actual_region_slot(self):
        raw = copy.deepcopy(self.actor)
        raw["Pos"] = n.List[n.Double]([32, 80, 8])
        put(self.world, (0, 0), [raw])
        with self.assertRaisesRegex(AssertionError, "saved Pos differs"):
            self.audit()

    def test_empty_storage_and_moved_snapshot_cannot_pass_vacuously(self):
        put(self.world, (0, 0), [])
        with self.assertRaisesRegex(AssertionError, "absent from the chunk"):
            self.audit()
        put(self.world, (0, 0), [self.actor])
        moved = copy.deepcopy(self.actor)
        moved["Pos"] = n.List[n.Double]([520, 80, 8])
        with self.assertRaisesRegex(AssertionError, "actual saved Pos"):
            self.audit(dict(entities=[entry(moved)]))

    def test_actual_snapshot_required_not_expected_projection(self):
        put(self.world, (0, 0), [self.actor])
        for value in ({"entities": []}, {"entities": [{"UUIDMost": 1}]}, {"entities": {"cart": {}}}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.audit(value)

    def test_snapshot_uuid_metadata_duplicates_and_missing_vehicle_root_fail(self):
        put(self.world, (0, 0), [self.actor])
        with self.assertRaisesRegex(AssertionError, "attestation"):
            self.audit(dict(entities=[entry(self.actor, uuid="wrong")]))
        with self.assertRaisesRegex(AssertionError, "Duplicate snapshot UUID"):
            self.audit(dict(entities=[entry(self.actor), entry(self.actor)]))
        with self.assertRaisesRegex(ValueError, "vehicle root"):
            self.audit(dict(entities=[entry(self.actor, vehicleUuid="missing-vehicle")]))

    def test_all_native_region_compression_forms(self):
        for compression in (1, 2, 3):
            put(self.world, (0, 0), [self.actor], compression=compression)
            with self.subTest(compression=compression):
                self.assertEqual("PASS", self.audit()["status"])

    def test_corrupt_compression_trailing_nbt_and_external_chunks_fail(self):
        for raw in (struct.pack(">I", 5) + b"\x82data", struct.pack(">I", 5) + b"\x02data",
                    struct.pack(">I", len(encoded(root((0, 0), [self.actor]))) + 2) + b"\x03" + encoded(root((0, 0), [self.actor])) + b"x"):
            put(self.world, (0, 0), raw=raw)
            with self.subTest(raw=raw[:6]), self.assertRaises((ValueError, zlib.error)):
                self.audit()

    def test_decompression_limit_fails_closed(self):
        put(self.world, (0, 0), [self.actor])
        with patch.object(storage, "MAX_NBT_BYTES", 32), self.assertRaises(ValueError):
            self.audit()

    def test_empty_native_lists_in_unrelated_chunks_are_supported(self):
        put(self.world, (0, 0), [self.actor])
        for entities in (n.List[n.Compound](), n.List[n.End]()):
            put(self.world, (1, 0), value=n.Compound(DataVersion=n.Int(2730), Position=n.IntArray([1, 0]), Entities=entities))
            self.assertEqual("PASS", self.audit()["status"])

    def test_native_zero_byte_read_probe_regions_are_retained_as_empty(self):
        put(self.world, (0, 0), [self.actor])
        empty = self.world / "entities/r.-1.-1.mca"
        empty.write_bytes(b"")
        receipt = self.audit()
        self.assertEqual("PASS", receipt["status"])
        self.assertEqual(1, receipt["matchedEntities"])
        self.assertEqual(hashlib.sha256(b"").hexdigest().upper(), receipt["sourceFiles"][empty.name])
        self.assertEqual(b"", (self.evidence / "regions" / empty.name).read_bytes())
        self.assertEqual(["chunks/0.0.chunk"], list(receipt["chunks"]))

    def test_empty_read_probe_cannot_satisfy_an_entity_witness(self):
        directory = self.world / "entities"
        directory.mkdir()
        (directory / "r.0.0.mca").write_bytes(b"")
        with self.assertRaisesRegex(AssertionError, "absent from the chunk"):
            self.audit()
        receipt = json.loads((self.evidence / "receipt.json").read_text())
        self.assertEqual("FAIL", receipt["status"])
        self.assertEqual({}, receipt["matched"])
        self.assertEqual({}, receipt["chunks"])

    def test_nonempty_short_headers_and_unpadded_regions_remain_invalid(self):
        for data in (b"\x00", bytes(4096), bytes(8191), bytes(8193)):
            with self.subTest(length=len(data)), self.assertRaisesRegex(ValueError, "region length"):
                list(storage._payloads(data, 0, 0))

    def test_duplicate_nbt_keys_cannot_be_silently_overwritten(self):
        data = encoded(root((0, 0), [self.actor]))
        duplicate = b"\x03\x00\x0bDataVersion" + struct.pack(">i", 2730)
        malformed = data[:-1] + duplicate + b"\x00"
        raw = struct.pack(">I", len(malformed) + 1) + b"\x03" + malformed
        put(self.world, (0, 0), raw=raw)
        with self.assertRaisesRegex(ValueError, "round-trip exactly"):
            self.audit()

    def test_overlapping_out_of_bounds_and_header_sector_allocations_fail(self):
        for location in ((1 << 8) | 1, (99 << 8) | 1, 1):
            path, _ = put(self.world, (0, 0), [self.actor])
            data = bytearray(path.read_bytes())
            data[:4] = location.to_bytes(4, "big")
            path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, "sector allocation"):
                self.audit()
            path.unlink()
        path, _ = put(self.world, (0, 0), [self.actor])
        data = bytearray(path.read_bytes())
        data[4:8] = data[:4]
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            self.audit()

    def test_truncated_region_and_invalid_chunk_length_fail(self):
        path, _ = put(self.world, (0, 0), [self.actor])
        data = path.read_bytes()
        path.write_bytes(data[:-1])
        with self.assertRaisesRegex(ValueError, "region length"):
            self.audit()
        broken = bytearray(data)
        broken[8192:8196] = (99999).to_bytes(4, "big")
        path.write_bytes(broken)
        with self.assertRaisesRegex(ValueError, "payload length"):
            self.audit()

    def test_source_changes_during_audit_are_not_accepted_as_stopped_evidence(self):
        path, _ = put(self.world, (0, 0), [self.actor])
        original = storage._semantic
        calls = 0

        def concurrent_write(value):
            nonlocal calls
            calls += 1
            if calls == 2:
                path.write_bytes(path.read_bytes() + bytes(4096))
            return original(value)

        with patch.object(storage, "_semantic", side_effect=concurrent_write), \
             self.assertRaisesRegex(AssertionError, "changed during"):
            self.audit()

    def test_evidence_cannot_be_in_world_or_overwrite_an_earlier_run(self):
        put(self.world, (0, 0), [self.actor])
        with self.assertRaisesRegex(ValueError, "disjoint"):
            storage.audit(self.record, self.world, self.world / "evidence")
        self.audit()
        receipt = (self.evidence / "receipt.json").read_bytes()
        with self.assertRaises(FileExistsError):
            storage.audit(self.record, self.world, self.evidence)
        self.assertEqual(receipt, (self.evidence / "receipt.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
