import copy
from pathlib import Path
import unittest
from unittest.mock import patch

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

    def test_server_review_does_not_open_client_classification(self):
        self.assertEqual("NATIVE66", diagnostics.require_controls()["engineProtocol"])
        with self.assertRaisesRegex(ValueError, "client diagnostic sequences pending"):
            diagnostics.require_client_controls()
        for text in ("java.lang.IllegalStateException: actual defect", "[now] [main/ERROR] [mod/]: failure",
                     "\tat zone.moddev.mc.basepower.Machine.tick(Machine.java:1)"):
            self.assertTrue(native.log_findings(text)[0])
        self.assertEqual(([], []), native.log_findings("[now] [main/INFO] [minecraft/]: All dimensions are saved"))

    @staticmethod
    def text(rows):
        return "\n".join(f"[now] [{row['thread']}/{row['level']}] [{row['logger']}]: {row['message']}\n"
                         + "\n".join(row["body"]) for row in rows)

    def test_each_actual_renderer_appender_sequence_is_exact_and_server_only(self):
        import native263_client_diagnostics as client
        for entry in diagnostics.server_fixture()["sequences"]:
            text = self.text(entry["blocks"])
            with self.subTest(kind=entry["kind"], renderer=entry["renderer"], appender=entry["appender"]):
                failures, notes = diagnostics.classify(text)
                self.assertEqual([], failures)
                self.assertEqual(len(entry["blocks"]), len(notes))
                self.assertTrue(client.classify(text)[0])
                self.assertTrue(diagnostics.strict(text)[0])

    def test_missing_duplicated_reordered_or_changed_native_blocks_fail(self):
        for entry in diagnostics.server_fixture()["sequences"]:
            baseline = entry["blocks"]
            for index in range(len(baseline)):
                changes = [baseline[:index] + baseline[index + 1:], baseline + [baseline[index]]]
                if len(baseline) > 1:
                    changes.append(list(reversed(baseline)))
                for rows in changes:
                    if not rows:
                        continue  # No diagnostic text makes no exception claim.
                    with self.subTest(kind=entry["kind"], index=index):
                        self.assertTrue(diagnostics.classify(self.text(rows))[0])
            for field, value in (("thread", "Unproven thread"), ("level", "FATAL"), ("logger", "unproven/"),
                                 ("message", "A different native diagnostic"),
                                 ("body", ["java.lang.IllegalStateException: actual defect"])):
                changed = copy.deepcopy(baseline)
                changed[0][field] = value
                if entry["kind"] == "unsafe" and field == "message":
                    changed[0][field] = "sun.misc.Unsafe: unavailable (different unproven reason)"
                with self.subTest(kind=entry["kind"], field=field):
                    self.assertTrue(diagnostics.classify(self.text(changed))[0])
            changed = self.text(baseline) + "\n[now] [Server thread/ERROR] [basepower/]: real machine failure"
            self.assertTrue(diagnostics.classify(changed)[0])

    def test_altered_frame_line_or_native_engine_version_is_not_a_waiver(self):
        for entry in diagnostics.server_fixture()["sequences"]:
            text = self.text(entry["blocks"])
            for before, after in (("SystemReport.java:116", "SystemReport.java:115"),
                                  ("CleanerJava9.java:", "CleanerJava9.java:9"),
                                  ("minecraft@26.3", "minecraft@26.2"), ("Native Method", "Changed Method")):
                if before in text:
                    with self.subTest(before=before):
                        self.assertTrue(diagnostics.classify(text.replace(before, after))[0])

    def test_fixture_tamper_is_rejected_before_any_classification(self):
        with patch.object(diagnostics, "SERVER_FIXTURE_SHA", "0" * 64), self.assertRaises(AssertionError):
            diagnostics.classify("[now] [main/INFO] [minecraft/]: innocent")

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
