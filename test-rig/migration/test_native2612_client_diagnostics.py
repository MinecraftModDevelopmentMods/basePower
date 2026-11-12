"""Client-only exact stack controls; no dedicated-server classifier or old receipt is changed."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZipInfo

import native2612_client as client
import native2612_client_diagnostics as diagnostics


class ClientCleanerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.observed = (Path(__file__).parent / "fixtures/native2612-client-netty-java25.txt").read_text(encoding="utf-8")

    def test_exact_observed_integrated_status_ping_sequence_is_source_classified(self):
        errors, accepted = diagnostics.classify(self.observed, client.native.previous_log_findings)
        self.assertEqual([], errors)
        self.assertEqual(2, len(accepted))
        self.assertTrue(all(row["classification"] == "SOURCE_PROVEN_NATIVE64_STATUS_PING_JAVA25_CLEANER_FALLBACK" for row in accepted))

    def test_changed_thread_level_body_order_count_fallback_and_unknown_exceptions_are_not_accepted(self):
        changes = (
            self.observed.replace("Server thread/DEBUG", "Render thread/DEBUG", 1),
            self.observed.replace("Server thread/DEBUG", "Server thread/ERROR", 1),
            self.observed.replace("ServerStatusPing.java:156", "ServerStatusPing.java:157", 1),
            self.observed.replace("sun.misc.Unsafe unavailable", "different failure", 1),
            self.observed.replace("CleanerJava25/", "OtherCleaner25/", 1),
            self.observed.replace("cleaner(): available", "cleaner(): unavailable", 1),
            self.observed + self.observed,
            self.observed.replace("\tat java.base/java.lang.Thread.run(Thread.java:1474) [?:?]", "\tat unknown.Frame.run(Frame.java:1)", 1),
            self.observed.replace("[12Nov2026 06:49:14.265]", "[12Nov2026 06:49:14.264] [Server thread/INFO] [unit.other/]: interruption\n[12Nov2026 06:49:14.265]", 1),
            self.observed + "\n[12Nov2026 06:49:14.268] [Server thread/ERROR] [unknown/]: failure\njava.lang.UnsupportedOperationException: new fault\n",
        )
        for text in changes:
            with self.subTest(text=text[:100]):
                errors, _ = diagnostics.classify(text, client.native.previous_log_findings)
                self.assertTrue(errors)

    def test_real_sdk_and_primary_sources_are_exact_and_mutations_cannot_bind(self):
        code = client.inspect_launch(client.native.RIG.parent / "runClient.launch")
        self.assertEqual(7, len(diagnostics.verify_bindings(code)))
        wrong = copy.deepcopy(code)
        next(row for row in wrong["archives"] if Path(row["path"]).name == "netty-common-4.2.7.Final.jar")["sha256"] = "0" * 64
        with self.assertRaisesRegex(AssertionError, "dependency bytes"):
            diagnostics.verify_bindings(wrong)
        with patch.object(diagnostics, "digest", return_value="0" * 64):
            with self.assertRaises(AssertionError):
                diagnostics.verify_bindings(code)

    def test_sdk_zip_metadata_changes_are_distinct_from_payload_mutations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def archive(name, rows, stamp):
                path = root / name
                with ZipFile(path, "w") as output:
                    for key, value in rows:
                        output.writestr(ZipInfo(key, date_time=stamp), value)
                return path
            rows = [("a/A.class", b"class-bytes"), ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")]
            original = archive("original.jar", rows, (2026, 1, 1, 0, 0, 0))
            reordered = archive("repacked.jar", list(reversed(rows)), (2026, 11, 12, 0, 0, 0))
            expected = diagnostics.sdk_payload_sha(original)
            with patch.object(diagnostics, "SDK_PAYLOAD_SHA", expected):
                diagnostics.verify_sdk_payload(reordered)
                for label, changed in (("changed", [("a/A.class", b"mutated"), rows[1]]),
                                       ("missing", rows[1:]), ("added", rows + [("other.class", b"extra")]),
                                       ("renamed", [("a/B.class", rows[0][1]), rows[1]])):
                    with self.subTest(label=label), self.assertRaises(AssertionError):
                        diagnostics.verify_sdk_payload(archive(label + ".jar", changed, (2026, 1, 1, 0, 0, 0)))
                with self.assertWarns(UserWarning):
                    duplicate = archive("duplicate.jar", rows + [rows[0]], (2026, 1, 1, 0, 0, 0))
                with self.assertRaisesRegex(AssertionError, "duplicate SDK"):
                    diagnostics.verify_sdk_payload(duplicate)


if __name__ == "__main__":
    unittest.main()
