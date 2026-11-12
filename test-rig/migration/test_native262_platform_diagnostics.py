from pathlib import Path
import unittest

import native12111
import native262_platform_diagnostics as native


class Native262PlatformDiagnosticsTest(unittest.TestCase):
    @staticmethod
    def host_text(rows=None):
        rows = rows if rows is not None else native.host_fixture()
        return "\n".join("[time] [" + row["thread"] + "/" + row["level"] + "] [" + row["logger"] + "]: "
                         + row["message"] + "\n" + "\n".join(row["body"]) for row in rows)

    def test_independent_native_host_failure_is_reported_not_silently_removed(self):
        for file_appender in (False, True):
            failures, diagnostics = native.classify(self.host_text(native.host_fixture(file_appender)), native12111.log_findings)
            self.assertFalse(failures)
            self.assertEqual(4, len(diagnostics))
            self.assertTrue(all(row["controlReceiptSha256"] == native.HOST_CONTROL_SHA for row in diagnostics))
            self.assertTrue(all(row["rawBlock"] for row in diagnostics))

    def test_host_matching_never_waives_changed_or_incomplete_stacks(self):
        import copy
        rows = native.host_fixture()
        for slot in range(4):
            for key in ("thread", "level", "logger", "message", "body"):
                changed = copy.deepcopy(rows)
                if key == "body":
                    changed[slot][key].append("\tat zone.moddev.mc.basepower.Machine.tick(Machine.java:10)")
                else:
                    changed[slot][key] += "changed"
                with self.subTest(slot=slot, key=key):
                    self.assertTrue(native.classify(self.host_text(changed), native12111.log_findings)[0])
            with self.subTest(missing=slot):
                self.assertTrue(native.classify(self.host_text(rows[:slot] + rows[slot+1:]), native12111.log_findings)[0])
        for suffix in ("\n[time] [Server thread/ERROR] [basepower/]: machine failed",
                       "\njava.lang.NullPointerException: unrelated failure"):
            self.assertTrue(native.classify(self.host_text() + suffix, native12111.log_findings)[0])

    def test_current_netty_fallback_requires_exact_sequence_and_successful_cleaner(self):
        import copy
        rows = native.netty_fixture()
        failures, diagnostics = native.classify(self.host_text(rows), native12111.log_findings)
        self.assertFalse(failures)
        self.assertEqual(2, len(diagnostics))
        for slot in range(3):
            for key in ("thread", "level", "logger", "message", "body"):
                changed = copy.deepcopy(rows)
                if key == "body":
                    changed[slot][key].append("java.lang.IllegalStateException: real failure")
                else:
                    changed[slot][key] += "changed"
                with self.subTest(slot=slot, key=key):
                    self.assertTrue(native.classify(self.host_text(changed), native12111.log_findings)[0])
            self.assertTrue(native.classify(self.host_text(rows[:slot] + rows[slot+1:]), native12111.log_findings)[0])
        mixed = self.host_text() + "\n" + self.host_text(rows)
        self.assertEqual(6, len(native.classify(mixed, native12111.log_findings)[1]))
        self.assertTrue(native.classify(mixed + "\njava.lang.IllegalArgumentException: real failure", native12111.log_findings)[0])

    def test_predecessor_capability_exceptions_are_not_waived(self):
        fixture = Path(__file__).with_name("fixtures") / "native2612-client-netty-java25.txt"
        text = fixture.read_text(encoding="utf-8")
        strict = native12111.log_findings(text)
        self.assertTrue(strict[0])
        actual = native.classify(text, native12111.log_findings)
        self.assertTrue(set(strict[0]) <= set(actual[0]))
        self.assertEqual([], actual[1])

    def test_new_runtime_failures_are_not_waived(self):
        for text in (
            "[now] [Server thread/ERROR] [basepower/]: machine failure",
            "java.lang.NoClassDefFoundError: MissingMachine",
            "java.lang.UnsupportedOperationException: native access unavailable",
        ):
            with self.subTest(text=text):
                self.assertTrue(native.classify(text, native12111.log_findings)[0])

    def test_classifier_keeps_all_original_text_and_results(self):
        seen = []
        expected = (["unknown failure"], [{"raw": "unaltered"}])
        def fallback(text):
            seen.append(text)
            return expected
        self.assertIs(expected, native.classify("full raw log", fallback))
        self.assertEqual(["full raw log"], seen)


if __name__ == "__main__":
    unittest.main()
