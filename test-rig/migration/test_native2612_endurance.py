import copy
import gzip
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import nbtlib as n

import native2612 as native
import native2612_endurance as producer
import native2612_endurance_replay as replay
from native2612_layout import shared_file, region_file
import test_native12111_endurance_replay as retained_controls

VERIFICATION = Path("D:/PowerAdvantage/_verification/base-2612-port-20261112")


def native_control_seal():
    keys = ("NATIVE2612_CONTROL_PROFILE", "NATIVE2612_CONTROL_PROFILE_SHA256",
            "NATIVE2612_CONTROL_ENGINE_RECEIPT", "NATIVE2612_CONTROL_ENGINE_SHA256")
    selected = [os.environ.get(key) for key in keys]
    if any(selected):
        if not all(selected):
            raise ValueError("Select the complete hash-bound native endurance control fixture")
        return replay.seal(Path(selected[0]), selected[1],
            native.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json",
            VERIFICATION / "native2612-forge-64.1.5-code-inventory-01.json",
            "3401E46C31606FC080837075542747F2A63865D8A5F8A5E2AFE4E907A35568E7",
            Path("C:/Users/johnb/.jdks/jdk-25.0.3+9/bin/java.exe"), replay.Budget(), retrospective=True,
            engine_receipt=Path(selected[2]), engine_receipt_sha256=selected[3])
    return replay.seal(VERIFICATION / "native2612-local-candidate-04.json",
        "672B91F20AF5A97936A81783A07F3134A4479D20E40F49972A4EA6F78B577240",
        native.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json",
        VERIFICATION / "native2612-forge-64.1.5-code-inventory-01.json",
        "3401E46C31606FC080837075542747F2A63865D8A5F8A5E2AFE4E907A35568E7",
        Path("C:/Users/johnb/.jdks/jdk-25.0.3+9/bin/java.exe"), replay.Budget(), retrospective=True,
        engine_receipt=VERIFICATION / "native2612-engine-classifier-02/receipt.json",
        engine_receipt_sha256="8DFC3F6CCD287AFA07BC502AF180B9EF5A8D59366F291E83DA4D82C49517979D")


def put_nbt(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(retained_controls.encoded(value)))


def stopped_fixture(root, dimension="minecraft:overworld"):
    world = root / replay.WORLD
    put_nbt(world / "level.dat", n.Compound(Data=n.Compound(DataVersion=n.Int(4790))))
    put_nbt(shared_file(world, "minecraft:world_gen_settings"), n.Compound(
        DataVersion=n.Int(4790), data=n.Compound(seed=n.Long(77), generate_structures=n.Byte(0),
                                              unknown=n.IntArray([3, 2, 1]))))
    chunk = retained_controls.chunk()
    chunk["DataVersion"] = n.Int(4790)
    path = region_file(world, dimension, 0, 0)
    path.parent.mkdir(parents=True)
    path.write_bytes(retained_controls.region(retained_controls.payload(chunk)))
    for station in replay.endurance.CHECKED:
        document = retained_controls.document()
        document["dimension"] = dimension
        retained_controls.put_json(root / "advantage-works-results" / f"{station}-checkpoint-restart-resumed.json", document)
    return world


def complete_report():
    report = retained_controls.complete_report()
    report.update(minecraft="26.1.2", forge="64.1.5")
    report["profiles"] = {mode: copy.deepcopy(next(iter(report["profiles"].values()))) for mode in replay.MODES}
    return report


