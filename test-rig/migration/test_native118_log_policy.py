import unittest

from native118 import log_findings


DEBUG = "[09Nov2026 12:44:32.901] [worker/DEBUG] [io.netty.util.internal.PlatformDependent0/]: "
PROBE = "direct buffer constructor: unavailable"
DETAIL = "java.lang.UnsupportedOperationException: Reflective setAccessible(true) disabled"
FRAME = "\tat io.netty.util.internal.ReflectionUtil.trySetAccessible(ReflectionUtil.java:31)"


class NativeLogPolicyTests(unittest.TestCase):
    def test_known_capability_fallback_is_recorded_without_a_runtime_failure(self):
        failures, diagnostics = log_findings("\n".join((DEBUG + PROBE, DETAIL, FRAME)))
        self.assertEqual([], failures)
        self.assertEqual([dict(probe=PROBE, detail=DETAIL)], diagnostics)

    def test_same_exception_without_the_exact_debug_probe_is_a_failure(self):
        for header in ("", DEBUG + "different probe", DEBUG.replace("/DEBUG]", "/ERROR]") + PROBE):
            failures, diagnostics = log_findings(header + "\n" + DETAIL)
            self.assertIn(DETAIL, failures)
            self.assertEqual([], diagnostics)

    def test_gameplay_error_after_a_platform_probe_is_not_discarded(self):
        error = "[12:44:33] [Server thread/ERROR] [minecraft/MinecraftServer]: Failed machine tick"
        failures, _ = log_findings("\n".join((DEBUG + PROBE, DETAIL, FRAME, error)))
        self.assertEqual([error], failures)
