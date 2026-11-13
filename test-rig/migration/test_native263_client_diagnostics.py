from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native263_client_diagnostics as diag
import native263_platform_diagnostics as platform

CONTROL_ROOT = Path("D:/PowerAdvantage/_verification/base-263-port-20261112/client-preparation-01")


def text_of(rows):
    return "\n".join(row.get("preamble", "") if "preamble" in row else
                     f'[native66] [{row["thread"]}/{row["level"]}] [{row["logger"]}]: {row["message"]}\n' + "\n".join(row["body"])
                     for row in rows)


class ClientPlatformTests(unittest.TestCase):
    def fixture(self):
        return diag.client_fixture()

    def sequence(self, kind):
        return deepcopy(next(row["blocks"] for row in self.fixture()["sequences"] if row["kind"] == kind))

    def test_all_eight_actual_control_logs(self):
        paths = []
        for phase in ("fresh-02", "restart-01"):
            phase_paths = list((CONTROL_ROOT / "sdk-01/forge-only-control" / phase).glob("*.log"))
            self.assertEqual(4, len(phase_paths), "Missing actual client control log")
            paths.extend(phase_paths)
            for path in phase_paths:
                with self.subTest(phase=phase, log=path.name):
                    failures, notes = diag.classify(path.read_text(encoding="utf-8"))
                    self.assertEqual(failures, [])
        self.assertEqual(8, len(paths))

    def test_exact_actual_host_sequence(self):
        failures, notes = diag.classify(text_of(self.sequence("host")))
        self.assertEqual(failures, [])
        self.assertEqual(len(notes), 5)

    def test_host_message_mutation_is_rejected(self):
        rows = self.sequence("host")
        rows[0]["message"] += " changed"
        self.assertTrue(diag.classify(text_of(rows))[0])

    def test_host_stack_mutation_is_rejected(self):
        rows = self.sequence("host")
        rows[0]["body"].append("    at basepower.UnrelatedFault.run(UnrelatedFault.java:42)")
        self.assertTrue(diag.classify(text_of(rows))[0])

    def test_host_truncation_is_rejected(self):
        self.assertTrue(diag.classify(text_of(self.sequence("host")[:-1]))[0])

    def test_duplicate_host_is_rejected(self):
        rows = self.sequence("host")
        self.assertTrue(diag.classify(text_of(rows + [rows[0]]))[0])

    def test_partial_netty_sequence_is_rejected(self):
        self.assertTrue(diag.classify(text_of(self.sequence("netty")[:-1]))[0])

    def test_interleaved_netty_sequence_is_rejected(self):
        rows = self.sequence("netty")
        rows.insert(1, dict(thread="Render thread", level="INFO", logger="unknown/", message="unproven gap", body=[]))
        self.assertTrue(diag.classify(text_of(rows))[0])

    def test_extra_mod_error_is_never_suppressed(self):
        text = text_of(self.sequence("host")) + "\n[native66] [Render thread/ERROR] [basepower/]: actual new failure"
        self.assertTrue(any("actual new failure" in row for row in diag.classify(text)[0]))

    def test_server_only_stack_cannot_qualify_client(self):
        server = platform.server_fixture()
        rows = next(row["blocks"] for row in server["sequences"] if row["kind"] == "host")
        self.assertTrue(diag.classify(text_of(rows))[0])

    def test_unsupported_transport_is_not_waived(self):
        text = "[native66] [Render thread/DEBUG] [io.netty.channel.epoll.Epoll/]: Epoll support is not available"
        self.assertTrue(diag.classify(text)[0])

    def test_shared_unsafe_notice_is_not_client_proof_alone(self):
        self.assertTrue(diag.classify(text_of(self.sequence("unsafe")))[0])

    def test_historical_fallback_not_called(self):
        called = []
        failures, notes = diag.classify("java.lang.IllegalStateException: never hide", lambda text: called.append(text) or ([], []))
        self.assertTrue(failures)
        self.assertEqual(called, [])

    def test_fixture_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="native66-client-negative-") as directory:
            path = Path(directory) / "fixture.json"
            path.write_text(json.dumps(dict(engineProtocol="NATIVE65")), encoding="utf-8")
            with patch.object(diag, "CLIENT_FIXTURE", path):
                with self.assertRaisesRegex(AssertionError, "fixture changed"):
                    diag.classify("ordinary text")

    def test_bare_exception_and_error_stay_fatal(self):
        for kind in ("Exception", "Error"):
            self.assertTrue(diag.classify("java.lang." + kind + ": deliberate control")[0])

    def test_actual_zero_mod_native_ui_fresh_and_same_world_reopen_logs(self):
        root = Path("D:/PowerAdvantage/_verification/base-263-port-20261112/client-final-family04-01/client-platform-controls-02")
        for phase in ("fresh-01", "reopen-01"):
            for name in ("stdout.log", "stderr.log", "logs/debug.log", "logs/latest.log"):
                with self.subTest(phase=phase, log=name):
                    self.assertEqual([], diag.classify((root / phase / name).read_text(encoding="utf-8"))[0])

    def test_exact_two_host_variants_preserve_every_field_stack_and_order(self):
        variants = [row["blocks"] for row in self.fixture()["sequences"]
                    if row["kind"] == "host" and row["expectedBlockCount"] == 2]
        self.assertEqual(2, len(variants))
        for original in variants:
            self.assertEqual([], diag.classify(text_of(original))[0])
            for index in range(2):
                for field in ("thread", "level", "logger", "message"):
                    altered = deepcopy(original)
                    altered[index][field] += "_UNPROVEN"
                    with self.subTest(index=index, field=field):
                        self.assertTrue(diag.classify(text_of(altered))[0])
                altered = deepcopy(original)
                altered[index]["body"].append("    at basepower.UnprovenFault.run(UnprovenFault.java:42)")
                self.assertTrue(diag.classify(text_of(altered))[0])
            altered = deepcopy(original)
            altered[0]["body"][0] += " UNPROVEN"
            self.assertTrue(diag.classify(text_of(altered))[0])
            for altered in (original[:1], list(reversed(original)), original + [original[0]]):
                self.assertTrue(diag.classify(text_of(altered))[0])

    def test_no_intermediate_host_count_or_extra_fault_is_accepted(self):
        rows = self.sequence("host")
        for count in (1, 3, 4):
            with self.subTest(count=count):
                self.assertTrue(diag.classify(text_of(rows[:count]))[0])
        two = next(row["blocks"] for row in self.fixture()["sequences"]
                   if row["kind"] == "host" and row["expectedBlockCount"] == 2)
        self.assertTrue(diag.classify(text_of(two) + "\n[native66] [Server thread/ERROR] [basepower/]: new actual failure")[0])

    def test_forged_per_sequence_count_cannot_expand_acceptance(self):
        document = self.fixture()
        document["sequences"][0]["expectedBlockCount"] += 1
        with patch.object(diag, "client_fixture", return_value=document):
            self.assertTrue(diag.classify(text_of(document["sequences"][0]["blocks"]))[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
