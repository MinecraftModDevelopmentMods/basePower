"""Order-proof contracts and strict template adaptation; no Minecraft process is started."""

import ast
import copy
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from zipfile import ZipFile

from matrix import digest, fingerprint, write_json
import native263 as native
import native263_transformer_order as probe


def woven(reverse=False):
    order = ["basemetals", "basepower"] if reverse else ["basepower", "basemetals"]
    return dict(status="PASS", scope=probe.SCOPE, qualificationAllowed=False,
                minecraft="26.3", forge="66.0.0", dataVersion=5023, upgradeDescriptor=probe.UPGRADE,
                selectedWorldAfterLock=True, selectedWorldTarget=probe.SELECTED, rawReadDescriptor=probe.RAW_READ,
                allHandlerBodiesMatchPinnedProduction=True, nativeReturnsCovered=2, exceptionEdgesChecked=True,
                regionHeads=order[:], regionReturns=[order[:], order[:]], worldHeads=order[:], worldgenHeads=order[:])


class Native263TransformerOrderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def test_only_exact_four_vectors_reverse_and_remain_diagnostic(self):
        result = probe.compare_orders(woven(), woven(True))
        self.assertEqual("PASS", result["status"])
        self.assertFalse(result["qualificationAllowed"])
        self.assertEqual(set(probe.VECTORS), set(result["permutations"]))
        with self.assertRaisesRegex(AssertionError, "did not reverse"):
            probe.compare_orders(woven(), woven())

    def test_stale_61_abi_layout_and_selected_world_proofs_fail_closed(self):
        for key, value in (("minecraft", "1.21.11"), ("forge", "61.1.0"), ("dataVersion", 4671),
                           ("upgradeDescriptor", probe.UPGRADE.replace(";I)", ";)")),
                           ("selectedWorldTarget", "net/minecraft/world/level/storage/LevelStorageSource"),
                           ("selectedWorldAfterLock", False), ("rawReadDescriptor", "old"),
                           ("qualificationAllowed", True), ("scope", "QUALIFIED"), ("status", "FAIL"),
                           ("allHandlerBodiesMatchPinnedProduction", False), ("exceptionEdgesChecked", False),
                           ("nativeReturnsCovered", 1), ("nativeReturnsCovered", 2.0), ("selectedWorldAfterLock", 1)):
            actual = woven(True)
            actual[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(AssertionError):
                probe.compare_orders(woven(), actual)
        for key in ("minecraft", "upgradeDescriptor", "selectedWorldAfterLock"):
            actual = woven()
            del actual[key]
            with self.assertRaises(AssertionError):
                probe.verify_woven(actual)

    def test_missing_duplicate_partial_and_unreversed_callbacks_cannot_pass(self):
        for key in probe.VECTORS:
            for value in ([], ["basepower"], ["basepower", "basepower"], ["basepower", "unknown"]):
                actual = woven(True)
                actual[key] = [value, value] if key == "regionReturns" else value
                with self.subTest(key=key, value=value), self.assertRaises(AssertionError):
                    probe.compare_orders(woven(), actual)
            actual = woven(True)
            actual[key] = copy.deepcopy(woven()[key])
            with self.assertRaises(AssertionError):
                probe.compare_orders(woven(), actual)
        actual = woven(True)
        actual["regionReturns"].pop()
        with self.assertRaises(AssertionError):
            probe.compare_orders(woven(), actual)

    def test_guarded_template_generation_preserves_templates_and_target_contract(self):
        before = fingerprint(probe.TEMPLATES)
        target = self.root / "sources"
        generated = probe.prepare_sources(target)
        self.assertEqual(before, fingerprint(probe.TEMPLATES))
        self.assertEqual(set(probe.TEMPLATE_HASHES), {name.replace("\\", "/") for name in generated})
        audit = (target / "src/zone/moddev/orderprobe/OrderProbeAudit.java").read_text()
        self.assertIn(probe.SELECTED, audit)
        self.assertIn('"(" + TAG + "I" + TAG + "I)" + TAG', audit)
        self.assertIn("SelectedWorldStorageMixin", audit)
        self.assertIn("dominates(selected, lockedAt, site)", audit)
        self.assertIn("dominates(selected, site, rawAt)", audit)
        self.assertIn("nativeSelected.tryCatchBlocks", audit)
        self.assertNotIn("readLevelDataTagFixed", audit)
        self.assertIn("allHandlerBodiesMatchPinnedProduction", audit)
        self.assertIn("native exception-handler coverage changed", audit)
        self.assertIn("native66-order-plugin-", (target / "src/zone/moddev/orderprobe/OrderProbePlugin.java").read_text())
        self.assertIn('@net.minecraftforge.fml.common.Mod("advantageorderprobe")',
                      (target / "src/zone/moddev/orderprobe/OrderProbeMod.java").read_text())
        metadata = (target / "resources/META-INF/mods.toml").read_text()
        self.assertIn('loaderVersion="[65,)"', metadata)
        self.assertIn("Native66", metadata)
        self.assertNotIn("Native64", metadata)
        self.assertIn('[66.0.0]', metadata)
        self.assertIn('[26.3]', metadata)
        config = json.loads((target / "resources/advantageorderprobe.mixins.json").read_text())
        self.assertEqual("JAVA_21", config["compatibilityLevel"])
        self.assertEqual([], config["mixins"])
        self.assertEqual({"defaultRequire": 1}, config["injectors"])
        with self.assertRaises(FileExistsError):
            probe.prepare_sources(target)

    def test_unknown_or_duplicate_template_patterns_fail_before_output_creation(self):
        output = self.root / "bad"
        with patch.dict(probe.TEMPLATE_HASHES, {"resources/META-INF/mods.toml": "0" * 64}), self.assertRaises(AssertionError):
            probe.prepare_sources(output)
        self.assertFalse(output.exists())
        for text in ("not-reviewed", "x x"):
            with self.assertRaises(AssertionError):
                probe.replace_once(text, "x", "y")

    def test_all_21_original_mutations_and_two_positive_baselines_remain_executable(self):
        target = self.root / "sources"
        probe.prepare_sources(target)
        self.assertEqual(10, len(probe.GUARD_CONTROLS))
        self.assertEqual(11, len(probe.WOVEN_CONTROLS))
        for filename, names, baseline in (("run_guard_controls.py", probe.GUARD_CONTROLS, "baseline"),
                                         ("run_woven_controls.py", probe.WOVEN_CONTROLS, "unmodified")):
            text = (target / "test" / filename).read_text()
            tree = ast.parse(text)
            constants = {node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)}
            self.assertTrue(set(names) <= constants)
            self.assertIn(baseline, constants)
            self.assertIn('"--release", "25"', text)
            self.assertIn("java.lang.AssertionError: ORDER_PROBE:", text)
            self.assertIn("subprocess.run", text)
            self.assertNotIn("import native12111", text)
        mutator = (target / "test/WovenNegativeControl.java").read_text()
        self.assertIn(probe.UPGRADE, mutator)
        self.assertIn("StandardOpenOption.CREATE_NEW", mutator)
        self.assertIn("jar/existing-file writes forbidden", mutator)

    def test_sealed_diagnostic_launch_does_not_relax_default_options_policy(self):
        clean = dict(JAVA_TOOL_OPTIONS="", JDK_JAVA_OPTIONS="", _JAVA_OPTIONS="", CLASSPATH="")
        with patch.dict(os.environ, clean):
            manifest, checksum = probe.diagnostic_manifest(self.root, "converted")
            env = native.native_launch_environment(self.root, "converted", diagnostic_environment=manifest,
                                                   diagnostic_environment_sha256=checksum)
            self.assertEqual("-Dmixin.debug.export=true -Dmixin.debug.export.decompile=false", env["JAVA_TOOL_OPTIONS"])
            receipt = native.read_json(self.root / "native66-launch-environment-converted.json")
            self.assertEqual("DIAGNOSTIC_NOT_QUALIFIED", receipt["status"])
            self.assertFalse(receipt["qualificationAllowed"])
        for key in clean:
            with patch.dict(os.environ, {**clean, key: "-javaagent:forbidden.jar"}), self.assertRaises(ValueError):
                native.native_launch_environment()

    def test_diagnostic_manifest_cannot_be_reused_or_modified_without_seal(self):
        clean = dict(JAVA_TOOL_OPTIONS="", JDK_JAVA_OPTIONS="", _JAVA_OPTIONS="", CLASSPATH="")
        with patch.dict(os.environ, clean):
            path, checksum = probe.diagnostic_manifest(self.root, "converted")
            with self.assertRaises(FileExistsError):
                probe.diagnostic_manifest(self.root, "converted")
            with self.assertRaises(ValueError):
                native.native_launch_environment(self.root, "other", diagnostic_environment=path, diagnostic_environment_sha256=checksum)
            document = json.loads(path.read_text())
            document["properties"]["arbitrary.vm.property"] = True
            path.write_text(json.dumps(document))
            with self.assertRaises(AssertionError):
                native.native_launch_environment(self.root, "converted", diagnostic_environment=path, diagnostic_environment_sha256=checksum)
            with self.assertRaises(ValueError):
                native.native_launch_environment(self.root, "converted", diagnostic_environment=path, diagnostic_environment_sha256=digest(path))

    def test_sidecar_staging_does_not_relax_six_mod_reader_or_rewrite_production_jars(self):
        runtime = self.root / "runtime"
        (runtime / "mods").mkdir(parents=True)
        production = runtime / "mods" / "production.jar"
        production.write_bytes(b"immutable-production")
        sidecar = self.root / "sidecar.jar"
        sidecar.write_bytes(b"diagnostic-only")
        variant = dict(jar=str(sidecar), sha256=digest(sidecar))
        inputs = [dict(file=production.name, sha256=digest(production))]
        write_json(runtime / "input-jars.json", inputs)
        expected = {production.name: digest(production)}
        core = SimpleNamespace(stage=Mock(return_value=expected.copy()), verify_runtime=Mock())
        mods = {name: ("v", Path(name), "s") for name in probe.MOD_IDS}
        with patch.object(probe.upgrade, "engine", return_value=core):
            result = probe.instrumented_stage(variant)(runtime, "seed", "inventory", mods, 26510)
        self.assertEqual(set(probe.MOD_IDS), set(mods))
        self.assertEqual({**expected, sidecar.name: digest(sidecar)}, result)
        self.assertEqual(b"immutable-production", production.read_bytes())
        receipt = native.read_json(runtime / "input-jars.json")
        self.assertEqual(inputs, receipt[:-1])
        self.assertIs(False, receipt[-1]["qualificationAllowed"])
        core.verify_runtime.assert_called_once_with(runtime, "inventory", result)
        sidecar.write_bytes(b"changed")
        with patch.object(probe.upgrade, "engine", return_value=core), self.assertRaises(AssertionError):
            probe.instrumented_stage(variant)(runtime, "seed", "inventory", mods, 26510)
        self.assertEqual(1, core.stage.call_count)

    def test_plugin_identity_priority_and_registered_config_guards_are_retained(self):
        pins = dict(mode="power-low", priorities={"basepower.mixins.json": 900, "basemetals.mixins.json": 1100})
        before = {"basepower.mixins.json": 1000, "basemetals.mixins.json": 1000, "other.json": 321}
        proof = dict(status=probe.SCOPE, qualificationAllowed=False, mode="power-low", runtime=str(self.root), pid=123,
                     preparedBefore=False, before=before, after={**before, **pins["priorities"]})
        probe.verify_plugin(proof, pins, self.root, 123)
        for field, value in (("pid", 124), ("preparedBefore", True), ("runtime", "other"), ("qualificationAllowed", True)):
            with self.assertRaises(AssertionError):
                probe.verify_plugin({**proof, field: value}, pins, self.root, 123)
        bad = copy.deepcopy(proof)
        bad["after"]["other.json"] = 322
        with self.assertRaises(AssertionError):
            probe.verify_plugin(bad, pins, self.root, 123)

    def test_control_receipts_require_every_named_rejection_and_build_hash(self):
        build_path = self.root / "build.json"
        write_json(build_path, {})
        output = self.root / "controls"
        output.mkdir()
        receipt = dict(status="PASS", scope=probe.SCOPE, qualificationAllowed=False, buildReceiptSha256=digest(build_path),
                       controls={"baseline": "PASS", **dict.fromkeys(probe.GUARD_CONTROLS, "REJECTED")})
        path = output / "guard-controls.json"
        write_json(path, receipt)
        core = SimpleNamespace(native_launch_environment=lambda: {})
        with patch.object(probe.upgrade, "engine", return_value=core), patch.object(probe.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout="", stderr="")):
            self.assertEqual("PASS", probe.controls(Path("java"), build_path, {"sourceTree": str(self.root)}, output)["status"])
            for key in probe.GUARD_CONTROLS:
                bad = copy.deepcopy(receipt)
                del bad["controls"][key]
                write_json(path, bad)
                with self.subTest(key=key), self.assertRaises(AssertionError):
                    probe.controls(Path("java"), build_path, {"sourceTree": str(self.root)}, output)

    def test_export_archive_preserves_complete_tree_and_clears_only_owned_stale_export_name(self):
        runtime = self.root / "runtime"
        source = runtime / ".mixin.out"
        for name in probe.TARGETS:
            path = source / "class" / (name + ".class")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
        (source / "audit-note.txt").write_text("retain complete export output")
        before = fingerprint(source)
        folder = runtime / "order-evidence/converted"
        folder.mkdir(parents=True)
        self.assertEqual(before, probe.archive_exports(runtime, folder))
        self.assertFalse(source.exists())
        self.assertEqual(before, fingerprint(folder / "mixin-output"))
        self.assertEqual("retain complete export output", (folder / "mixin-output/audit-note.txt").read_text())
        for name in probe.TARGETS:
            self.assertEqual(name.encode(), (folder / "export" / (name + ".class")).read_bytes())
        with self.assertRaises(FileNotFoundError):
            probe.archive_exports(runtime, folder)

    def test_incomplete_export_tree_cannot_be_archived_as_current_proof(self):
        runtime = self.root / "runtime"
        (runtime / ".mixin.out/class").mkdir(parents=True)
        folder = runtime / "order-evidence/failed"
        folder.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "Missing"):
            probe.archive_exports(runtime, folder)
        self.assertTrue((runtime / ".mixin.out/class").is_dir())
        self.assertFalse((folder / "export").exists())

    def test_json_string_custody_paths_are_converted_and_changed_bytes_are_rejected(self):
        path = self.root / "classpath.jar"
        path.write_bytes(b"pinned dependency")
        hashes = json.loads(json.dumps({str(path): digest(path)}))
        probe.verify_files(hashes)
        path.write_bytes(b"changed dependency")
        with self.assertRaisesRegex(AssertionError, "checksum differs"):
            probe.verify_files(hashes)
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            probe.verify_files(hashes)

    def test_sidecar_rejects_missing_entrypoint_or_wrong_mod_identity_before_launch(self):
        files = {"META-INF/mods.toml": f'[[mods]]\nmodId="{probe.MOD_ID}"\nversion="{probe.VERSION}"\n'.encode(),
                 "advantageorderprobe.mixins.json": b"{}", "order-probe-pins.json": b"{}"}
        files.update({"zone/moddev/orderprobe/" + name + ".class": b"unit packaging fixture only"
                      for name in ("OrderProbeMod", "OrderProbeAudit", "OrderProbePlugin", "ProbeGuard")})
        for index, change in enumerate((None, "entrypoint", "identity", "duplicate")):
            payloads = files.copy()
            if change == "entrypoint": del payloads["zone/moddev/orderprobe/OrderProbeMod.class"]
            elif change == "identity": payloads["META-INF/mods.toml"] = b'[[mods]]\nmodId="unknown"\nversion="1"\n'
            path = self.root / (str(index) + ".jar")
            with ZipFile(path, "x") as archive:
                for name, data in payloads.items(): archive.writestr(name, data)
                if change == "duplicate":
                    with self.assertWarns(UserWarning):
                        archive.writestr("zone/moddev/orderprobe/OrderProbeMod.class", b"duplicate fixture")
            if change is None:
                probe.verify_probe_package(path)
            else:
                with self.subTest(change=change), self.assertRaises(AssertionError):
                    probe.verify_probe_package(path)


if __name__ == "__main__":
    unittest.main()
