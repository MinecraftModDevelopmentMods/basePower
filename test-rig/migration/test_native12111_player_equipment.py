"""Native-only player equipment placement; item loss must remain red."""

import copy
from pathlib import Path
import unittest

import actors
import native12111_actors as native


class NativePlayerEquipmentTest(unittest.TestCase):
    def setUp(self):
        self.player = {"Inventory": [
            {"id": "minecraft:dirt", "Slot": 0, "count": 17},
            *({"id": "minecraft:" + item, "Slot": slot, "count": 1, "components": {
                "minecraft:damage": damage,
                "minecraft:enchantments": {"minecraft:unbreaking": 3},
                "minecraft:custom_data": {"Nested": {"OwnerNote": "keep-this-verbatim"}},
            }} for slot, item, damage in ((100, "iron_boots", 9), (101, "iron_leggings", 10),
                                         (102, "iron_chestplate", 11), (103, "iron_helmet", 12),
                                         (-106, "shield", 5)))], "Health": 20}
        self.expected = native.expected_player_equipment(self.player)

    def check(self, actual):
        return native.verdict(actors.compare, {"file": self.expected, "entities": {}},
                              {"file": actual, "entities": {}})

    def test_exact_five_slot_placement_and_every_item_field_are_preserved(self):
        original = copy.deepcopy(self.player)
        self.assertEqual([self.player["Inventory"][0]], self.expected["Inventory"])
        self.assertEqual({"feet", "legs", "chest", "head", "offhand"}, set(self.expected["equipment"]))
        for item in self.player["Inventory"][1:]:
            self.assertEqual({key: value for key, value in item.items() if key != "Slot"},
                             self.expected["equipment"][native.PLAYER_EQUIPMENT_SLOTS[item["Slot"]]])
        self.assertEqual(original, self.player)
        self.assertEqual("PASS", self.check(copy.deepcopy(self.expected))["status"])

    def test_native_observation_includes_complete_unmodified_equipment(self):
        value = copy.deepcopy(self.expected)
        value["equipment"]["head"]["other:opaque"] = {"Slot": 103, "Count": 7, "id": "literal"}
        before = copy.deepcopy(value)
        actual = native.player_semantic(value, actors.PLAYER_KEYS, {})
        self.assertEqual(value["equipment"], actual["equipment"])
        self.assertEqual(before, value)

    def test_missing_each_equipment_stack_or_whole_map_is_rejected(self):
        for slot in (*self.expected["equipment"], None):
            actual = copy.deepcopy(self.expected)
            if slot is None:
                del actual["equipment"]
            else:
                del actual["equipment"][slot]
            with self.subTest(slot=slot):
                self.assertEqual("FAIL", self.check(actual)["status"])

    def test_wrong_slot_count_damage_enchantment_custom_data_and_extra_data_are_rejected(self):
        variants = []
        for key, value in (("count", 2), ("id", "minecraft:diamond_helmet"), ("Slot", 103)):
            actual = copy.deepcopy(self.expected)
            actual["equipment"]["head"][key] = value
            variants.append(actual)
        for component, value in (("minecraft:damage", 0), ("minecraft:enchantments", {}),
                                 ("minecraft:custom_data", {}), ("other:new", 1)):
            actual = copy.deepcopy(self.expected)
            actual["equipment"]["head"]["components"][component] = value
            variants.append(actual)
        actual = copy.deepcopy(self.expected)
        actual["equipment"]["head"], actual["equipment"]["offhand"] = (
            actual["equipment"]["offhand"], actual["equipment"]["head"])
        variants.append(actual)
        actual = copy.deepcopy(self.expected)
        actual["equipment"]["body"] = copy.deepcopy(actual["equipment"]["head"])
        variants.append(actual)
        for index, actual in enumerate(variants):
            with self.subTest(index=index):
                self.assertEqual("FAIL", self.check(actual)["status"])
                self.assertEqual("FAIL", self.check(actual)["player_status"])

    def test_no_extra_equipment_is_allowed_even_when_none_expected(self):
        result = native.verdict(actors.compare, {"file": {"Inventory": []}, "entities": {}},
                                {"file": {"Inventory": [], "equipment": self.expected["equipment"]}, "entities": {}})
        self.assertEqual("FAIL", result["status"])

    def test_unopened_conversion_file_stays_historical_but_host_and_read_states_move(self):
        source = {key: copy.deepcopy(self.player) for key in ("file", "adapter", "embedded")}
        source["entities"] = {}
        original = copy.deepcopy(source)
        converted = native.expected_state(source, "actors", adapter=False)
        self.assertEqual(self.player, converted["file"])
        self.assertNotIn("adapter", converted)
        self.assertEqual(self.expected, converted["embedded"])
        loaded = native.expected_state(source, "actors")
        for key in ("file", "adapter", "embedded"):
            self.assertEqual(self.expected, loaded[key])
        self.assertEqual(original, source)
        self.assertEqual(loaded, native.expected_state(loaded, "actors"))

    def test_unknown_slots_are_preserved_and_duplicate_or_ambiguous_layouts_rejected(self):
        unknown = copy.deepcopy(self.player)
        unknown["Inventory"].append({"id": "minecraft:apple", "Slot": 99, "count": 2})
        self.assertEqual(unknown["Inventory"][-1], native.expected_player_equipment(unknown)["Inventory"][-1])
        variants = []
        duplicate = copy.deepcopy(self.player)
        duplicate["Inventory"].append(copy.deepcopy(duplicate["Inventory"][1]))
        variants.append(duplicate)
        mixed = copy.deepcopy(self.player)
        mixed["equipment"] = {}
        variants.append(mixed)
        for bad in (None, True, "100"):
            malformed = copy.deepcopy(self.player)
            malformed["Inventory"][1]["Slot"] = bad
            variants.append(malformed)
        for value in variants:
            with self.subTest(value=value), self.assertRaises(ValueError):
                native.expected_player_equipment(value)

    def test_native61_wolf_controls_are_separately_pinned_not_historical_controls(self):
        root = (Path(__file__).resolve().parents[4] / "_verification/base-12111-port-20261111"
                / "native12111-wolf-platform-native-a")
        for version, checksum in native.WOLF_CONTROL_HASHES.items():
            path = root / f"legacy-{version}-receipt.json"
            native.native.validate_hash(path, checksum)
            self.assertEqual(4671, native.native.read_json(path)["targetDataVersion"])


if __name__ == "__main__":
    unittest.main()
