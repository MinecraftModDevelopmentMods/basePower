"""Native persistence and component views must retain the historical strict oracles."""

import base64
import copy
import gzip
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nbtlib

import endurance
import native1211neo as native
import semantic_machine_audit
import test_semantic_machine_audit
from native117_identity import target_id
from native1211neo_broad import Adapter
from native1211neo_endurance import EnduranceHold, Rig, check_hold, measure_batch
from native1211neo_upgrade import comparison, components


class RuntimeViews(unittest.TestCase):
    def test_priming_is_atomic_and_releases_the_native_tick_gate_on_failure(self):
        rig = Rig("fresh", Path("runtime"), Path("java"), native.LAUNCHER, {}, {}, {})
        for method in ("start", "seed"):
            calls = []
            rig.command = lambda command, response=None: calls.append(command)
            with patch("native1211neo_endurance.NativeEnduranceRig." + method,
                       side_effect=RuntimeError("deliberate priming failure")):
                with self.assertRaisesRegex(RuntimeError, "priming failure"):
                    getattr(rig, method)()
            self.assertEqual(["tick freeze", "tick unfreeze"], calls)

    def test_endurance_decoding_is_local_and_keeps_raw_native_persistent_data(self):
        row = {"pos": [1, 2, 3], "nbt": '{NeoForgeData:{Witness:"native",counter:17L},'
               'Items:[{Slot:0b,id:"baseelectric:lithium_battery",count:1,'
               'components:{"minecraft:custom_data":{energy:5.5f,NeoForgeData:{opaque:19}},"minecraft:damage":2}}]}'}
        document = {"snapshot": [row]}
        before = copy.deepcopy(document)
        view = native.parse_endurance_snapshot(document)[(1, 2, 3)]["data"]
        self.assertEqual({"Witness": "native", "counter": 17}, view["ForgeData"])
        stack = view["Items"][0]
        self.assertEqual(1, stack["Count"])
        self.assertEqual({"energy": 5.5, "NeoForgeData": {"opaque": 19}}, stack["tag"])
        self.assertEqual(2, stack["components"]["minecraft:damage"])
        self.assertEqual(before, document)
        self.assertNotIn("Count", endurance.parse_snapshot(document)[(1, 2, 3)]["data"]["Items"][0])

    def test_binary_native_checkpoint_keeps_nbt_types_and_the_original_witness(self):
        data = nbtlib.parse_nbt('{NeoForgeData:{owner:[I;1,2,3,4],energy:5.5f},Items:[{'
                                'id:"minecraft:diamond",count:2,components:{"minecraft:custom_data":{marker:7L}}}]}')
        stream = io.BytesIO()
        nbtlib.File(data).write(stream)
        row = {"nbtBase64": base64.b64encode(gzip.compress(stream.getvalue())).decode("ascii")}
        original = copy.deepcopy(row)
        projected = native.compat_tile_nbt(row)
        self.assertIsInstance(projected["ForgeData"]["owner"], nbtlib.IntArray)
        self.assertIsInstance(projected["ForgeData"]["energy"], nbtlib.Float)
        self.assertIsInstance(projected["Items"][0]["tag"]["marker"], nbtlib.Long)
        self.assertEqual(original, row)

    def test_ambiguous_persistence_and_legacy_inventory_do_not_become_native_evidence(self):
        for snbt in ('{NeoForgeData:{Witness:"native"},ForgeData:{Witness:"legacy"}}',
                     '{Items:[{id:"minecraft:dirt",Count:2b}]}',
                     '{Items:[{id:"minecraft:dirt",count:2,Count:2b}]}',
                     '{Items:[{id:"minecraft:dirt",count:2,tag:{}}]}'):
            with self.subTest(snbt=snbt), self.assertRaises(AssertionError):
                native.compat_tile_nbt({"nbt": snbt})

    def test_native_semantic_view_retains_the_positive_and_negative_conservation_oracles(self):
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
                if "ForgeData" in data:
                    data["NeoForgeData"] = data.pop("ForgeData")
                for stack in data.get("Items", []):
                    stack["count"] = nbtlib.Int(stack.pop("Count"))
                    if "tag" in stack:
                        stack["components"] = nbtlib.Compound({"minecraft:custom_data": stack.pop("tag")})
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

    def test_endurance_code_custody_does_not_shadow_inventory_commands(self):
        rig = Rig("fresh", Path("runtime"), Path("java"), native.LAUNCHER, {}, {"files": {}}, {})
        self.assertTrue(callable(rig.inventory))
        self.assertEqual({"files": {}}, rig.code_inventory)

    def test_endurance_hold_requires_an_explicit_operator_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check_hold(root)
            (root / "hold-endurance.request").touch()
            with self.assertRaises(EnduranceHold):
                check_hold(root)

    def test_broad_witness_requires_native_tile_components_and_keeps_native_stock(self):
        row = {"nbt": Adapter.drill_tag('"S-05:down-facing"')}
        before = copy.deepcopy(row)
        decoded = Adapter.drill_data(row)
        self.assertEqual("S-05:down-facing", decoded["ForgeData"]["AdvBayWitness"])
        self.assertEqual("S-05:down-facing", decoded["Items"][0]["tag"]["AdvBayStock"])
        self.assertEqual(7, decoded["Items"][0]["Count"])
        self.assertIn("components", decoded["Items"][0])
        self.assertEqual(before, row)
        for old in ('{ForgeData:{AdvBayWitness:"legacy"}}', '{NeoForgeData:{AdvBayWitness:"wrong-source"}}'):
            with self.subTest(old=old), self.assertRaisesRegex(AssertionError, "native tile component"):
                Adapter.drill_data({"nbt": old})

    def test_upgrade_translation_preserves_exact_components_and_native_persistent_data(self):
        before = {"Items": [{"id": "basesteam:musket", "Count": 1, "Slot": 3, "tag": {
            "Damage": 5, "Enchantments": [{"id": "basesteam:powderless", "lvl": 1}], "Witness": "legacy"}}],
            "ForgeData": {"parent": [1, 2, 3], "opaque": {"counter": 17}}}
        after = {"Items": [{"id": "basesteam:musket", "count": 1, "Slot": 3, "components": {
            "minecraft:damage": 5, "minecraft:enchantments": {"levels": {"basesteam:powderless": 1}},
            "minecraft:custom_data": {"Witness": "legacy"}}}], "NeoForgeData": before["ForgeData"]}
        self.assertEqual(after["Items"], components(before["Items"]))
        witness = {"name": "chest", "x": 1, "y": 5, "z": 1, "actualName": "minecraft:chest",
                   "properties": {}, "nbtBase64": "fixture"}
        untouched = copy.deepcopy((before, after))
        with patch("native1211neo_upgrade.parse_nbt", side_effect=[before, after]):
            self.assertEqual("PASS", comparison([witness], [witness], modern=True)["status"])
        self.assertEqual(untouched, (before, after))
        variants = []
        for field, value in (("count", 2), ("components", {})):
            changed = copy.deepcopy(after)
            changed["Items"][0][field] = value
            variants.append(changed)
        changed = copy.deepcopy(after)
        changed["Items"][0]["components"]["minecraft:repair_cost"] = 10
        variants.append(changed)
        changed = copy.deepcopy(after)
        changed["NeoForgeData"]["opaque"]["counter"] = 18
        variants.append(changed)
        for observed in variants:
            with self.subTest(observed=observed), patch("native1211neo_upgrade.parse_nbt", side_effect=[before, observed]):
                self.assertEqual("FAIL", comparison([witness], [witness], modern=True)["status"])

    def test_upgrade_rejects_ambiguous_source_persistent_data(self):
        witness = dict(name="tank", x=1, y=2, z=3, actualName="basepower:crude_oil_tank",
                       properties={}, nbtBase64="fixture")
        with patch("native1211neo_upgrade.parse_nbt", side_effect=[{"ForgeData": {}, "NeoForgeData": {}}, {}]):
            with self.assertRaises(AssertionError):
                comparison([witness], [witness], modern=True)

    def test_requires_exactly_one_successful_shared_crushing_witness(self):
        row = {"id": "basemetals/native-power-crushing", "status": "PASS"}
        native.require_shared_crushing({"sharedChecks": [row]})
        for rows in ([], [row, row], [dict(row, status="FAIL")], [dict(row, id="other")]):
            with self.subTest(rows=rows), self.assertRaises(AssertionError):
                native.require_shared_crushing({"sharedChecks": rows})


if __name__ == "__main__":
    unittest.main()
