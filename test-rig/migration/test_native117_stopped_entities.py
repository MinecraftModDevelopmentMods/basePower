"""Synthetic stop-file/retention controls, not native shutdown runtime proof."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from matrix import WORLD
import native117_stopped_entities as stopped
from test_native117_entity_storage import entity, entry, put


class StoppedEntitiesAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / "runtime"
        self.world = self.runtime / WORLD
        self.world.mkdir(parents=True)
        (self.runtime / "advantage-migration.allow").write_text("marked synthetic unit fixture")
        self.doc = dict(format=1, status="CAPTURED", minecraftVersion="1.17.1", dataVersion=2730,
            phase="FMLServerStoppingEvent", bootId="12345678-1234-1234-1234-123456789abc",
            worldName="AdvantageWorksMigration", worldDirectory=str(self.world), entityCount=0,
            enumeration="ServerLevel.getAllEntities", dimensions={})
        for key, (identity, _) in stopped.DIMENSIONS.items():
            self.doc["dimensions"][key] = dict(dimension=identity, worldDimensionKey=key, gameTime=10, entityCount=0, entities=[])
        self.index = 0

    def stage(self, key="minecraft:overworld", words=(1, 2, 3, 4)):
        actor = entity(words)
        row = entry(actor, uuid=stopped.entity_storage._identity(actor), id="minecraft:chest_minecart", worldDimensionKey=key)
        record = self.doc["dimensions"][key]
        record["entities"].append(row)
        record["entityCount"] += 1
        self.doc["entityCount"] += 1
        put(self.world / stopped.DIMENSIONS[key][1], (0, 0), [actor])

    def audit(self):
        self.index += 1
        self.evidence = self.root / ("evidence-" + str(self.index))
        self.source = self.runtime / stopped.SNAPSHOT
        self.source.write_text(json.dumps(self.doc), encoding="utf-8")
        return stopped.audit_stopped(self.runtime, self.evidence)

    def test_retains_exact_stopped_bytes_and_uses_actual_raw_entity_reader(self):
        self.stage()
        before = copy.deepcopy(self.doc)
        result = self.audit()
        data = self.source.read_bytes()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(data, (self.evidence / stopped.SNAPSHOT).read_bytes())
        self.assertEqual(hashlib.sha256(data).hexdigest().upper(), result["snapshotSha256"])
        self.assertEqual((1, 1, 1), (result["auditedDimensions"], result["snapshotRecords"], result["matchedEntities"]))
        self.assertEqual("SKIP_EMPTY_STOP_SNAPSHOT", result["dimensions"]["minecraft:the_nether"]["status"])
        self.assertEqual(before, self.doc)

    def test_all_three_nonempty_dimensions_are_audited(self):
        for index, key in enumerate(stopped.DIMENSIONS):
            self.stage(key, (index + 1, 2, 3, 4))
        self.assertEqual(3, self.audit()["auditedDimensions"])

    def test_all_empty_is_not_an_entity_proof_pass(self):
        result = self.audit()
        self.assertEqual("NO_NONEMPTY_DIMENSIONS", result["status"])
        self.assertEqual(0, result["matchedEntities"])

    def test_pending_failed_and_live_phase_records_fail(self):
        for key, value in (("status", "PENDING_STOP"), ("status", "FAILED"), ("phase", "live-snapshot"),
                           ("dataVersion", 2724), ("dataVersion", True), ("enumeration", "fixture-projection")):
            before = copy.deepcopy(self.doc)
            self.doc[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.audit()
            self.doc = before

    def test_missing_dimension_wrong_identity_and_counts_fail(self):
        baseline = copy.deepcopy(self.doc)
        self.doc["dimensions"].pop("minecraft:the_end")
        with self.assertRaises(ValueError): self.audit()
        self.doc = copy.deepcopy(baseline)
        self.doc["dimensions"]["minecraft:the_nether"]["dimension"] = 0
        with self.assertRaises(ValueError): self.audit()
        self.doc = copy.deepcopy(baseline)
        self.doc["entityCount"] = 1
        with self.assertRaises(AssertionError): self.audit()
        self.doc = copy.deepcopy(baseline)
        self.doc["dimensions"]["minecraft:overworld"]["entityCount"] = 1
        with self.assertRaises(ValueError): self.audit()

    def test_cross_dimension_duplicate_uuid_is_not_ignored(self):
        self.stage()
        self.stage("minecraft:the_nether")
        with self.assertRaisesRegex(AssertionError, "across dimensions"):
            self.audit()

    def test_wrong_world_and_malformed_boot_identity_fail(self):
        original = self.doc["worldDirectory"]
        self.doc["worldDirectory"] = str(self.root / "unrelated-world")
        with self.assertRaisesRegex(AssertionError, "another world"):
            self.audit()
        self.doc["worldDirectory"] = original
        self.doc["bootId"] = "not-a-uuid"
        with self.assertRaises(ValueError): self.audit()

    def test_changed_raw_inventory_fails_without_a_tick_drift_allowance(self):
        self.stage()
        changed = entity()
        changed["Items"][0]["Count"] = type(changed["Items"][0]["Count"])(6)
        put(self.world, (0, 0), [changed])
        with self.assertRaisesRegex(AssertionError, "Complete saved entity NBT"):
            self.audit()
        self.assertEqual("FAIL", json.loads((self.evidence / "receipt.json").read_text())["status"])

    def test_immutable_copy_and_existing_evidence_cannot_be_overwritten(self):
        self.stage()
        self.audit()
        retained = (self.evidence / stopped.SNAPSHOT).read_bytes()
        self.source.write_text("new boot overwrote runtime output")
        self.assertEqual(retained, (self.evidence / stopped.SNAPSHOT).read_bytes())
        with self.assertRaises(FileExistsError):
            stopped.audit_stopped(self.runtime, self.evidence)

    def test_stop_file_change_during_audit_fails(self):
        self.stage()
        original = stopped.entity_storage.audit

        def overwrite(*args, **kwargs):
            result = original(*args, **kwargs)
            self.source.write_text("reused by another boot")
            return result

        with patch.object(stopped.entity_storage, "audit", side_effect=overwrite), \
             self.assertRaisesRegex(AssertionError, "changed during"):
            self.audit()

    def test_runtime_marker_and_matching_native_type_attestation_are_required(self):
        self.stage()
        self.doc["dimensions"]["minecraft:overworld"]["entities"][0]["id"] = "minecraft:pig"
        with self.assertRaisesRegex(AssertionError, "type attestation"):
            self.audit()
        (self.runtime / "advantage-migration.allow").unlink()
        with self.assertRaisesRegex(ValueError, "not explicitly marked"):
            self.audit()

    def test_duplicate_json_keys_cannot_turn_pending_into_captured(self):
        self.source = self.runtime / stopped.SNAPSHOT
        data = json.dumps(self.doc)
        self.source.write_text('{"status":"PENDING_STOP",' + data[1:])
        with self.assertRaisesRegex(ValueError, "Duplicate stopping snapshot JSON key"):
            stopped.audit_stopped(self.runtime, self.root / "duplicate-json-evidence")


if __name__ == "__main__":
    unittest.main()
