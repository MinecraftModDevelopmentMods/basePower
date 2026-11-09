"""Adversarial receipt checks; these do not substitute for a native server run."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

import native117_readiness as native

BOUNDS = (74, 63, 75, 64)


def sample():
    initial = [dict(x=x, z=z, blockAndEntitiesLoaded=True, entityTicking=False)
               for x, z in sorted(native.chunk_set(BOUNDS))]
    ready = [dict(row, entityTicking=True) for row in initial]
    return dict(protocol=native.PROTOCOL, status="PASS", label="harvest-exercise", minecraft="1.17.1",
                dimension="minecraft:overworld", bounds=dict(zip(("minX", "minZ", "maxX", "maxZ"), BOUNDS)),
                checks=3, elapsedMillis=100, requestedWorldTime=1, completedWorldTime=4,
                maximumChecks=1200, maximumWaitMillis=120000, chunks=ready,
                transitions=[dict(check=1, worldTime=2, changes=initial), dict(check=3, worldTime=4, changes=ready)])


class NativeReadinessTests(unittest.TestCase):
    def test_actual_transition_replay_is_required_for_all_boundary_chunks(self):
        value = sample()
        self.assertIs(value, native.require_ready(value, BOUNDS, "harvest-exercise"))

    def test_missing_chunks_duplicates_wrong_positions_and_boolean_coordinates_fail(self):
        for mutate in (lambda rows: rows.pop(), lambda rows: rows.append(rows[0]),
                       lambda rows: rows[0].update(x=999), lambda rows: rows[0].update(x=True)):
            value = sample()
            mutate(value["chunks"])
            with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                native.require_ready(value, BOUNDS, "harvest-exercise")

    def test_either_native_readiness_fact_false_or_truthy_nonboolean_is_rejected(self):
        for field in ("blockAndEntitiesLoaded", "entityTicking"):
            for state in (False, 1, "true", None):
                value = sample()
                value["chunks"][0][field] = state
                with self.subTest(field=field, state=state), self.assertRaises(AssertionError):
                    native.require_ready(value, BOUNDS, "harvest-exercise")

    def test_timeouts_failure_protocol_label_and_dimension_cannot_be_waived(self):
        for field, bad in (("status", "FAIL"), ("protocol", "legacy"), ("label", "another"),
                           ("dimension", ""), ("dimension", "minecraft:the_nether"),
                           ("checks", 0), ("checks", True), ("checks", 1201),
                           ("elapsedMillis", 120001), ("maximumChecks", 1300),
                           ("maximumWaitMillis", 150000), ("completedWorldTime", 0)):
            value = sample()
            value[field] = bad
            with self.subTest(field=field, bad=bad), self.assertRaises(AssertionError):
                native.require_ready(value, BOUNDS, "harvest-exercise")

    def test_forged_truncated_or_redundant_transitions_cannot_prove_completion(self):
        for mutate in (lambda value: value.update(transitions=[]),
                       lambda value: value["transitions"][0]["changes"].pop(),
                       lambda value: value["transitions"][1].update(check=1),
                       lambda value: value["transitions"][1].update(worldTime=0),
                       lambda value: value["transitions"].pop(),
                       lambda value: value["transitions"][1]["changes"][0].update(entityTicking=False),
                       lambda value: value.update(checks=4)):
            value = sample()
            mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                native.require_ready(value, BOUNDS, "harvest-exercise")

    def test_bounds_and_labels_are_bounded_before_any_console_command(self):
        for bounds in ((0, 0, 16, 15), (0, 0, 64, 0), (1, 0, 0, 0),
                       (-1875001, 0, -1875001, 0), (False, 0, 0, 0)):
            with self.subTest(bounds=bounds), self.assertRaises(AssertionError): native.chunk_set(bounds)
        self.assertEqual(256, len(native.chunk_set((0, 0, 15, 15))))

    def test_command_is_read_only_and_native_end_checks_are_literal(self):
        source = (Path(__file__).resolve().parents[1] /
                  "src/main/java/zone/moddev/mc/advantageworks/Native117Readiness.java").read_text()
        self.assertIn("TickEvent.Phase.END", source)
        self.assertIn("world.isPositionTickingWithEntitiesLoaded(pos)", source)
        self.assertIn("world.isPositionEntityTicking(new ChunkPos(x, z))", source)
        for forbidden in ("setChunkForced(", "setChanged(", "setBlock(", "getChunk(",
                          "addFreshEntity(", "getPrivateValue(", "Thread.sleep("):
            self.assertNotIn(forbidden, source)

    def test_python_waits_for_result_validates_evidence_then_requires_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            calls = []
            class Server:
                def send(self, command): calls.append(command)
                def wait(self, marker, timeout):
                    calls.append((marker, timeout))
                    if marker.startswith("ADVREADINESS RESULT"):
                        target = runtime / "advantage-works-results/native-readiness-harvest-exercise.json"
                        target.parent.mkdir()
                        target.write_text(json.dumps(sample()))
            native.await_ready(Server(), runtime, BOUNDS, "harvest-exercise")
            self.assertEqual("advreadiness chunks 74 63 75 64 harvest-exercise", calls[0])
            self.assertEqual(("ADVREADINESS RESULT harvest-exercise status=", 150), calls[1])
            self.assertEqual(("ADVREADINESS COMPLETE harvest-exercise", 10), calls[2])


if __name__ == "__main__": unittest.main()
