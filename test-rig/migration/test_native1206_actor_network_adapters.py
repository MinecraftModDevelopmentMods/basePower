"""Fail-closed controls for the Forge 50 lifecycle format adapters."""

import copy
from pathlib import Path
import tempfile
import unittest

import nbtlib

import actors
import native1206_actors as actor_driver
import native1206_networks as network_driver
from matrix import WORLD


class ActorAdapters(unittest.TestCase):
    def test_preserves_enchantments_damage_slot_count_and_opaque_custom_data(self):
        item = {"id": "basesteam:musket", "Count": 2, "Slot": -106, "tag": {
            "Damage": 5, "Enchantments": [{"id": "basesteam:recoil", "lvl": 3}],
            "StoredEnchantments": [{"id": "basesteam:powderless", "lvl": 1}],
            "Nested": {"id": "minecraft:dirt", "Count": 17},
        }}
        original = copy.deepcopy(item)
        self.assertEqual({
            "id": "basesteam:musket", "count": 2, "Slot": -106, "components": {
                "minecraft:damage": 5,
                "minecraft:enchantments": {"levels": {"basesteam:recoil": 3}},
                "minecraft:stored_enchantments": {"levels": {"basesteam:powderless": 1}},
                "minecraft:custom_data": {"Nested": {"id": "minecraft:dirt", "Count": 17}},
            },
        }, actor_driver.expected_components(item))
        self.assertEqual(original, item)

    def test_rejects_missing_or_added_native_stack_components(self):
        expected = {"file": {"Inventory": [{"id": "minecraft:dirt", "Slot": 3, "count": 1,
                                             "components": {"minecraft:custom_data": {"Note": "original"}}}]},
                    "entities": {}}
        self.assertEqual("PASS", actor_driver.verdict(actors.compare, expected, copy.deepcopy(expected))["status"])
        for components in ({}, {"minecraft:custom_data": {"Note": "original"}, "minecraft:damage": 7}):
            actual = copy.deepcopy(expected)
            actual["file"]["Inventory"][0]["components"] = components
            result = actor_driver.verdict(actors.compare, expected, actual)
            self.assertEqual("FAIL", result["status"])
            self.assertEqual("FAIL", result["player_status"])
            self.assertTrue(any(row["path"].endswith(".exact-item") for row in result["differences"]))

    def test_rejects_ambiguous_native_and_historical_stack(self):
        with self.assertRaises(AssertionError):
            actor_driver.expected_components({"id": "minecraft:dirt", "Count": 2, "count": 2})

    def test_dimension_projection_preserves_actual_leash_armor_and_jump_strength(self):
        armor = {"id": "minecraft:iron_horse_armor", "count": 1,
                 "components": {"minecraft:custom_data": {"Note": "witness"}}}
        horse = {"id": "minecraft:horse", "leash": [168, 100, 8], "body_armor_item": armor,
                 "Attributes": [{"Name": "minecraft:generic.jump_strength", "Base": 0.7}]}
        result = actor_driver.dimension_entity_view(horse)
        self.assertEqual({"X": 168, "Y": 100, "Z": 8}, result["Leash"])
        self.assertEqual(armor, result["ArmorItem"])
        self.assertEqual([{"Name": "horse.jumpStrength", "Base": 0.7}], result["Attributes"])

    def test_ambiguous_or_malformed_native_leash_is_not_normalized(self):
        for tag in ({"leash": [168, 100, True]}, {"leash": [168, 100, 8], "Leash": {"X": 5}}):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                actor_driver.dimension_entity_view({"id": "minecraft:horse", **tag})

    def test_fixture_copy_does_not_replace_native_forge_config(self):
        with tempfile.TemporaryDirectory() as folder:
            source, runtime = Path(folder) / "source", Path(folder) / "runtime"
            (source / WORLD).mkdir(parents=True)
            (source / "config").mkdir()
            (source / "migration-fixture.json").write_text("{}", encoding="utf-8")
            (source / "config/poweradvantage.cfg").write_text("historical", encoding="utf-8")
            (runtime / "config").mkdir(parents=True)
            (runtime / "config/fml.toml").write_text("native", encoding="utf-8")
            actor_driver.copy_fixture(source, runtime)
            self.assertEqual("native", (runtime / "config/fml.toml").read_text(encoding="utf-8"))
            self.assertEqual("historical", (runtime / "config/poweradvantage.cfg").read_text(encoding="utf-8"))


class NetworkAdapters(unittest.TestCase):
    def test_native_witness_inventory_command(self):
        item = 'minecraft:dirt{AdvWorksWitness:"S-EXT-north"}'
        self.assertEqual('minecraft:dirt[minecraft:custom_data={AdvWorksWitness:"S-EXT-north"}]',
                         network_driver.native_inventory_item(item))
        self.assertEqual("minecraft:apple", network_driver.native_inventory_item("minecraft:apple"))

    def test_compatibility_view_retains_native_components_and_does_not_edit_observations(self):
        source = {"callbacks": {"drill": {"after": {"nbt":
                  '{Items:[{id:"minecraft:dirt",Slot:0b,count:7,'
                  'components:{"minecraft:custom_data":{AdvWorksWitness:"test"},"other:component":5}}]}'}}}}
        original = copy.deepcopy(source)
        projected = network_driver.compatibility_view(source)
        stack = nbtlib.parse_nbt(projected["callbacks"]["drill"]["after"]["nbt"])["Items"][0]
        self.assertEqual(7, stack["count"])
        self.assertEqual(7, stack["Count"])
        self.assertEqual("test", stack["tag"]["AdvWorksWitness"])
        self.assertEqual(5, stack["components"]["other:component"])
        self.assertEqual(original, source)

    def test_legacy_or_badly_typed_stack_is_not_native_evidence(self):
        for count in ("Count:7b", "count:7b", "count:0"):
            with self.subTest(count=count), self.assertRaises(AssertionError):
                network_driver.compatibility_view({"nbt": '{Items:[{id:"minecraft:dirt",' + count + '}]}'})


if __name__ == "__main__":
    unittest.main()
