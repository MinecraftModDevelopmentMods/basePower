"""Fail-closed metadata/launch boundary controls, without launching Minecraft."""

import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native12111 as native
import native12111_order_probe as probe


def woven(reverse=False):
    order = ["basepower", "basemetals"]
    if reverse:
        order.reverse()
    return dict(status="PASS", scope=probe.SCOPE, qualificationAllowed=False,
                regionHeads=order[:], regionReturns=[order[:], order[:]], worldHeads=order[:], worldgenHeads=order[:],
                allHandlerBodiesMatchPinnedProduction=True, nativeReturnsCovered=2, exceptionEdgesChecked=True)


class NativeOrderProbeTest(unittest.TestCase):
    def test_only_actual_reverse_permutations_pass(self):
        self.assertEqual("PASS", probe.compare_orders(woven(), woven(True))["status"])
        first, second = woven(), woven()
        first["mode"], second["mode"] = "power-low", "metals-low"
        with self.assertRaisesRegex(AssertionError, "did not reverse"):
            probe.compare_orders(first, second)

    def test_missing_duplicate_and_partial_order_vectors_fail(self):
        for key in probe.VECTORS:
            for value in (["basepower"], ["basepower", "basepower"], []):
                actual = woven(True)
                actual[key] = [value, value] if key == "regionReturns" else value
                with self.subTest(key=key, value=value), self.assertRaises(AssertionError):
                    probe.compare_orders(woven(), actual)
        actual = woven(True)
        actual["regionReturns"].pop()
        with self.assertRaises(AssertionError):
            probe.compare_orders(woven(), actual)

    def test_unverified_bodies_missing_native_return_and_qualification_relabel_fail(self):
        for key, value in (("allHandlerBodiesMatchPinnedProduction", False), ("nativeReturnsCovered", 1),
                           ("scope", "QUALIFIED"), ("qualificationAllowed", True), ("status", "FAIL"), ("exceptionEdgesChecked", False)):
            actual = woven(True)
            actual[key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                probe.compare_orders(woven(), actual)

    def test_plugin_requires_this_pid_runtime_mode_and_unprepared_state(self):
        pins = dict(mode="power-low", priorities={"basepower.mixins.json": 900, "basemetals.mixins.json": 1100})
        runtime = Path(tempfile.gettempdir()).resolve()
        before = {"basepower.mixins.json": 1000, "basemetals.mixins.json": 1000, "unrelated.json": 321}
        record = dict(status=probe.SCOPE, qualificationAllowed=False, mode="power-low", runtime=str(runtime),
                      pid=123, preparedBefore=False, before=before, after={**before, **pins["priorities"]})
        probe.verify_plugin(record, pins, runtime, 123)
        for key, value in (("pid", 124), ("runtime", "other"), ("mode", "metals-low"),
                           ("preparedBefore", True), ("qualificationAllowed", True), ("status", "PASS")):
            bad = copy.deepcopy(record)
            bad[key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                probe.verify_plugin(bad, pins, runtime, 123)
        for position, key, value in (("before", "basepower.mixins.json", 900),
                                     ("after", "basepower.mixins.json", 1000), ("after", "unrelated.json", 322)):
            bad = copy.deepcopy(record)
            bad[position][key] = value
            with self.subTest(position=position, key=key), self.assertRaises(AssertionError):
                probe.verify_plugin(bad, pins, runtime, 123)

    def test_plugin_missing_config_or_added_config_fails(self):
        runtime = Path(tempfile.gettempdir()).resolve()
        pins = dict(mode="power-low", priorities={"basepower.mixins.json": 900, "basemetals.mixins.json": 1100})
        record = dict(status=probe.SCOPE, qualificationAllowed=False, mode="power-low", runtime=str(runtime),
                      pid=123, preparedBefore=False, before={"basepower.mixins.json": 1000},
                      after={"basepower.mixins.json": 900})
        with self.assertRaises(AssertionError):
            probe.verify_plugin(record, pins, runtime, 123)

    def test_variant_preserves_all_six_original_mod_records(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            sidecar = root / "AdvantageOrderProbe-1.0.0.121111.jar"
            sidecar.write_bytes(b"pinned-test-standin")
            pin = dict(jar=str(sidecar), sha256=hashlib.sha256(sidecar.read_bytes()).hexdigest().upper())
            base = dict(mods=[dict(id=name, file=name + ".jar", sha256="source-" + name) for name in sorted(probe.MOD_IDS)])
            original = copy.deepcopy(base)
            actual = probe.variant_profile(base, pin, root / "profile.json")
            self.assertEqual(original, base)
            self.assertEqual(original["mods"], actual["mods"][:6])
            self.assertEqual(probe.MOD_ID, actual["mods"][-1]["id"])
            sidecar.write_bytes(b"changed")
            with self.assertRaises(AssertionError):
                probe.variant_profile(base, pin, root / "bad.json")
            for mods in (base["mods"][:-1], base["mods"] + [dict(id="unknown")]):
                with self.assertRaises(AssertionError):
                    probe.variant_profile(dict(mods=mods), pin, root / "bad.json")

    def test_existing_diagnostic_api_and_markers_are_not_relaxed(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "", "JDK_JAVA_OPTIONS": "", "_JAVA_OPTIONS": ""}):
            runtime = Path(folder).resolve()
            manifest, checksum = probe.diagnostic_manifest(runtime, "converted")
            environment = native.native_launch_environment(runtime, "converted", diagnostic_environment=manifest,
                                                             diagnostic_environment_sha256=checksum)
            self.assertEqual("-Dmixin.debug.export=true -Dmixin.debug.export.decompile=false", environment["JAVA_TOOL_OPTIONS"])
            receipt = native.read_json(runtime / "native61-launch-environment-converted.json")
            self.assertEqual("DIAGNOSTIC_NOT_QUALIFIED", receipt["status"])
            self.assertIs(False, receipt["qualificationAllowed"])
            document = json.loads(manifest.read_text())
            document["properties"]["advantage.order"] = "other"
            manifest.write_text(json.dumps(document))
            with self.assertRaises(ValueError):
                native.native_launch_environment(runtime, "converted", diagnostic_environment=manifest,
                                                 diagnostic_environment_sha256=native.digest(manifest))

    def test_inherited_options_still_fail(self):
        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "-javaagent:forbidden.jar"}):
            with self.assertRaises(ValueError):
                native.native_launch_environment()


if __name__ == "__main__":
    unittest.main()