class Native2612EnduranceContracts(unittest.TestCase):
    def test_complete_two_world_gate_rejects_held_smoke_missing_resume_and_low_ticks(self):
        replay.complete(complete_report())
        for key, value in (("status", "HELD"), ("status", "SMOKE_PASS_NOT_ENDURANCE"),
                           ("minecraft", "1.21.11"), ("duration_seconds", 1799), ("retained_unchanged", False)):
            changed = complete_report()
            changed[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(AssertionError):
                replay.complete(changed)
        for key, value in (("resumed_processing", "FAIL"), ("endurance_batches", 29),
                           ("verified_checkpoints", 32), ("game_ticks", 32399), ("elapsed_seconds", 1799)):
            changed = complete_report()
            changed["profiles"][replay.MODES[1]][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                replay.complete(changed)

    def test_stopped_exact_typed_nbt_all_dimensions_and_nested_custom_dimension(self):
        for dimension in ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "example:deep/cave"):
            with self.subTest(dimension=dimension), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                world = stopped_fixture(root, dimension)
                before = {str(path.relative_to(world)): path.read_bytes() for path in world.rglob("*") if path.is_file()}
                actual = replay.raw_final(root, replay.Budget())
                self.assertEqual("PASS", actual["status"])
                self.assertEqual(4790, actual["dataVersion"])
                self.assertEqual(len(replay.endurance.CHECKED), actual["comparedRows"])
                self.assertEqual(before, {str(path.relative_to(world)): path.read_bytes() for path in world.rglob("*") if path.is_file()})
                station = replay.endurance.CHECKED[0]
                path = root / "advantage-works-results" / f"{station}-checkpoint-restart-resumed.json"
                document = json.loads(path.read_text())
                value = retained_controls.tile()
                value["Items"][0]["count"] = n.Int(2)
                document["snapshot"][0]["nbt"] = value.snbt()
                path.write_text(json.dumps(document))
                with self.assertRaisesRegex(AssertionError, "raw state/component mismatch"):
                    replay.raw_final(root, replay.Budget())

    def test_missing_external_worldgen_is_not_recovered_from_level_dat(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            world = stopped_fixture(root)
            shared_file(world, "minecraft:world_gen_settings").unlink()
            with self.assertRaises(FileNotFoundError):
                replay.raw_final(root, replay.Budget())

    def test_native_terrain_rejects_old_version_wrong_count_and_packed_tile(self):
        native_chunk = retained_controls.chunk()
        with self.assertRaises(AssertionError):
            replay.terrain(retained_controls.payload(native_chunk), (0, 0))
        native_chunk["DataVersion"] = n.Int(4790)
        for compression in (1, 2, 3):
            replay.terrain(retained_controls.payload(native_chunk, compression), (0, 0))
        for callback in (lambda value: value.__setitem__("DataVersion", n.Short(4790)),
                         lambda value: value["block_entities"][0]["Items"][0].__setitem__("count", n.Byte(1)),
                         lambda value: value["block_entities"][0].__setitem__("keepPacked", n.Byte(1))):
            changed = copy.deepcopy(native_chunk)
            callback(changed)
            with self.assertRaises(AssertionError):
                replay.terrain(retained_controls.payload(changed), (0, 0))

    def test_preseal_time_precedes_both_setups_retrospective_never_becomes_genuine(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for mode in replay.MODES:
                retained_controls.put_json(root / mode / "advantage-works-results/W-01-checkpoint-setup.json",
                                           dict(recordedAtUtc="2026-11-12T04:00:00+00:00"))
            seal = dict(purpose=replay.PRE_RUN, createdAtUtc="2026-11-12T03:59:59+00:00")
            self.assertTrue(replay.custody(seal, root, replay.Budget())["preRunCustody"])
            seal["createdAtUtc"] = "2026-11-12T04:00:01+00:00"
            with self.assertRaises(AssertionError):
                replay.custody(seal, root, replay.Budget())
            seal["purpose"] = replay.RETROSPECTIVE
            self.assertFalse(replay.custody(seal, root, replay.Budget())["preRunCustody"])

    def test_hold_request_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "hold-endurance.request").touch()
            with self.assertRaises(producer.EnduranceHold):
                producer.check_hold(root)

    def test_qualified_run_cannot_silently_omit_retained_world(self):
        with self.assertRaisesRegex(ValueError, "original-world migration"):
            producer.retained_input(SimpleNamespace(retained=None, seconds=1800))
        self.assertIsNone(producer.retained_input(SimpleNamespace(retained=None, seconds=60)))

    def test_retained_receipt_must_be_full_original110_not_hook_only_or_an_old_candidate(self):
        prior = native.read_json(native.RIG / "build/native2612-migration-candidate04-original110-02/native2612-upgrade-results.json")
        profile_sha = "672B91F20AF5A97936A81783A07F3134A4479D20E40F49972A4EA6F78B577240"
        inventory_sha = "3401E46C31606FC080837075542747F2A63865D8A5F8A5E2AFE4E907A35568E7"
        producer.require_retained_receipt(prior, profile_sha, inventory_sha)
        for field, value in (("route", "released110"), ("scope", "INSTRUMENTED_MIGRATION_HOOK_CONVERSION_RESTART_ONLY"),
                             ("profileSha256", "0" * 64), ("referenceUnchanged", False)):
            changed = copy.deepcopy(prior)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                producer.require_retained_receipt(changed, profile_sha, inventory_sha)
        for field in ("operation", "restarted"):
            changed = copy.deepcopy(prior)
            changed["phases"][field] = {}
            with self.subTest(phase=field), self.assertRaises(AssertionError):
                producer.require_retained_receipt(changed, profile_sha, inventory_sha)

    def test_every_interval_has_real_18_tps_and_starvation_is_not_a_gameplay_diagnosis(self):
        before = dict(worldTime=100, recordedAtUtc="2026-11-12T04:00:00+00:00")
        after = dict(worldTime=1300, recordedAtUtc="2026-11-12T04:01:00+00:00")
        self.assertEqual(20, producer.interval_metrics(before, after, "cycle-001")["ticksPerSecond"])
        after["worldTime"] = 1179
        with self.assertRaisesRegex(producer.ThroughputFailure, "not a gameplay diagnosis"):
            producer.interval_metrics(before, after, "cycle-001")
        after["worldTime"] = True
        with self.assertRaises(AssertionError):
            producer.interval_metrics(before, after, "cycle-001")

    def test_fresh_world_info_retains_full_native_unknown_data(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            world = stopped_fixture(root)
            actual = producer.world_info(world)
            self.assertEqual(77, actual["RandomSeed"])
            self.assertEqual(0, actual["MapFeatures"])
            self.assertIn("unknown", actual["fullWorldgenSnbt"])
            self.assertIn("[I; 3, 2, 1]", actual["fullWorldgenSnbt"])

    def test_frozen_oracle_verifier_rejects_changed_bytes_duplicate_fields_and_wrong_purpose(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            oracle = root / "oracle.py"
            oracle.write_text("test fixture, not a production oracle")
            seal_path = root / "seal.json"
            document = native_control_seal()
            document["files"][str(oracle)] = native.digest(oracle)
            seal_path.write_text(json.dumps(document))
            pin = native.digest(seal_path)
            replay.verify_seal(seal_path, pin, replay.Budget())
            oracle.write_text("changed")
            with self.assertRaises(AssertionError):
                replay.verify_seal(seal_path, pin, replay.Budget())
            seal_path.write_text('{"format":1,"format":1}')
            with self.assertRaises(ValueError):
                replay.verify_seal(seal_path, native.digest(seal_path), replay.Budget())

    def test_full_closure_binds_classifier_review_receipt_and_native_primary_sources(self):
        document = native_control_seal()
        self.assertEqual(replay.RETROSPECTIVE, document["purpose"], "Unit control is never a genuine qualification seal")
        self.assertIn(str(Path(native.__file__).resolve()), document["files"])
        self.assertIn(str(Path(replay.platform_diagnostics.__file__).resolve()), document["files"])
        self.assertIn(document["engineReceipt"], document["files"])
        self.assertIn(replay.platform_diagnostics.SOURCES_JAR_SHA, document["files"].values())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "seal.json"
            for change in ("omit-oracle", "omit-engine", "old-purpose"):
                altered = copy.deepcopy(document)
                if change == "omit-oracle":
                    del altered["files"][altered["oracles"][0]]
                elif change == "omit-engine":
                    del altered["files"][altered["engineReceipt"]]
                else:
                    altered["purpose"] = "NATIVE61_ENDURANCE_PRE_RUN_SEAL"
                path.write_text(json.dumps(altered))
                with self.subTest(change=change), self.assertRaises(AssertionError):
                    replay.verify_seal(path, native.digest(path), replay.Budget())


if __name__ == "__main__":
    unittest.main()
