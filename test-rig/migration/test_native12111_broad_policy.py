"""Native61 broad CLI policy and orchestration, not Minecraft qualification."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import native12111_broad as broad
from test_broad_campaign_status import passed


class Native12111BroadPolicyTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="native61-broad-policy-", dir=broad.native.RIG / "build")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.output = self.root / "new-output"
        self.seed = self.root / "seed"
        self.seed.mkdir()
        (self.seed / "protected-input").write_bytes(b"seed must not change")
        self.profile = self.root / "profile.json"
        self.inventory_file = self.root / "inventory.json"
        self.profile.write_text("{}", encoding="utf-8")
        self.inventory_file.write_text("{}", encoding="utf-8")
        self.java = self.root / "java.exe"
        self.java.write_bytes(b"unit fixture, never launched")
        self.mods = {}
        for name in ("basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"):
            path = self.root / (name + ".jar")
            path.write_bytes(name.encode("ascii"))
            self.mods[name] = ("unit.121111", path, broad.digest(path))
        self.inventory = {"fixture": "orchestration only"}

    def arguments(self, mode=None):
        args = ["--profile", str(self.profile), "--seed", str(self.seed),
                "--inventory", str(self.inventory_file), "--inventory-sha256", "A" * 64,
                "--java", str(self.java), "--output", str(self.output), "--port", "26460"]
        return args if mode is None else args + ["--mode", mode]

    def run_main(self, arguments):
        with patch.object(sys, "argv", [str(Path(broad.__file__)), *arguments]), redirect_stdout(io.StringIO()):
            return broad.main()

    def assert_unsupported(self, mode):
        error = io.StringIO()
        with patch.object(broad, "output_path") as output, \
                patch.object(broad.native, "read_family_target") as inputs, \
                patch.object(broad.native, "java21") as java, \
                patch.object(broad, "run_profile") as launch, redirect_stderr(error):
            with self.assertRaises(SystemExit) as failure:
                self.run_main(self.arguments(mode))
        self.assertEqual(failure.exception.code, 2)
        for mock in (output, inputs, java, launch):
            mock.assert_not_called()
        self.assertIn("BaseMetals", error.getvalue())
        self.assertIn("1.21.11", error.getvalue())
        self.assertIn("packaged-current", error.getvalue())
        return error.getvalue()

    def test_both_and_decoupled_refuse_before_inputs_output_or_launch(self):
        for mode in ("both", "packaged-decoupled"):
            with self.subTest(mode=mode):
                message = self.assert_unsupported(mode)
                self.assertIn(mode, message)
                self.assertFalse(self.output.exists())

    def test_refusal_does_not_relabel_or_overwrite_an_old_failure(self):
        self.output.mkdir()
        receipt = self.output / "native12111-broad-results.json"
        old = b'{"status":"FAIL","profiles":{"packaged-decoupled":{"status":"FAIL"}}}\n'
        receipt.write_bytes(old)
        for mode in ("both", "packaged-decoupled"):
            with self.subTest(mode=mode):
                self.assert_unsupported(mode)
                self.assertEqual(receipt.read_bytes(), old)
                self.assertEqual(list(self.output.iterdir()), [receipt])

    def exercise_supported(self, mode=None, *, current_failure=False, custody_failure=False):
        result = (dict(status="FAIL", profile="packaged-current", error="measured station failed")
                  if current_failure else passed("packaged-current"))
        with patch.object(broad.native, "read_family_target", return_value=(self.seed, self.inventory, self.mods)), \
                patch.object(broad.native, "java21", return_value=(self.java, 'java version "21-unit"')), \
                patch.object(broad, "run_profile", return_value=result) as launch, \
                patch.object(broad.native, "verify_runtime", side_effect=(
                    AssertionError("staged jar changed") if custody_failure else None)) as custody:
            exit_code = self.run_main(self.arguments(mode))
        self.assertEqual(launch.call_count, 1)
        args, kwargs = launch.call_args
        self.assertEqual(args[2:5], (self.output, "packaged-current", 26460))
        self.assertEqual(len(args[5]), 21)
        self.assertEqual(set(kwargs["native118_target"][2]), set(self.mods))
        expected = {path.name: checksum for _, path, checksum in self.mods.values()}
        custody.assert_called_once_with(self.output / "packaged-current", self.inventory, expected)
        document = json.loads((self.output / "native12111-broad-results.json").read_text(encoding="utf-8"))
        self.assertEqual(set(document["profiles"]), {"packaged-current"})
        self.assertEqual(set(document["jars"]), set(self.mods))
        self.assertTrue(document["seedUnchanged"])
        return exit_code, document

    def test_default_dispatches_only_supported_current_with_all_six_pins(self):
        exit_code, document = self.exercise_supported()
        self.assertEqual(exit_code, 0)
        self.assertEqual(document["status"], "PASS")

    def test_explicit_current_remains_supported(self):
        exit_code, document = self.exercise_supported("packaged-current")
        self.assertEqual(exit_code, 0)
        self.assertEqual(document["status"], "PASS")

    def test_supported_station_failure_stays_failure(self):
        exit_code, document = self.exercise_supported(current_failure=True)
        self.assertEqual(exit_code, 1)
        self.assertEqual(document["status"], "FAIL")
        self.assertIn("measured station failed", document["profiles"]["packaged-current"]["error"])

    def test_supported_custody_failure_stays_failure(self):
        exit_code, document = self.exercise_supported(custody_failure=True)
        self.assertEqual(exit_code, 1)
        self.assertEqual(document["status"], "FAIL")
        self.assertIn("staged jar changed", document["profiles"]["packaged-current"]["custodyError"])

    def test_missing_basemetals_refuses_before_output_or_java(self):
        self.mods.pop("basemetals")
        error = io.StringIO()
        with patch.object(broad.native, "read_family_target", return_value=(self.seed, self.inventory, self.mods)), \
                patch.object(broad, "output_path") as output, patch.object(broad.native, "java21") as java, \
                patch.object(broad, "run_profile") as launch, redirect_stderr(error):
            with self.assertRaises(SystemExit) as failure:
                self.run_main(self.arguments())
        self.assertEqual(failure.exception.code, 2)
        output.assert_not_called()
        java.assert_not_called()
        launch.assert_not_called()
        self.assertIn("BaseMetals", error.getvalue())
        self.assertFalse(self.output.exists())

    def test_real_cli_rejects_unsupported_even_without_other_arguments(self):
        for mode in ("both", "packaged-decoupled"):
            with self.subTest(mode=mode):
                process = subprocess.run([sys.executable, "-B", str(Path(broad.__file__).resolve()), "--mode", mode],
                                         capture_output=True, text=True, timeout=20)
                self.assertEqual(process.returncode, 2)
                self.assertIn("BaseMetals", process.stderr)
                self.assertIn("packaged-current", process.stderr)
                self.assertNotIn("Traceback", process.stderr)
                self.assertEqual(process.stdout, "")

    def test_real_cli_refuses_unsupported_before_reading_missing_profile(self):
        self.profile.unlink()
        for mode in ("both", "packaged-decoupled"):
            with self.subTest(mode=mode):
                process = subprocess.run([sys.executable, "-B", str(Path(broad.__file__).resolve()),
                                          *self.arguments(mode)], capture_output=True, text=True, timeout=20)
                self.assertEqual(process.returncode, 2)
                self.assertIn("BaseMetals", process.stderr)
                self.assertNotIn("Traceback", process.stderr)
                self.assertEqual(process.stdout, "")
                self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
