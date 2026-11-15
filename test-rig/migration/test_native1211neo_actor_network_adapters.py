"""Retain strict actor and network oracles for native NeoForge observations."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nbtlib

import actors
import dimensions
import native1211neo_actors as actor_driver
import native1211neo_networks as network_driver
from matrix import WORLD


class ActorAdapters(unittest.TestCase):
    def test_expected_swim_aliases_preserve_values_modifiers_and_nested_passengers(self):
        payload = {"Base": 2.5, "Modifiers": [{"Name": "other:current", "Amount": 0.125,
                   "Operation": 0, "UUIDMost": 17, "UUIDLeast": 19, "opaque": {"keep": [1, 2]}}]}
        for alias in ("forge.swimSpeed", "forge:swim_speed"):
            with self.subTest(alias=alias):
                entity = {"Attributes": {alias: copy.deepcopy(payload)}, "Passengers": [
                    {"Attributes": {alias: copy.deepcopy(payload)}}]}
                source = {key: copy.deepcopy(entity) for key in ("file", "adapter", "embedded")}
                source["entities"] = {"horse": copy.deepcopy(entity), "passenger-wolf": copy.deepcopy(entity)}
                original = copy.deepcopy(source)
                with patch.object(actor_driver.historical, "baseline", return_value=(source, "source")):
                    expected, label = actor_driver.baseline(Path("unused"), "dimensions")
                self.assertEqual("source", label)
                for value in [expected[key] for key in ("file", "adapter", "embedded")] + list(expected["entities"].values()):
                    self.assertEqual({"neoforge:swim_speed": payload}, value["Attributes"])
                    self.assertEqual({"neoforge:swim_speed": payload}, value["Passengers"][0]["Attributes"])
                self.assertEqual(original, source)

    def test_expected_swim_alias_collisions_are_rejected_even_for_identical_values(self):
        for aliases in (("forge.swimSpeed", "forge:swim_speed"),
                        ("forge.swimSpeed", "neoforge:swim_speed"),
                        ("forge:swim_speed", "neoforge:swim_speed"),
                        ("forge.swimSpeed", "forge:swim_speed", "neoforge:swim_speed")):
            with self.subTest(aliases=aliases):
                source = {"entities": {"horse": {"Attributes": {name: {"Base": 2.5} for name in aliases}}}}
                original = copy.deepcopy(source)
                with patch.object(actor_driver.historical, "baseline", return_value=(source, "source")):
                    with self.assertRaisesRegex(AssertionError, "Ambiguous source attribute aliases"):
                        actor_driver.baseline(Path("unused"), "dimensions")
                self.assertEqual(original, source)

    def test_swim_alias_is_finite_and_does_not_fill_in_absent_attributes(self):
        attributes = {"forge:swim_speed": {"Base": 2.5}, "other:swim_speed": {"Base": 7.0},
                      "forge.swimSpeedExtra": {"Base": 11.0}}
        source = {"entities": {"horse": {"Attributes": attributes}, "boat": {"Attributes": {}}}}
        with patch.object(actor_driver.historical, "baseline", return_value=(source, "source")):
            expected, _ = actor_driver.baseline(Path("unused"), "dimensions")
        self.assertEqual({"neoforge:swim_speed": {"Base": 2.5}, "other:swim_speed": {"Base": 7.0},
                          "forge.swimSpeedExtra": {"Base": 11.0}}, expected["entities"]["horse"]["Attributes"])
        self.assertEqual({}, expected["entities"]["boat"]["Attributes"])

    def test_observed_attribute_names_stay_literal_and_wrong_legacy_name_still_fails(self):
        source = {"entities": {"horse": {"id": "minecraft:horse", "Attributes": {"forge.swimSpeed": {"Base": 2.5}}}}}
        with patch.object(actor_driver.historical, "baseline", return_value=(source, "source")):
            expected, _ = actor_driver.baseline(Path("unused"), "dimensions")
        observed = {"id": "minecraft:horse", "attributes": [{"id": "forge.swimSpeed", "base": 2.5}]}
        original = copy.deepcopy(observed)
        projected = actor_driver.native_attribute_view(observed)
        self.assertEqual("forge.swimSpeed", projected["Attributes"][0]["Name"])
        actual = {"entities": {"horse": dimensions.entity_state(projected)}}
        self.assertEqual("FAIL", actor_driver.verdict(dimensions.verdict, expected, actual)["status"])
        self.assertEqual(original, observed)

    def test_native_attribute_view_keeps_values_and_opaque_modifiers(self):
        original = {"attributes": [{"id": "neoforge:swim_speed", "base": 2.5, "modifiers": [
            {"id": "other:current", "amount": 0.125, "operation": "add_value", "opaque": 17}]}]}
        before = copy.deepcopy(original)
        actual = actor_driver.native_attribute_view(original)
        self.assertEqual({"Attributes": [{"Name": "neoforge:swim_speed", "Base": 2.5, "Modifiers": [
            {"id": "other:current", "amount": 0.125, "operation": "add_value", "opaque": 17}]}]}, actual)
        self.assertEqual(before, original)
        for malformed in ({"attributes": [{"id": "x:y"}, {"id": "x:y"}]},
                          {"attributes": [], "Attributes": []}):
            with self.assertRaises(ValueError):
                actor_driver.native_attribute_view(malformed)

    def test_preserves_enchantments_damage_slot_count_and_opaque_custom_data(self):
        item = {"id": "basesteam:musket", "Count": 2, "Slot": -106, "tag": {
            "Damage": 5, "Enchantments": [{"id": "basesteam:recoil", "lvl": 3}],
            "StoredEnchantments": [{"id": "basesteam:powderless", "lvl": 1}],
            "Nested": {"id": "minecraft:dirt", "Count": 17, "ForgeData": {"opaque": "keep"}},
        }}
        original = copy.deepcopy(item)
        self.assertEqual({
            "id": "basesteam:musket", "count": 2, "Slot": -106, "components": {
                "minecraft:damage": 5,
                "minecraft:enchantments": {"levels": {"basesteam:recoil": 3}},
                "minecraft:stored_enchantments": {"levels": {"basesteam:powderless": 1}},
                "minecraft:custom_data": {"Nested": {
                    "id": "minecraft:dirt", "Count": 17, "ForgeData": {"opaque": "keep"}}},
            },
        }, actor_driver.expected_components(item))
        self.assertEqual(original, item)

    def test_rejects_missing_added_or_changed_native_stack_components_and_counts(self):
        expected = {"file": {"Inventory": [{"id": "minecraft:dirt", "Slot": 3, "count": 1,
                                             "components": {"minecraft:custom_data": {"Note": "original"}}}]},
                    "entities": {}}
        self.assertEqual("PASS", actor_driver.verdict(actors.compare, expected, copy.deepcopy(expected))["status"])
        variants = []
        for components in ({}, {"minecraft:custom_data": {"Note": "original"}, "minecraft:damage": 7},
                           {"minecraft:custom_data": {"Note": "changed"}}):
            actual = copy.deepcopy(expected)
            actual["file"]["Inventory"][0]["components"] = components
            variants.append(actual)
        actual = copy.deepcopy(expected)
        actual["file"]["Inventory"][0]["count"] = 2
        variants.append(actual)
        for actual in variants:
            with self.subTest(actual=actual):
                result = actor_driver.verdict(actors.compare, expected, actual)
                self.assertEqual("FAIL", result["status"])
                self.assertEqual("FAIL", result["player_status"])
                self.assertTrue(any(row["path"].endswith(".exact-item") for row in result["differences"]))

    def test_rejects_ambiguous_native_and_historical_stack(self):
        with self.assertRaises(AssertionError):
            actor_driver.expected_components({"id": "minecraft:dirt", "Count": 2, "count": 2})

    def test_dimension_projection_preserves_actual_leash_armor_jump_and_native_persistent_data(self):
        armor = {"id": "minecraft:iron_horse_armor", "count": 1,
                 "components": {"minecraft:custom_data": {"Note": "witness"}}}
        horse = {"id": "minecraft:horse", "leash": [168, 100, 8], "body_armor_item": armor,
                 "NeoForgeData": {"owner": [1, 2, 3, 4], "private": {"retained": 17}},
                 "Attributes": [{"Name": "minecraft:generic.jump_strength", "Base": 0.7}]}
        result = actor_driver.dimension_entity_view(copy.deepcopy(horse))
        self.assertEqual({"X": 168, "Y": 100, "Z": 8}, result["Leash"])
        self.assertEqual(armor, result["ArmorItem"])
        self.assertEqual(horse["NeoForgeData"], result["NeoForgeData"])
        self.assertEqual([{"Name": "horse.jumpStrength", "Base": 0.7}], result["Attributes"])

    def test_ambiguous_or_malformed_native_leash_is_not_normalized(self):
        for tag in ({"leash": [168, 100, True]}, {"leash": [168, 100, 8], "Leash": {"X": 5}}):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                actor_driver.dimension_entity_view({"id": "minecraft:horse", **tag})

    def test_fixture_copy_does_not_replace_native_neoforge_config(self):
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


class NativeDifferenceAssessment(unittest.TestCase):
    def setUp(self):
        self.before = {"entities": {"passenger-wolf": {
            "id": "minecraft:wolf", "Health": 8,
            "Attributes": {"generic.maxHealth": {"Base": 20}},
            "UUIDMost": 5, "UUIDLeast": 7, "OwnerUUID": "owner"}},
            "player": {"Inventory": [{"id": "minecraft:dirt", "count": 17,
                                       "components": {"minecraft:custom_data": {"keep": "exact"}}}]}}
        self.after = copy.deepcopy(self.before)
        self.after["entities"]["passenger-wolf"]["Health"] = 16
        self.after["entities"]["passenger-wolf"]["Attributes"]["generic.maxHealth"]["Base"] = 40

    def assess(self, actual, proof=Path("controls")):
        strict = actor_driver.verdict(dimensions.verdict, self.before, actual)
        with patch.object(actor_driver.native, "validate_hash") as hashes, \
                patch.object(actor_driver.native, "read_json", return_value={"noPower": True}):
            result = actor_driver.assess_native(self.before, actual, strict, proof, "scope", None)
        return result, hashes

    def test_only_controlled_native_wolf_change_is_classified_and_raw_failure_is_kept(self):
        result, hashes = self.assess(self.after)
        self.assertEqual("PASS_WITH_NATIVE_DIFFERENCE", result["status"])
        self.assertEqual("FAIL", result["rawStrictResult"]["status"])
        self.assertEqual(2, hashes.call_count)
        self.assertEqual(8, self.before["entities"]["passenger-wolf"]["Health"])

    def test_unrelated_inventory_payload_owner_uuid_or_health_changes_stay_failed(self):
        variants = []
        for key, value in (("OwnerUUID", "another"), ("Health", 15), ("UUIDLeast", 9)):
            changed = copy.deepcopy(self.after)
            changed["entities"]["passenger-wolf"][key] = value
            variants.append(changed)
        for key, value in (("count", 16), ("components", {})):
            changed = copy.deepcopy(self.after)
            changed["player"]["Inventory"][0][key] = value
            variants.append(changed)
        for actual in variants:
            with self.subTest(actual=actual):
                self.assertEqual("FAIL", self.assess(actual)[0]["status"])
        self.assertEqual("FAIL", self.assess(self.after, proof=None)[0]["status"])

    def test_control_hash_failure_is_not_accepted(self):
        strict = actor_driver.verdict(dimensions.verdict, self.before, self.after)
        with patch.object(actor_driver.native, "validate_hash", side_effect=AssertionError("changed control")):
            with self.assertRaises(AssertionError):
                actor_driver.assess_native(self.before, self.after, strict, Path("controls"), "scope", None)

    def test_controlled_wolf_difference_cannot_hide_swim_attribute_loss_or_changed_values(self):
        payload = {"Base": 2.5, "Modifiers": [{"Name": "other:current", "Amount": 0.125, "Operation": 0}]}
        self.before["entities"]["horse"] = {"id": "minecraft:horse", "Attributes": {}}
        for entity in self.before["entities"].values():
            entity["Attributes"]["forge.swimSpeed"] = copy.deepcopy(payload)
        with patch.object(actor_driver.historical, "baseline", return_value=(self.before, "source")):
            self.before, _ = actor_driver.baseline(Path("unused"), "dimensions")
        self.after = copy.deepcopy(self.before)
        self.after["entities"]["passenger-wolf"]["Health"] = 16
        self.after["entities"]["passenger-wolf"]["Attributes"]["generic.maxHealth"]["Base"] = 40
        self.assertEqual("PASS_WITH_NATIVE_DIFFERENCE", self.assess(self.after)[0]["status"])
        for witness in ("horse", "passenger-wolf"):
            for mutation in ("missing", "base", "modifier", "wrong-name"):
                with self.subTest(witness=witness, mutation=mutation):
                    actual = copy.deepcopy(self.after)
                    attributes = actual["entities"][witness]["Attributes"]
                    if mutation == "missing":
                        del attributes["neoforge:swim_speed"]
                    elif mutation == "base":
                        attributes["neoforge:swim_speed"]["Base"] = 9.0
                    elif mutation == "modifier":
                        attributes["neoforge:swim_speed"]["Modifiers"][0]["Amount"] = 0.5
                    else:
                        attributes["forge.swimSpeed"] = attributes.pop("neoforge:swim_speed")
                    original = copy.deepcopy(actual)
                    self.assertEqual("FAIL", self.assess(actual)[0]["status"])
                    self.assertEqual(original, actual)


class NetworkAdapters(unittest.TestCase):
    def test_native_witness_inventory_command_keeps_opaque_custom_data(self):
        item = 'minecraft:dirt{AdvWorksWitness:"S-EXT-north",ForgeData:{opaque:17}}'
        self.assertEqual('minecraft:dirt[minecraft:custom_data={AdvWorksWitness:"S-EXT-north",ForgeData:{opaque:17}}]',
                         network_driver.native_inventory_item(item))
        self.assertEqual("minecraft:apple", network_driver.native_inventory_item("minecraft:apple"))

    def test_compatibility_view_retains_native_components_without_editing_observations(self):
        source = {"callbacks": {"drill": {"after": {"nbt":
                  '{Items:[{id:"minecraft:dirt",Slot:0b,count:7,'
                  'components:{"minecraft:custom_data":{AdvWorksWitness:"test",ForgeData:{opaque:17}},'
                  '"other:component":5}}]}'}}}}
        original = copy.deepcopy(source)
        projected = network_driver.compatibility_view(source)
        stack = nbtlib.parse_nbt(projected["callbacks"]["drill"]["after"]["nbt"])["Items"][0]
        self.assertEqual(7, stack["count"])
        self.assertEqual(7, stack["Count"])
        self.assertEqual("test", stack["tag"]["AdvWorksWitness"])
        self.assertEqual(17, stack["components"]["minecraft:custom_data"]["ForgeData"]["opaque"])
        self.assertEqual(5, stack["components"]["other:component"])
        self.assertEqual(original, source)

    def test_neoforge_persistent_data_is_projected_only_at_the_tile_root(self):
        source = {"nbt": '{NeoForgeData:{AdvBayWitness:"kept",owner:[I;1,2,3,4]},'
                         'Items:[{id:"minecraft:dirt",count:1,components:{'
                         '"minecraft:custom_data":{NeoForgeData:{opaque:17}}}}]}'}
        original = copy.deepcopy(source)
        result = nbtlib.parse_nbt(network_driver.compatibility_view(source)["nbt"])
        self.assertEqual("kept", result["ForgeData"]["AdvBayWitness"])
        self.assertEqual([1, 2, 3, 4], list(result["ForgeData"]["owner"]))
        self.assertEqual(17, result["Items"][0]["components"]["minecraft:custom_data"]["NeoForgeData"]["opaque"])
        self.assertEqual(original, source)

    def test_ambiguous_native_and_legacy_persistent_data_is_rejected(self):
        with self.assertRaises(AssertionError):
            network_driver.compatibility_view({"nbt": '{NeoForgeData:{x:1},ForgeData:{x:2}}'})

    def test_legacy_ambiguous_or_badly_typed_stacks_are_not_native_evidence(self):
        for count in ("Count:7b", "count:7b", "count:0", "count:-1", "count:7,Count:7b", "count:7,tag:{}"):
            with self.subTest(count=count), self.assertRaises(AssertionError):
                network_driver.compatibility_view({"nbt": '{Items:[{id:"minecraft:dirt",' + count + '}]}'})

    def test_custom_data_must_remain_a_compound(self):
        with self.assertRaises(AssertionError):
            network_driver.compatibility_view({"nbt": '{Items:[{id:"minecraft:dirt",count:1,'
                                                      'components:{"minecraft:custom_data":17}}]}'})


if __name__ == "__main__":
    unittest.main()
