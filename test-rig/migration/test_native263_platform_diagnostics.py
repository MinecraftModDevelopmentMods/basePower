import copy
from pathlib import Path
import unittest

import native263 as native
import native263_platform_diagnostics as diagnostics
import native263_actors as actors


class Native66DiagnosticsContract(unittest.TestCase):
    def test_no_262_or_earlier_stack_fixture_is_automatically_waived(self):
        root = Path(__file__).with_name("fixtures")
        for name in ("native262-server-host-perflib.json", "native262-server-netty-java25.json"):
            text = (root / name).read_text(encoding="utf-8")
            errors, notes = diagnostics.classify(text, lambda _: ([], ["old waiver"]))
            self.assertTrue(errors)
            self.assertEqual([], notes)

    def test_runtime_review_stays_pending_after_worldless_controls(self):
        with self.assertRaisesRegex(ValueError, "worldless controls alone"):
            diagnostics.require_controls()
        for text in ("java.lang.IllegalStateException: actual defect", "[now] [main/ERROR] [mod/]: failure",
                     "\tat zone.moddev.mc.basepower.Machine.tick(Machine.java:1)"):
            self.assertTrue(native.log_findings(text)[0])
        self.assertEqual(([], []), native.log_findings("[now] [main/INFO] [minecraft/]: All dimensions are saved"))

    def test_actual_new_wolf_receipts_are_bound_and_mutations_still_fail(self):
        from test_native12111_actor_network_adapters import NativeDifferenceAssessment
        fixture = NativeDifferenceAssessment()
        fixture.setUp()
        proof = Path("D:/PowerAdvantage/_verification/base-263-port-20261112/harness/controls-07/NativeWolfPlatformControl")
        strict = actors.verdict(actors.dimensions.verdict, fixture.before, fixture.after)
        result = actors.assess_native(fixture.before, fixture.after, strict, proof, "strict", True)
        self.assertEqual("PASS_WITH_NATIVE_DIFFERENCE", result["status"])
        self.assertEqual("FAIL", result["rawStrictResult"]["status"])
        changed = copy.deepcopy(fixture.after)
        changed["player"]["Inventory"][0]["Count"] = 16
        strict = actors.verdict(actors.dimensions.verdict, fixture.before, changed)
        self.assertEqual("FAIL", actors.assess_native(fixture.before, changed, strict, proof, "strict", True)["status"])


if __name__ == "__main__":
    unittest.main()
