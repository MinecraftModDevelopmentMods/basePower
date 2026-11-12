"""Keep the native capability checks strict; synthetic documents are not gameplay proof."""

import copy
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native263_interop as adapter


def observation(phase="exercise"):
    ids = adapter.contracts.EXERCISE_CHECK_IDS if phase == "exercise" else adapter.contracts.RESTORED_CHECK_IDS
    return dict(phase=phase, status="PASS", worldTime=20,
                checks=[dict(id=key, status="PASS") for key in sorted(ids)],
                sharedChecks=[dict(id="basemetals/native-power-crushing", status="PASS")])


class Native263InteropTest(unittest.TestCase):
    def validate(self, document, phase="exercise"):
        adapter.validate_observation(document, phase, dict(completedWorldTime=20))

    def test_both_phases_retain_the_exact_interface_contracts(self):
        for phase in ("exercise", "restored"):
            self.validate(observation(phase), phase)
        self.assertEqual(("26.3", "66.0.0"), (adapter.native.MINECRAFT, adapter.native.FORGE))

    def test_missing_duplicate_and_failed_capability_witnesses_are_rejected(self):
        document = observation()
        changes = [copy.deepcopy(document) for _ in range(3)]
        changes[0]["checks"].pop()
        changes[1]["checks"][-1] = changes[1]["checks"][0]
        changes[2]["checks"][0]["status"] = "FAIL"
        for changed in changes:
            with self.subTest(checks=changed["checks"]), self.assertRaises(AssertionError):
                self.validate(changed)

    def test_wrong_phase_time_or_overall_status_cannot_be_pass(self):
        for field, value in (("phase", "restored"), ("worldTime", 19), ("worldTime", 20.0), ("status", "MANUAL_REQUIRED")):
            changed = observation()
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                self.validate(changed)

    def test_central_base_metals_crushing_is_mandatory(self):
        for checks in ([], [dict(id="basemetals/native-power-crushing", status="SKIP")],
                       [dict(id="local-fallback", status="PASS")],
                       [dict(id="basemetals/native-power-crushing", status="PASS")] * 2):
            changed = observation()
            changed["sharedChecks"] = checks
            with self.subTest(checks=checks), self.assertRaises(AssertionError):
                self.validate(changed)

    def test_survival_oil_contract_keeps_both_oils_and_all_ten_checks(self):
        ids = [f"{oil}/{check}" for oil in ("crude_oil", "refined_oil")
               for check in ("world-roundtrip", "machine-roundtrip", "machine-below-bucket",
                             "machine-insufficient-room", "machine-full")]
        good = dict(gameMode="survival", worldTime=20, checks=[dict(id=key, status="PASS") for key in ids])
        adapter.previous.require_oil_buckets(good, dict(completedWorldTime=20))
        for field, value in (("gameMode", "creative"), ("checks", good["checks"][:-1]), ("worldTime", 19)):
            changed = copy.deepcopy(good)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                adapter.previous.require_oil_buckets(changed, dict(completedWorldTime=20))

    def test_preflight_does_not_start_or_stage_a_world(self):
        args = SimpleNamespace(port=26601, preflight_only=True)
        inputs = SimpleNamespace(receipt=lambda: dict(minecraft="26.3", forge="66.0.0"))
        with patch.object(adapter, "preflight", return_value=inputs), \
                patch.object(adapter.native, "stage") as stage, patch.object(adapter.native, "Server") as server:
            result = adapter.run(args)
        self.assertEqual("READY_NOT_RUN", result["status"])
        self.assertFalse(result["qualificationAllowed"])
        stage.assert_not_called()
        server.assert_not_called()

    def test_privileged_port_is_refused_before_inputs_or_launch(self):
        with patch.object(adapter, "preflight") as preflight, patch.object(adapter.native, "Server") as server:
            with self.assertRaises(ValueError):
                adapter.run(SimpleNamespace(port=80))
        preflight.assert_not_called()
        server.assert_not_called()


if __name__ == "__main__":
    unittest.main()
