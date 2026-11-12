from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZipInfo

import native12111
import native262_client_diagnostics as diagnostics


class Native262ClientDiagnosticsTest(unittest.TestCase):
    def test_predecessor_status_ping_exceptions_are_not_waived(self):
        text = (Path(__file__).parent / "fixtures/native2612-client-netty-java25.txt").read_text(encoding="utf-8")
        strict = native12111.log_findings(text)
        self.assertTrue(strict[0])
        actual = diagnostics.classify(text, native12111.log_findings)
        self.assertTrue(set(strict[0]) <= set(actual[0]))
        self.assertEqual([], actual[1])

    @staticmethod
    def text(rows):
        return "\n".join("[time] [" + row["thread"] + "/" + row["level"] + "] [" + row["logger"] + "]: "
                         + row["message"] + "\n" + "\n".join(row["body"]) for row in rows)

    def test_exact_native_sequences_retain_diagnostics_but_no_other_failures(self):
        import copy
        for name, rows in diagnostics.variants().items():
            failures, notes = diagnostics.classify(self.text(rows), native12111.log_findings)
            self.assertFalse(failures)
            self.assertEqual(len(rows), len(notes))
            for slot in range(len(rows)):
                for field in ("thread", "level", "logger", "message", "body"):
                    changed = copy.deepcopy(rows)
                    if field == "body":
                        changed[slot][field].append("\tat zone.moddev.mc.basepower.Machine.tick(Machine.java:10)")
                    else:
                        changed[slot][field] += "changed"
                    with self.subTest(appender=name, slot=slot, field=field):
                        self.assertTrue(diagnostics.classify(self.text(changed), native12111.log_findings)[0])
                self.assertTrue(diagnostics.classify(self.text(rows[:slot] + rows[slot+1:]), native12111.log_findings)[0])
            self.assertTrue(diagnostics.classify(self.text(rows) + "\njava.lang.IllegalStateException: unrelated", native12111.log_findings)[0])

    def test_missing_duplicate_and_unknown_dependencies_fail(self):
        for rows in ([], [{"path": "unknown.jar", "sha256": "0" * 64}],
                     [{"path": "netty-common-4.2.15.Final.jar", "sha256": "0" * 64}] * 4):
            with self.subTest(rows=rows), self.assertRaises(AssertionError):
                diagnostics.verify_bindings({"archives": rows})

    def test_sdk_metadata_can_change_but_payload_mutations_cannot(self):
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
            with patch.object(diagnostics, "SDK_PAYLOAD_SHA", diagnostics.sdk_payload_sha(original)):
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
