import copy
import unittest

import nbtlib
from native12111_actor_fixtures import BATTERIES, legal_ender_batteries
from native12111_actors import source_components


class NativeActorFixtureControls(unittest.TestCase):
    def item(self, identity="electricadvantage:lithium_battery", count=3, slot=1):
        return nbtlib.Compound(id=nbtlib.String(identity), Count=nbtlib.Byte(count), Slot=nbtlib.Byte(slot),
                               tag=nbtlib.Compound(energy=nbtlib.Double(123), Nested=nbtlib.Compound(
                                   OwnerNote=nbtlib.String("keep-this-verbatim"), Items=nbtlib.List[nbtlib.Int]([7, 5]))))

    def test_known_split_keeps_totals_metadata_types_and_unique_slots(self):
        original = nbtlib.Compound(EnderItems=nbtlib.List[nbtlib.Compound]([
            self.item(identity=identity, slot=index) for index, identity in enumerate(sorted(BATTERIES)[:4])]))
        before = copy.deepcopy(original)
        edits = legal_ender_batteries(original)
        self.assertEqual(4, len(edits))
        self.assertEqual(12, len(original["EnderItems"]))
        self.assertEqual(12, len(set(int(row["Slot"]) for row in original["EnderItems"])))
        for first in before["EnderItems"]:
            after = [row for row in original["EnderItems"] if row["id"] == first["id"]]
            self.assertEqual(3, sum(int(row["Count"]) for row in after))
            self.assertTrue(all(type(row["Count"]) is nbtlib.Byte and row["tag"] == first["tag"] for row in after))
        self.assertEqual(12, sum(int(row["Count"]) for row in before["EnderItems"]))

    def test_unknown_items_and_other_containers_are_not_normalized(self):
        player = nbtlib.Compound(EnderItems=nbtlib.List[nbtlib.Compound]([self.item("other:unknown", 7)]),
                                Inventory=nbtlib.List[nbtlib.Compound]([self.item()]))
        before = copy.deepcopy(player)
        self.assertEqual([], legal_ender_batteries(player))
        self.assertEqual(before, player)

    def test_unreviewed_count_duplicate_or_full_slots_fail_without_changes(self):
        variants = [nbtlib.Compound(EnderItems=nbtlib.List[nbtlib.Compound]([self.item(count=2)])),
                    nbtlib.Compound(EnderItems=nbtlib.List[nbtlib.Compound]([self.item(), self.item()])),
                    nbtlib.Compound(EnderItems=nbtlib.List[nbtlib.Compound]([self.item(slot=0),
                        *(self.item("other:item", 1, slot) for slot in range(1, 27))]))]
        for player in variants:
            before = copy.deepcopy(player)
            with self.subTest(player=player), self.assertRaises(AssertionError):
                legal_ender_batteries(player)
            self.assertEqual(before, player)

    def test_native1211_expectation_flattens_only_enchantment_wrapper(self):
        stack = {"id": "basesteam:musket", "count": 1, "Slot": 0, "components": {
            "minecraft:enchantments": {"levels": {"basesteam:recoil": 3}},
            "minecraft:damage": 5, "minecraft:custom_data": {"Nested": {"id": "opaque", "Count": 19}}}}
        before = copy.deepcopy(stack)
        result = source_components(stack, 3955)
        self.assertEqual({"basesteam:recoil": 3}, result["components"]["minecraft:enchantments"])
        self.assertEqual(stack["components"]["minecraft:custom_data"], result["components"]["minecraft:custom_data"])
        self.assertEqual(before, stack)
        with self.assertRaises(ValueError):
            source_components(stack, 3956)


if __name__ == "__main__":
    unittest.main()
