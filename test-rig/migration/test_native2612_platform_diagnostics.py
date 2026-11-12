import os
from pathlib import Path
import unittest

import native12111
import native2612_platform_diagnostics as native


REFERENCE = Path(os.environ.get("NATIVE2612_NETTY_REFERENCE",
    "D:/PowerAdvantage/PA2612/PowerAdvantage/test-rig/build/native2612-migration-candidate01-from12111-01/runtime/debug-converted.log"))
SOURCES = Path("D:/GradleCaches/minecraft-mods-shared/caches/modules-2/files-2.1/io.netty/netty-common/4.2.7.Final/ca57754b709612b8a2a9a270129d2ed1c2cd351/netty-common-4.2.7.Final-sources.jar")


def captured():
    lines = REFERENCE.read_text(encoding="utf-8").splitlines()
    first = next(index for index, line in enumerate(lines) if "[io.netty.util.internal.CleanerJava9/]:" in line)
    last = next(index for index, line in enumerate(lines) if "[io.netty.util.internal.CleanerJava25/]:" in line)
    return "\n".join(lines[first:last + 1])


class Native2612CleanerDiagnosticsTest(unittest.TestCase):
    def check(self, text):
        return native.classify(text, native12111.log_findings)

    def test_actual_primary_sources_are_hash_bound(self):
        self.assertEqual(native.SOURCE_SHA, native.verify_sources(SOURCES))

    def test_actual_native_log_requires_both_failures_then_working_native25(self):
        text = captured()
        self.assertTrue(native12111.log_findings(text)[0], "Baseline strict classifier must reject the new exceptions")
        failures, diagnostics = self.check(text)
        self.assertEqual([], failures)
        self.assertEqual(2, len(diagnostics))
        self.assertTrue(all(row["level"] == "DEBUG" and row["rawBlock"] for row in diagnostics))
        self.assertEqual({native.COMMON_JAR_SHA}, {row["nativeDependencySha256"] for row in diagnostics})

    def test_raw_full_retained_debug_log_remains_unchanged(self):
        original = REFERENCE.read_bytes()
        failures, diagnostics = self.check(original.decode("utf-8"))
        self.assertEqual([], failures)
        self.assertEqual(2, sum(row.get("classification") == "SOURCE_PROVEN_NATIVE_NETTY_JAVA25_CLEANER_FALLBACK"
                                for row in diagnostics))
        self.assertEqual(original, REFERENCE.read_bytes())

    def test_error_level_never_classifies_as_a_capability_probe(self):
        for logger in ("CleanerJava9", "CleanerJava24Linker", "CleanerJava25"):
            text = captured().splitlines()
            text = [line.replace("/DEBUG]", "/ERROR]") if "internal." + logger + "/]" in line else line for line in text]
            self.assertTrue(self.check("\n".join(text))[0])

    def test_missing_or_changed_available_fallback_fails(self):
        text = captured()
        for changed in ("\n".join(text.splitlines()[:-1]), text.replace("cleaner(): available", "cleaner(): unavailable"),
                        text.replace("CleanerJava25/", "UnexpectedCleaner/")):
            self.assertTrue(self.check(changed)[0])

    def test_changed_exception_type_message_or_module_fails(self):
        text = captured()
        for changed in (text.replace("UnsupportedOperationException", "IllegalStateException", 1),
                        text.replace("sun.misc.Unsafe unavailable", "actual runtime defect"),
                        text.replace("io.netty.common module.", "other module.")):
            self.assertTrue(self.check(changed)[0])

    def test_unknown_removed_added_or_mutated_frames_fail(self):
        text = captured()
        for changed in (text.replace("ClassInitializerUtil.tryLoadClasses", "UnknownBootstrap.run", 1),
                        text.replace("\tat java.base/java.lang.Thread.run(Thread.java:1474)", "", 1),
                        text.replace("CleanerJava9.java:70", "CleanerJava9.java:71"),
                        text.replace("@4.2.7.Final", "@4.2.15.Final", 1),
                        text.replace("\tat SECURE-BOOTSTRAP/io.netty.common", "\tat zone.moddev.mc.basepower.Broken.run(Broken.java:1)\n\tat SECURE-BOOTSTRAP/io.netty.common", 1)):
            self.assertTrue(self.check(changed)[0])

    def test_wrong_count_order_thread_and_interposed_event_fail(self):
        text = captured()
        blocks = []
        for line in text.splitlines():
            if native.HEADER.fullmatch(line):
                blocks.append([])
            blocks[-1].append(line)
        variants = ("\n".join(blocks[0] + blocks[0] + blocks[1] + blocks[2]),
                    "\n".join(blocks[1] + blocks[0] + blocks[2]),
                    "\n".join(blocks[1] + blocks[2]), "\n".join(blocks[2]),
                    text.replace("Server thread/DEBUG", "mod-worker/DEBUG", 1),
                    "\n".join(blocks[0] + ["[now] [Server thread/DEBUG] [unknown/]: interposed"] + blocks[1] + blocks[2]))
        for changed in variants:
            self.assertTrue(self.check(changed)[0])

    def test_unrelated_failures_outside_exact_sequence_remain_failures(self):
        for message in ("java.lang.UnsupportedOperationException: actual machine defect",
                        "[now] [Server thread/ERROR] [basepower/]: runtime failure",
                        "java.lang.NoClassDefFoundError: InventoryLocks$Fields"):
            self.assertTrue(self.check(captured() + "\n" + message)[0])


if __name__ == "__main__":
    unittest.main()
