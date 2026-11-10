from pathlib import Path
import tempfile
import tomllib
import unittest

from native1201 import log_findings, prepare_fml_defaults


PROBE = "jdk.internal.misc.Unsafe.allocateUninitializedArray(int): unavailable"
DEBUG = "[09Nov2026 15:22:51.470] [main/DEBUG] [io.netty.util.internal.PlatformDependent0/]: "
DETAIL = ("java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent0$7 "
          "(in module io.netty.common) cannot access class jdk.internal.misc.Unsafe (in module java.base)")
FRAME = "\tat io.netty.util.internal.PlatformDependent0$7.run(PlatformDependent0.java:410)"


class Native1201LogPolicyTests(unittest.TestCase):
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

    def test_disposable_first_start_uses_forge_defaults_except_the_external_check(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            prepare_fml_defaults(runtime)
            config = runtime / "config/fml.toml"
            self.assertEqual({"versionCheck": False, "disableOptimizedDFU": True,
                              "defaultConfigPath": "defaultconfigs", "maxThreads": -1,
                              "earlyWindowHeight": 480, "earlyWindowWidth": 854,
                              "earlyWindowControl": True, "earlyWindowFBScale": 1,
                              "earlyWindowProvider": "fmlearlywindow", "earlyWindowMaximized": False,
                              "earlyWindowSkipGLVersions": [], "earlyWindowLogHelpMessage": True,
                              "earlyWindowSquir": False, "earlyWindowShowCPU": False},
                             tomllib.loads(config.read_text(encoding="utf-8")))
    def test_copied_forge_configuration_changes_only_the_external_check(self):
        for original in (b'maxThreads = 2\n', b'versionCheck = true # preserve comment\r\nmaxThreads = 2\r\n'):
            with self.subTest(original=original), tempfile.TemporaryDirectory() as directory:
                runtime = Path(directory)
                config = runtime / "config/fml.toml"
                config.parent.mkdir()
                config.write_bytes(original)
                prepare_fml_defaults(runtime)
                self.assertEqual({"versionCheck": False, "maxThreads": 2},
                                 tomllib.loads(config.read_text(encoding="utf-8")))
                self.assertEqual(original, (runtime / "fml-pre-qualification.toml").read_bytes())
                disabled = config.read_bytes()
                if b"#" in original:
                    self.assertEqual(original.replace(b"true", b"false"), disabled)
                prepare_fml_defaults(runtime)
                self.assertEqual(disabled, config.read_bytes())

    def test_update_check_errors_still_fail_if_they_appear(self):
        error = "com.google.gson.JsonSyntaxException: Expected BEGIN_OBJECT but was NUMBER"
        errors, _ = log_findings("[Forge Version Check/WARN] Failed to process update information\n" + error)
        self.assertEqual([error], errors)
