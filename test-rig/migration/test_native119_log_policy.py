from pathlib import Path
import tempfile
import tomllib
import unittest

from native119 import log_findings, prepare_fml_defaults


PROBE = "jdk.internal.misc.Unsafe.allocateUninitializedArray(int): unavailable"
DEBUG = "[09Nov2026 15:22:51.470] [main/DEBUG] [io.netty.util.internal.PlatformDependent0/]: "
DETAIL = ("java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent0$7 "
          "(in module io.netty.common) cannot access class jdk.internal.misc.Unsafe (in module java.base)")
FRAME = "\tat io.netty.util.internal.PlatformDependent0$7.run(PlatformDependent0.java:410)"


class Native119LogPolicyTests(unittest.TestCase):
    def test_records_exact_netty_debug_fallback(self):
        errors, diagnostics = log_findings("\n".join((DEBUG + PROBE, DETAIL, FRAME)))
        self.assertEqual([], errors)
        self.assertEqual([dict(probe=PROBE, detail=DETAIL)], diagnostics)

    def test_does_not_discard_same_exception_at_wrong_level_or_after_a_different_probe(self):
        for header in (DEBUG.replace("/DEBUG]", "/ERROR]") + PROBE, DEBUG + "unrelated probe", ""):
            errors, diagnostics = log_findings(header + "\n" + DETAIL)
            self.assertIn(DETAIL, errors)
            self.assertEqual([], diagnostics)

    def test_preserves_machine_failure_after_netty_probe(self):
        error = "[15:22:53] [Server thread/ERROR] [minecraft/MinecraftServer]: Failed machine tick"
        errors, _ = log_findings("\n".join((DEBUG + PROBE, DETAIL, FRAME, error)))
        self.assertEqual([error], errors)

    def test_disposable_first_start_uses_forge_defaults_but_preserves_existing_config(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            prepare_fml_defaults(runtime)
            config = runtime / "config/fml.toml"
            self.assertEqual({"versionCheck": True, "splashscreen": True, "disableOptimizedDFU": True,
                              "defaultConfigPath": "defaultconfigs", "maxThreads": -1},
                             tomllib.loads(config.read_text(encoding="utf-8")))
            original = b'maxThreads = 2\n'
            config.write_bytes(original)
            prepare_fml_defaults(runtime)
            self.assertEqual(original, config.read_bytes())
