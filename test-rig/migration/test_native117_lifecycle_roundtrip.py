"""First native callback receipts, independent of eventual machine outcomes."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

import lifecycle
import native117_lifecycle as native
from matrix import write_json


class FirstCallbackTests(unittest.TestCase):
    definition = [{"id": "drill", "to": [1, 80, 1]}]

    def runner(self, folder):
        runner = native.NativeLifecycleRun(Path(folder), None, None, self.definition)
        runner._round_trip_ready = Mock(return_value=["fixture-chunks-ready"])
        return runner

    def document(self, nbt='{progress:12s,targetX:2,targetY:80,targetZ:1,TargetState:{Name:"minecraft:stone"}}'):
        row = dict(pos=[1, 80, 1], loaded=True, tileIdentity=42, nbt=nbt)
        flags = {name: True for name in native.ROUND_TRIP_FLAGS}
        return dict(worldTime=101, cases={"drill": [row]}, roundTrip=dict(
            protocol=native.ROUND_TRIP_PROTOCOL, operation="changed-target-read", label="changed",
            status="PASS", dimension="minecraft:overworld", startPhase="START", endPhase="END",
            startWorldTime=100, endWorldTime=101, mutationCount=1,
            prerequisites={"drill": dict(flags=flags, tileIdentity=42)},
            callbacks={"drill": dict(calls=1, completed=1, valid=True, worldTime=101,
                                     prerequisiteFlags={key: True for key in native.CALLBACK_FLAGS},
                                     nativeStack=["net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity.tick(LevelChunk.java:844)",
                                                  "net.minecraft.world.level.Level.tickBlockEntities(Level.java:488)"],
                                     nativeStackValidated=True, before=copy.deepcopy(row), after=row)}))

    def check(self, doc):
        return native.require_round_trip(doc, self.definition, "changed-target-read", "changed")

    def test_complete_natural_callback_is_accepted_even_when_strict_machine_oracle_fails(self):
        doc = self.document()
        self.assertIs(self.check(doc), doc)
        self.assertEqual(lifecycle.changed_target_results(self.definition, doc, doc)["drill"]["status"], "FAIL")

    def test_clock_advance_without_callback_proof_is_rejected(self):
        doc = self.document()
        del doc["roundTrip"]
        with self.assertRaises(AssertionError):
            self.check(doc)

    def test_each_prerequisite_is_required(self):
        for name in native.ROUND_TRIP_FLAGS:
            for value in (False, None, 1):
                doc = self.document()
                doc["roundTrip"]["prerequisites"]["drill"]["flags"][name] = value
                with self.subTest(name=name, value=value), self.assertRaises(AssertionError):
                    self.check(doc)

    def test_wrong_phase_tick_world_operation_label_or_incomplete_dispatch_fails(self):
        for key, value in (("endPhase", "START"), ("startPhase", "END"), ("endWorldTime", 108),
                           ("dimension", "minecraft:the_nether"), ("operation", "legacy-read"),
                           ("label", "stale"), ("mutationCount", 2), ("status", "FAIL")):
            doc = self.document()
            doc["roundTrip"][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.check(doc)
        for key, value in (("calls", 0), ("calls", 2), ("completed", 0), ("valid", False),
                           ("nativeStackValidated", False), ("worldTime", 108)):
            doc = self.document()
            doc["roundTrip"]["callbacks"]["drill"][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.check(doc)

    def test_missing_duplicate_replaced_or_changed_first_callback_is_rejected(self):
        for change in ("missing", "extra", "replaced", "later"):
            doc = self.document()
            if change == "missing":
                doc["roundTrip"]["callbacks"].clear()
            elif change == "extra":
                doc["roundTrip"]["callbacks"]["other"] = doc["roundTrip"]["callbacks"]["drill"]
            elif change == "replaced":
                doc["roundTrip"]["prerequisites"]["drill"]["tileIdentity"] = 99
            else:
                doc["cases"]["drill"][0] = {**doc["cases"]["drill"][0], "nbt": "{progress:0s}"}
            with self.subTest(change=change), self.assertRaises(AssertionError):
                self.check(doc)

    def test_probe_issues_one_operation_and_never_resamples_a_failed_first_machine_result(self):
        with tempfile.TemporaryDirectory() as folder:
            runner = self.runner(folder)
            doc = self.document()
            def probe(operation, label):
                write_json(Path(folder) / "advantage-works-results" / ("lifecycle-" + label + ".json"), doc)
                return doc
            runner._raw_probe = Mock(side_effect=probe)
            runner._wait_ready = Mock(side_effect=AssertionError("Clock-based resampling forbidden"))
            self.assertIs(runner.probe("changed-target-read", "changed"), doc)
            runner._round_trip_ready.assert_called_once_with("changed")
            runner._raw_probe.assert_called_once_with("changed-target-read", "changed")
            runner._wait_ready.assert_not_called()

    def test_failed_first_legacy_callback_blocks_changed_mutation_and_is_not_observer_repaired(self):
        with tempfile.TemporaryDirectory() as folder:
            runner = self.runner(folder)
            doc = self.document('{progress:227s,targetX:2,targetY:80,targetZ:1}')
            doc["roundTrip"].update(operation="legacy-read", label="save-read-legacy")
            runner._raw_probe = Mock(side_effect=[doc, doc, self.document("{progress:0s}")])
            with self.assertRaisesRegex(AssertionError, "First eligible natural callback failed legacy restoration"):
                runner.save_read_guards()
            self.assertEqual([call.args[0] for call in runner._raw_probe.call_args_list], ["snapshot", "legacy-read"])
            self.assertEqual(runner.observations[-1]["classification"], "ELIGIBLE_FIRST_CALLBACK_PRODUCTION_GUARD_FAILURE")

    def test_zero_energy_cannot_delay_the_first_callback_or_make_a_bad_machine_result_pass(self):
        doc = self.document()
        for row in [doc["cases"]["drill"][0], doc["roundTrip"]["callbacks"]["drill"]["before"]]:
            row["energy"] = {"steam": 0}
        self.check(doc)
        self.assertEqual(lifecycle.changed_target_results(self.definition, doc, doc)["drill"]["status"], "FAIL")

    def test_stack_flags_cannot_replace_the_actual_native_dispatch_frames(self):
        for stack in ([], ["Harness.forceTick(Test.java:1)"], ["net.minecraft.world.level.Level.tickBlockEntities(Level.java:1)"],
                      ["net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity.tick(LevelChunk.java:844)",
                       "net.minecraft.world.level.Level.<init>(Level.java:1)"],
                      ["net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity.tick(LevelChunk.java:844)",
                       "net.minecraft.world.level.Level.setBlock(Level.java:1)"]):
            doc = self.document()
            doc["roundTrip"]["callbacks"]["drill"]["nativeStack"] = stack
            with self.assertRaises(AssertionError):
                self.check(doc)

    def test_callback_prerequisites_cannot_be_substituted_by_earlier_start_flags(self):
        for key in native.CALLBACK_FLAGS:
            doc = self.document()
            doc["roundTrip"]["callbacks"]["drill"]["prerequisiteFlags"][key] = False
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.check(doc)

    def test_wrong_position_or_unloaded_callback_row_is_rejected(self):
        for key, value in (("pos", [2, 80, 1]), ("loaded", False)):
            doc = self.document()
            doc["roundTrip"]["callbacks"]["drill"]["before"][key] = value
            with self.assertRaises(AssertionError):
                self.check(doc)

    def test_legacy_first_callback_progress_or_target_loss_blocks_changed_read(self):
        for nbt in ('{progress:0s,TargetState:{Name:"minecraft:stone"}}',
                    '{progress:12s,targetX:99,targetY:80,targetZ:1,TargetState:{Name:"minecraft:stone"}}'):
            with tempfile.TemporaryDirectory() as folder:
                runner = self.runner(folder)
                doc = self.document()
                doc["roundTrip"].update(operation="legacy-read", label="legacy")
                doc["roundTrip"]["callbacks"]["drill"]["after"]["nbt"] = nbt
                runner._raw_probe = Mock(return_value=doc)
                with self.assertRaisesRegex(AssertionError, "First eligible natural callback failed legacy restoration"):
                    runner.probe("legacy-read", "legacy")
                runner._raw_probe.assert_called_once()
                self.assertEqual(runner.observations[-1]["classification"], "ELIGIBLE_FIRST_CALLBACK_PRODUCTION_GUARD_FAILURE")


if __name__ == "__main__":
    unittest.main()
