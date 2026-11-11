import copy
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import nbtlib

import endurance
import native12111 as native
import semantic_machine_audit
import test_semantic_machine_audit
from native117_identity import target_id
from native12111_upgrade import comparison, components
from native12111_broad import Adapter
from native12111_endurance import EnduranceHold, Rig, check_hold, measure_batch


class Native1211Test(unittest.TestCase):
    def test_priming_is_atomic_and_always_releases_the_native_tick_gate(self):
        rig = Rig("fresh", Path("runtime"), Path("java"), native.LAUNCHER, {}, {}, {})
        for method in ("start", "seed"):
            calls = []
            rig.command = lambda command, response=None: calls.append(command)
            with patch("native12111_endurance.NativeEnduranceRig." + method,
                       side_effect=RuntimeError("deliberate priming failure")):
                with self.assertRaisesRegex(RuntimeError, "priming failure"):
                    getattr(rig, method)()
            self.assertEqual(["tick freeze", "tick unfreeze"], calls)

    def test_endurance_decoding_is_local_and_preserves_raw_checkpoint(self):
        row = {"pos": [1, 2, 3], "nbt": '{Items:[{Slot:0b,id:"baseelectric:lithium_battery",count:1,'
                                       'components:{"minecraft:custom_data":{energy:5.5f},"minecraft:damage":2}}]}'}
        document = {"snapshot": [row]}
        before = copy.deepcopy(document)
        native_stack = native.parse_endurance_snapshot(document)[(1, 2, 3)]["data"]["Items"][0]
        self.assertEqual(1, native_stack["Count"])
        self.assertEqual({"energy": 5.5}, native_stack["tag"])
        self.assertEqual(2, native_stack["components"]["minecraft:damage"])
        self.assertEqual(before, document)
        self.assertNotIn("Count", endurance.parse_snapshot(document)[(1, 2, 3)]["data"]["Items"][0])
        legacy = {"snapshot": [{"pos": [1, 2, 3], "nbt": '{Items:[{Slot:0b,id:"minecraft:dirt",Count:2b}]}'}]}
        self.assertEqual(2, endurance.parse_snapshot(legacy)[(1, 2, 3)]["data"]["Items"][0]["Count"])
        with self.assertRaisesRegex(AssertionError, "native 1.21.11 stack"):
            native.parse_endurance_snapshot(legacy)

    def test_native_semantic_view_keeps_historical_positive_and_negative_oracles(self):
        fixture = test_semantic_machine_audit.IndependentEnduranceTest()
        fixture.setUp()
        legacy = fixture.documents()
        definitions = fixture.rig.definitions
        self.assertEqual("PASS", semantic_machine_audit.endurance_batch(legacy, definitions)["status"])
        modern = copy.deepcopy(legacy)
        for document in modern.values():
            for row in document["snapshot"]:
                row["block"] = target_id(row["block"])
                data = nbtlib.parse_nbt(row["nbt"])
                for stack in data.get("Items", []):
                    stack["count"] = nbtlib.Int(stack.pop("Count"))
                for name in ("Tank", "InputTank"):
                    if "FluidName" in data.get(name, {}):
                        data[name]["FluidName"] = nbtlib.String(target_id(str(data[name]["FluidName"])))
                row["nbt"] = data.snbt()
        before = copy.deepcopy(modern)
        self.assertEqual("PASS", measure_batch(modern, definitions)["status"])
        self.assertEqual(before, modern)
        row = modern["W-03"]["snapshot"][0]
        data = nbtlib.parse_nbt(row["nbt"])
        self.assertIn("Tank", data)
        del data["Tank"]
        row["nbt"] = data.snbt()
        result = measure_batch(modern, definitions)
        self.assertEqual("FAIL", result["status"])
        self.assertIn("Missing oil tank evidence", result["failures"])

    def test_endurance_custody_does_not_shadow_native_inventory_commands(self):
        rig = Rig("fresh", Path("runtime"), Path("java"), native.LAUNCHER, {}, {"files": {}}, {})
        self.assertTrue(callable(rig.inventory))
        self.assertEqual({"files": {}}, rig.code_inventory)

    def test_endurance_hold_requires_an_explicit_request_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check_hold(root)
            (root / "hold-endurance.request").touch()
            with self.assertRaises(EnduranceHold):
                check_hold(root)

    def test_broad_witness_requires_native_tile_and_item_components(self):
        marker = '"S-05:down-facing"'
        row = {"nbt": Adapter.drill_tag(marker)}
        decoded = Adapter.drill_data(row)
        self.assertEqual("S-05:down-facing", decoded["ForgeData"]["AdvBayWitness"])
        self.assertEqual("S-05:down-facing", decoded["Items"][0]["tag"]["AdvBayStock"])
        self.assertEqual(7, decoded["Items"][0]["Count"])
        self.assertIn("components", decoded["Items"][0])
        with self.assertRaisesRegex(AssertionError, "native tile component"):
            Adapter.drill_data({"nbt": '{ForgeData:{AdvBayWitness:"legacy"}}'})
        with self.assertRaisesRegex(AssertionError, "native 1.21.11 stack"):
            native.compat_tile_data({"nbt": '{Items:[{id:"minecraft:diamond",Count:7b}]}'})

    def test_java_version_gate_rejects_non_21_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            java = Path(directory) / "java"
            java.touch()
            result = SimpleNamespace(stdout="", stderr='openjdk version "17.0.1"')
            with patch.object(native.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(ValueError, "requires Java 21"):
                    native.java21(java)
                result.stderr = 'openjdk version "21.0.12.1"'
                self.assertEqual((java, result.stderr), native.java21(java))

    def test_modern_components_preserve_exact_enchantment_wear_and_custom_payload(self):
        before = {"id": "basesteam:musket", "Count": 1, "Slot": 3, "tag": {
            "Damage": 5, "Enchantments": [{"id": "basesteam:powderless", "lvl": 1}],
            "Witness": "legacy"}}
        after = {"id": "basesteam:musket", "count": 1, "Slot": 3, "components": {
            "minecraft:damage": 5, "minecraft:enchantments": {"basesteam:powderless": 1},
            "minecraft:custom_data": {"Witness": "legacy"}}}
        self.assertEqual(after, components(before))
        witness = {"name": "chest", "x": 1, "y": 5, "z": 1, "actualName": "minecraft:chest",
                   "properties": {}, "nbtBase64": "fixture"}
        with patch("native12111_upgrade.parse_nbt", side_effect=[{"Items": [before]}, {"Items": [after], "components": {}}]):
            self.assertEqual("PASS", comparison([witness], [witness], modern=True)["status"])
        extra = dict(after, components={**after["components"], "minecraft:repair_cost": 10})
        with patch("native12111_upgrade.parse_nbt", side_effect=[{"Items": [before]}, {"Items": [extra], "components": {}}]):
            self.assertEqual("FAIL", comparison([witness], [witness], modern=True)["status"])
        with patch("native12111_upgrade.parse_nbt", side_effect=[{"Items": [before]}, {"Items": [dict(after, count=2)], "components": {}}]):
            self.assertEqual("FAIL", comparison([witness], [witness], modern=True)["status"])
        for observed in ({"Items": [after]}, {"Items": [after], "components": {"other:unexpected": 1}}):
            with patch("native12111_upgrade.parse_nbt", side_effect=[{"Items": [before]}, observed]):
                self.assertEqual("FAIL", comparison([witness], [witness], modern=True)["status"])

    def test_requires_one_successful_shared_crushing_witness(self):
        row = {"id": "basemetals/native-power-crushing", "status": "PASS"}
        native.require_shared_crushing({"sharedChecks": [row]})
        for rows in ([], [row, row], [dict(row, status="FAIL")], [dict(row, id="other")]):
            with self.subTest(rows=rows), self.assertRaises(AssertionError):
                native.require_shared_crushing({"sharedChecks": rows})

    def test_inventory_rejects_modified_launcher_even_with_the_same_shim(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = Path(directory)
            launcher = seed / native.LAUNCHER
            launcher.parent.mkdir(parents=True)
            launcher.write_text("-Djava.net.preferIPv6Addresses=system -jar " + native.SHIM)
            with patch.object(native, "validate_hash"):
                self.assertIn(native.LAUNCHER, native.inspect_seed(seed)["files"])
                launcher.write_text(launcher.read_text() + " --unexpected")
                with self.assertRaisesRegex(AssertionError, "official shim"):
                    native.inspect_seed(seed)

    def test_profile_does_not_carry_historical_artifact_pins_or_drop_assertions(self):
        sha = "A" * 64
        versions = {"basepower": "2.4.15.121111", "advantageworkstest": "1.1.5.121111", "orespawn": "4.1.0.121111"}
        mods = {key: (version, native.RIG / f"{key}-{version}.jar", sha) for key, version in versions.items()}
        fixture = native.read_json(native.RIG / "profiles/native1201/worldgen-legacy-import.json")
        output = native.RIG / "build/test-generated-profile.json"
        args = SimpleNamespace(name="worldgen-legacy-import", output=output, profile=native.BASE_PROFILE,
                               steam=Path("baseSteam-2.2.16.121111.jar"), steam_sha256=sha,
                               electric=Path("baseElectric-2.2.16.121111.jar"), electric_sha256=sha,
                               mineralogy=None, mineralogy_sha256=None)
        with (patch.object(native, "read_mods", return_value=mods), patch.object(native, "validate_hash"),
              patch.object(native, "jar_identity"), patch.object(native, "write_json") as write):
            native.materialize_profile(args)
        _, document = write.call_args.args
        for key in ("firstProfileAssertions", "restartProfileAssertions", "configFixtures", "worldgenExpectations"):
            self.assertEqual(fixture.get(key), document.get(key), key)
        self.assertNotIn("powerDeobfSha256", document)
        self.assertNotIn("sourceCommit", document)
        self.assertEqual("1.21.11", document["minecraftVersion"])
        self.assertEqual(native.EXPLORATORY, document["qualificationStatus"])
        self.assertTrue(all(entry["version"].endswith(".121111") for entry in document["mods"]))


if __name__ == "__main__":
    unittest.main()
