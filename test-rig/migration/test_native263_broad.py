"""Adapter rejection and orchestration controls; these tests launch no Minecraft."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import native263_broad as broad
from test_broad_campaign_status import passed


class Native263BroadTest(unittest.TestCase):
    def setUp(self):
        (broad.native.RIG / "build").mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="native26-broad-", dir=broad.native.RIG / "build")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.output = self.root / "result"
        self.seed = self.root / "seed"
        self.seed.mkdir()
        (self.seed / "protected").write_bytes(b"untouched seed")
        self.profile = self.root / "profile.json"
        self.inventory = self.root / "inventory.json"
        self.java = self.root / "java.exe"
        for path in (self.profile, self.inventory, self.java):
            path.write_bytes(b"unit test only, never launched")
        self.mods = {}
        for name in broad.native.VERSIONS:
            path = self.root / (name + ".jar")
            path.write_bytes(name.encode("ascii"))
            self.mods[name] = ("unit.2601021", path, broad.digest(path))

    def arguments(self):
        return ["--profile", str(self.profile), "--profile-sha256", broad.digest(self.profile),
                "--seed", str(self.seed), "--inventory", str(self.inventory),
                "--inventory-sha256", broad.digest(self.inventory), "--java", str(self.java),
                "--output", str(self.output), "--port", "26600"]

    def exercise(self, *, failed=False, changed_seed=False, changed_input=False, custody_failed=False):
        def launch(*args, **kwargs):
            self.assertEqual(args[2:5], (self.output, "packaged-current", 26600))
            self.assertEqual(21, len(args[5]))
            self.assertEqual(set(self.mods), set(kwargs["native118_target"][2]))
            self.assertIs(broad.Adapter, kwargs["native_adapter"])
            if changed_seed:
                (self.seed / "protected").write_bytes(b"changed")
            if changed_input:
                self.profile.write_bytes(b"changed")
            return dict(status="FAIL", profile="packaged-current", error="finite assertion failed") if failed else passed("packaged-current")
        with patch.object(broad.native, "read_family_target", return_value=(self.seed, {}, self.mods)), \
                patch.object(broad.native, "java25", return_value=(self.java, "Java25 unit fixture")), \
                patch.object(broad, "oracle_closure", return_value={}), \
                patch.object(broad, "run_profile", side_effect=launch) as run, \
                patch.object(broad.native, "verify_runtime", side_effect=(AssertionError("jar changed") if custody_failed else None)), \
                redirect_stdout(io.StringIO()):
            code = broad.main(self.arguments())
        self.assertEqual(1, run.call_count)
        result = json.loads((self.output / broad.RECEIPT).read_text(encoding="utf-8"))
        self.assertEqual({"packaged-current"}, set(result["profiles"]))
        self.assertEqual(set(self.mods), set(result["jars"]))
        return code, result

    def test_native_sdk_and_existing_oracles_are_not_historical_dispatch(self):
        self.assertEqual(("26.3", "66.0.0"), (broad.Adapter.MINECRAFT, broad.Adapter.FORGE))
        self.assertIs(broad.native.Server, broad.Adapter.Server)
        self.assertIs(broad.previous.Adapter.drill_tag, broad.Adapter.drill_tag)
        self.assertIs(broad.native, broad.Adapter.drill_tickets.__globals__["native"])

    def test_native_tile_component_witness_and_stock_are_projected_without_dropping_components(self):
        row = {"nbt": '{components:{"minecraft:custom_data":{AdvBayWitness:"S-05:north"}},'
                      'Items:[{Slot:0b,id:"minecraft:diamond",count:7,'
                      'components:{"minecraft:custom_data":{AdvBayStock:"S-05:north",Unknown:[2,1]}}}]}' }
        data = broad.Adapter.drill_data(row)
        self.assertEqual({"AdvBayWitness": "S-05:north"}, data["ForgeData"])
        self.assertEqual(7, data["Items"][0]["Count"])
        custom = data["Items"][0]["components"]["minecraft:custom_data"]
        self.assertEqual("S-05:north", custom["AdvBayStock"])
        self.assertEqual([2, 1], list(custom["Unknown"]))
        self.assertEqual(custom, data["Items"][0]["tag"])

    def test_legacy_witness_and_incorrect_native_item_types_are_rejected(self):
        for nbt in ('{ForgeData:{AdvBayWitness:"stale"}}',
                    '{Items:[{id:"minecraft:diamond",count:7.0f}]}',
                    '{Items:[{id:"minecraft:diamond",count:7,Count:7b}]}'):
            with self.subTest(nbt=nbt), self.assertRaises(AssertionError):
                broad.Adapter.drill_data({"nbt": nbt})

    def test_staging_passes_only_the_exact_native_target_and_launcher(self):
        entries = {key: dict(version=value[0], path=value[1], sha256=value[2]) for key, value in self.mods.items()}
        with patch.object(broad.native, "stage") as stage:
            self.assertEqual(broad.native.LAUNCHER,
                             broad.Adapter.stage(self.output, (self.seed, {}, entries), 26600))
        stage.assert_called_once_with(self.output, self.seed, {}, self.mods, 26600)

    def test_supported_full_family_dispatches_all_twenty_one_stations(self):
        code, result = self.exercise()
        self.assertEqual(0, code)
        self.assertEqual("PASS", result["status"])
        self.assertTrue(result["seedUnchanged"] and result["inputsUnchanged"])

    def test_measured_failure_cannot_become_pass(self):
        code, result = self.exercise(failed=True)
        self.assertEqual(1, code)
        self.assertEqual("FAIL", result["status"])
        self.assertIn("finite assertion failed", result["profiles"]["packaged-current"]["error"])

    def test_staged_jar_change_cannot_become_pass(self):
        code, result = self.exercise(custody_failed=True)
        self.assertEqual(1, code)
        self.assertEqual("FAIL", result["status"])
        self.assertIn("jar changed", result["profiles"]["packaged-current"]["custodyError"])

    def test_protected_seed_change_cannot_become_pass(self):
        code, result = self.exercise(changed_seed=True)
        self.assertEqual(1, code)
        self.assertFalse(result["seedUnchanged"])

    def test_profile_change_cannot_become_pass(self):
        code, result = self.exercise(changed_input=True)
        self.assertEqual(1, code)
        self.assertFalse(result["inputsUnchanged"])

    def test_missing_mandatory_mod_refuses_before_java_or_output(self):
        del self.mods["basemetals"]
        with patch.object(broad.native, "read_family_target", return_value=(self.seed, {}, self.mods)), \
                patch.object(broad.native, "java25") as java, patch.object(broad, "run_profile") as launch, \
                redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as failure:
                broad.main(self.arguments())
        self.assertEqual(2, failure.exception.code)
        java.assert_not_called()
        launch.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_incorrect_profile_hash_refuses_before_family_or_launch(self):
        args = self.arguments()
        args[args.index("--profile-sha256") + 1] = "0" * 64
        with patch.object(broad.native, "read_family_target") as read, patch.object(broad, "run_profile") as launch:
            with self.assertRaises(AssertionError):
                broad.main(args)
        read.assert_not_called()
        launch.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_unsupported_modes_do_not_touch_an_existing_failure(self):
        self.output.mkdir()
        receipt = self.output / broad.RECEIPT
        old = b'{"status":"FAIL","reason":"preserve this"}\n'
        receipt.write_bytes(old)
        for mode in ("both", "packaged-decoupled"):
            with patch.object(broad.native, "read_family_target") as read, redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    broad.main(self.arguments() + ["--mode", mode])
                self.assertEqual(2, failure.exception.code)
                read.assert_not_called()
                self.assertEqual(old, receipt.read_bytes())

    def test_real_cli_refuses_unsupported_mode_before_missing_paths(self):
        process = subprocess.run([sys.executable, "-B", str(Path(broad.__file__).resolve()),
                                  "--mode", "packaged-decoupled"], capture_output=True, text=True, timeout=20)
        self.assertEqual(2, process.returncode)
        self.assertIn("BaseMetals is mandatory", process.stderr)
        self.assertIn("26.3", process.stderr)
        self.assertNotIn("Traceback", process.stderr)


if __name__ == "__main__":
    unittest.main()
