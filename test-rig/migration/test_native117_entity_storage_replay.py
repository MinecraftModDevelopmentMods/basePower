"""Synthetic replay/tamper controls only. No native server or source fixture is used."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nbtlib as n

from matrix import WORLD
import native117_entity_storage as native
import native117_stopped_entities as stopped
import native117_entity_storage_replay as reader
from test_native117_entity_storage import entity, entry, put, root


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")


class RetainedEntityReplayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.runtime = self.base / "runtime"
        self.world = self.runtime / WORLD
        self.world.mkdir(parents=True)
        (self.runtime / "advantage-migration.allow").write_text("synthetic fixture only")
        self.evidence = self.base / "retained"
        self.actor = entity()
        self.document = dict(format=1, status="CAPTURED", phase="FMLServerStoppingEvent", minecraftVersion="1.17.1",
            dataVersion=2730, worldName=WORLD, worldDirectory=str(self.world), bootId="12345678-1234-1234-1234-123456789abc",
            enumeration="ServerLevel.getAllEntities", entityCount=0, dimensions={})
        for key, (dimension, _) in stopped.DIMENSIONS.items():
            self.document["dimensions"][key] = dict(worldDimensionKey=key, dimension=dimension, gameTime=20, entityCount=0, entities=[])

    def stage(self, key="minecraft:overworld", actor=None):
        actor = actor if actor is not None else self.actor
        row = entry(actor, uuid=native._identity(actor), id=str(actor["id"]), worldDimensionKey=key)
        self.document["dimensions"][key]["entities"].append(row)
        self.document["dimensions"][key]["entityCount"] += 1
        self.document["entityCount"] += 1
        put(self.world / stopped.DIMENSIONS[key][1], native._chunk(actor), [actor])

    def retain(self):
        write(self.runtime / stopped.SNAPSHOT, self.document)
        return stopped.audit_stopped(self.runtime, self.evidence)

    def reseal(self, prefix="overworld", key="minecraft:overworld"):
        """Forge coherent claimed hashes without repairing semantic truth."""
        folder = self.evidence / prefix
        child = read(folder / "receipt.json")
        child["files"] = {p.relative_to(folder).as_posix(): sha(p) for p in folder.rglob("*") if p.is_file() and p.name != "receipt.json"}
        child["sourceFiles"] = {p.name: sha(p) for p in (folder / "regions").glob("*.mca")}
        child["chunks"] = {}
        for path in (folder / "regions").glob("*.mca"):
            rx, rz = map(int, path.name.split(".")[1:3])
            for coordinates, payload in native._payloads(path.read_bytes(), rx, rz):
                name = "chunks/%d.%d.chunk" % coordinates
                if (folder / name).exists():
                    child["chunks"][name] = dict(position=list(coordinates), source=path.name, sha256=sha(folder / name))
        write(folder / "receipt.json", child)
        parent = read(self.evidence / "receipt.json")
        parent["dimensions"][key] = child
        parent["snapshotSha256"] = sha(self.evidence / stopped.SNAPSHOT)
        write(self.evidence / "receipt.json", parent)

    def replace_raw(self, entities, prefix="overworld", key="minecraft:overworld", version=2730):
        scratch = self.base / ("synthetic-replacement-" + prefix)
        value = root((0, 0), entities)
        value["DataVersion"] = n.Int(version)
        path, _ = put(scratch, (0, 0), value=value)
        folder = self.evidence / prefix
        (folder / "regions" / path.name).write_bytes(path.read_bytes())
        for coordinates, payload in native._payloads(path.read_bytes(), 0, 0):
            (folder / ("chunks/%d.%d.chunk" % coordinates)).write_bytes(payload)
        self.reseal(prefix, key)

    def test_replays_complete_semantics_and_hashes_every_file_without_writing(self):
        self.stage()
        proof = self.retain()
        before = {p.relative_to(self.evidence).as_posix(): p.read_bytes() for p in self.evidence.rglob("*") if p.is_file()}
        with patch.object(native, "audit", side_effect=AssertionError("Replay must never restage/write a world")), \
             patch.object(native, "_entity_chunk", wraps=native._entity_chunk) as parse:
            result = reader.replay(self.evidence, expected_snapshot_sha256=proof["snapshotSha256"],
                                   expected_receipt_sha256=sha(self.evidence / "receipt.json"))
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, parse.call_count)
        self.assertEqual((1, 1, 1), (result["auditedDimensions"], result["snapshotRecords"], result["matchedEntities"]))
        self.assertEqual(set(before), set(result["files"]))
        self.assertIn("receipt.json", result["files"])
        self.assertIn("overworld/receipt.json", result["files"])
        self.assertEqual(before, {p.relative_to(self.evidence).as_posix(): p.read_bytes() for p in self.evidence.rglob("*") if p.is_file()})

    def test_does_not_read_or_require_the_original_runtime(self):
        self.stage()
        self.retain()
        destination = self.base / "original-runtime-no-longer-at-recorded-path"
        assert self.runtime.resolve().is_relative_to(self.base.resolve())
        assert destination.resolve().is_relative_to(self.base.resolve())
        self.runtime.rename(destination)
        self.assertEqual("PASS", reader.replay(self.evidence)["status"])

    def test_retained_native_zero_byte_regions_are_not_missing_witness_proof(self):
        self.stage()
        (self.world / "entities/r.-1.-1.mca").write_bytes(b"")
        self.retain()
        result = reader.replay(self.evidence)
        self.assertIn("overworld/regions/r.-1.-1.mca", result["files"])
        self.assertEqual(1, result["matchedEntities"])
        self.replace_raw([])
        with self.assertRaisesRegex(AssertionError, "absent from its actual saved Pos"):
            reader.replay(self.evidence)

    def test_all_empty_remains_nonqualifying(self):
        self.retain()
        self.assertEqual("NO_NONEMPTY_DIMENSIONS", reader.replay(self.evidence)["status"])

    def test_all_three_dimensions_are_rederived(self):
        for index, key in enumerate(stopped.DIMENSIONS):
            self.stage(key, entity((index + 1, 2, 3, 4)))
        self.retain()
        self.assertEqual(3, reader.replay(self.evidence)["auditedDimensions"])

    def test_cross_dimension_uuid_duplicates_fail_after_local_metadata_is_resigned(self):
        self.stage()
        self.stage("minecraft:the_nether", entity((5, 6, 7, 8)))
        self.retain()
        document = read(self.evidence / stopped.SNAPSHOT)
        key = "minecraft:the_nether"
        identity = native._identity(self.actor)
        document["dimensions"][key]["entities"] = [entry(self.actor, uuid=identity,
            id=str(self.actor["id"]), worldDimensionKey=key)]
        write(self.evidence / stopped.SNAPSHOT, document)
        write(self.evidence / "DIM-1/snapshot.json", document["dimensions"][key])
        self.replace_raw([self.actor], "DIM-1", key)
        path = self.evidence / "DIM-1/receipt.json"
        child = read(path)
        child["matched"] = {identity: dict(rootUuid=identity, chunk=[0, 0],
            payload="chunks/0.0.chunk", separatelyObserved=True)}
        write(path, child)
        self.reseal("DIM-1", key)
        with self.assertRaisesRegex(AssertionError, "across dimensions"):
            reader.replay(self.evidence)

    def test_forged_root_counts_fail_even_with_pass_status(self):
        self.stage()
        self.retain()
        path = self.evidence / "receipt.json"
        value = read(path)
        value["matchedEntities"] = 99
        write(path, value)
        with self.assertRaisesRegex(AssertionError, "root receipt"):
            reader.replay(self.evidence)

    def test_boolean_or_forged_child_counters_are_not_accepted_as_integers(self):
        self.stage()
        self.retain()
        for value in (True, 99):
            child_path = self.evidence / "overworld/receipt.json"
            child = read(child_path)
            child["snapshotRecords"] = value
            write(child_path, child)
            self.reseal()
            with self.subTest(value=value), self.assertRaisesRegex(AssertionError, "overworld/receipt"):
                reader.replay(self.evidence)

    def test_forged_uuid_match_metadata_is_recomputed(self):
        self.stage()
        self.retain()
        path = self.evidence / "overworld/receipt.json"
        child = read(path)
        next(iter(child["matched"].values()))["chunk"] = [99, 99]
        write(path, child)
        self.reseal()
        with self.assertRaisesRegex(AssertionError, "overworld/receipt"):
            reader.replay(self.evidence)

    def test_changed_inventory_nbt_fails_after_all_claimed_hashes_are_resigned(self):
        self.stage()
        self.retain()
        changed = copy.deepcopy(self.actor)
        changed["Items"][0]["Count"] = n.Byte(6)
        self.replace_raw([changed])
        with self.assertRaisesRegex(AssertionError, "Complete retained entity NBT/type"):
            reader.replay(self.evidence)

    def test_motion_drift_is_not_normalized_away(self):
        self.stage()
        self.retain()
        changed = copy.deepcopy(self.actor)
        changed["Motion"][1] = n.Double(-0.4)
        self.replace_raw([changed])
        with self.assertRaisesRegex(AssertionError, "Complete retained entity NBT/type"):
            reader.replay(self.evidence)

    def test_changed_unknown_keys_or_tag_types_cannot_hide_behind_semantic_projection(self):
        self.stage()
        self.retain()
        for count in (n.Short(7), n.Byte(7)):
            changed = copy.deepcopy(self.actor)
            changed["Items"][0]["Count"] = count
            if isinstance(count, n.Byte):
                changed["ForgeData"]["Unrecognized"] = n.String("must not be ignored")
            self.replace_raw([changed])
            with self.subTest(count=count), self.assertRaisesRegex(AssertionError, "Complete retained entity NBT/type"):
                reader.replay(self.evidence)

    def test_passenger_loss_is_rejected(self):
        self.actor["Passengers"] = n.List[n.Compound]([entity((5, 6, 7, 8), identity="minecraft:pig")])
        self.stage()
        self.retain()
        changed = copy.deepcopy(self.actor)
        changed.pop("Passengers")
        self.replace_raw([changed])
        with self.assertRaisesRegex(AssertionError, "Complete retained entity NBT/type"):
            reader.replay(self.evidence)

    def test_duplicate_raw_uuid_fails_even_with_coherent_archive_hashes(self):
        self.stage()
        self.retain()
        self.replace_raw([self.actor, copy.deepcopy(self.actor)])
        with self.assertRaisesRegex(AssertionError, "Duplicate UUID"):
            reader.replay(self.evidence)

    def test_native_data_version_is_parsed_not_trusted_from_receipt(self):
        self.stage()
        self.retain()
        self.replace_raw([self.actor], version=2724)
        with self.assertRaisesRegex(ValueError, "DataVersion 2730"):
            reader.replay(self.evidence)

    def test_serialized_dimension_must_agree_with_storage_dimension(self):
        self.stage()
        self.retain()
        wrong = copy.deepcopy(self.actor)
        wrong["Dimension"] = n.String("minecraft:the_nether")
        self.replace_raw([wrong])
        with self.assertRaisesRegex(AssertionError, "NBT dimension differs"):
            reader.replay(self.evidence)

    def test_missing_payload_is_detected_from_region_even_if_removed_from_manifests(self):
        self.stage()
        self.retain()
        (self.evidence / "overworld/chunks/0.0.chunk").unlink()
        self.reseal()
        with self.assertRaisesRegex(AssertionError, "Missing retained payload"):
            reader.replay(self.evidence)

    def test_payload_cannot_disagree_with_region_even_if_each_has_a_valid_hash(self):
        self.stage()
        self.retain()
        path = self.evidence / "overworld/chunks/0.0.chunk"
        path.write_bytes(path.read_bytes() + b"trailing")
        self.reseal()
        with self.assertRaisesRegex(AssertionError, "byte-for-byte"):
            reader.replay(self.evidence)

    def test_nested_snapshot_must_equal_actual_stopping_dimension_record(self):
        self.stage()
        self.retain()
        path = self.evidence / "overworld/snapshot.json"
        value = read(path)
        value["gameTime"] += 1
        write(path, value)
        self.reseal()
        with self.assertRaisesRegex(AssertionError, "overworld/snapshot"):
            reader.replay(self.evidence)

    def test_external_frozen_pins_are_checked_as_well_as_semantic_truth(self):
        self.stage()
        self.retain()
        for kwargs in (dict(expected_snapshot_sha256="0" * 64), dict(expected_receipt_sha256="0" * 64)):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(AssertionError, "External frozen evidence pin"):
                reader.replay(self.evidence, **kwargs)

    def test_unlisted_files_and_empty_directories_are_not_silently_ignored(self):
        self.stage()
        self.retain()
        extra = self.evidence / "unexpected.txt"
        extra.write_text("not part of retained proof")
        with self.assertRaisesRegex(AssertionError, "retained files"):
            reader.replay(self.evidence)
        extra.unlink()
        (self.evidence / "unexpected-empty").mkdir()
        with self.assertRaisesRegex(AssertionError, "retained directories"):
            reader.replay(self.evidence)

    def test_duplicate_receipt_keys_cannot_hide_a_forged_counter(self):
        self.stage()
        self.retain()
        path = self.evidence / "receipt.json"
        data = path.read_text()
        path.write_text('{"matchedEntities":999,' + data[1:])
        with self.assertRaisesRegex(ValueError, "Duplicate retained JSON key"):
            reader.replay(self.evidence)


if __name__ == "__main__":
    unittest.main()
