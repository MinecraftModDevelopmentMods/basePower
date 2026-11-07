import unittest

from native117 import native_normalized, source_normalized
from native117_identity import command_ids, fixture_ids, target_id


class NamespaceUpgradeTest(unittest.TestCase):
    def test_maps_expected_nested_items_and_fluids_without_changing_amounts(self):
        old = {
            "id": "minecraft:poweradvantage.tileentity.fluid_metal_tank",
            "Tank": {"FluidName": "poweradvantage:crude_oil", "Amount": 1730},
            "Items": [{"id": "steamadvantage:musket", "Count": 1,
                       "tag": {"display": {"Name": "steamadvantage:musket"},
                               "Enchantments": [{"id": "steamadvantage:rapid_reload", "lvl": 2}]}}],
        }
        actual = source_normalized(old)
        self.assertEqual("basepower:tileentity.fluid_metal_tank", actual["id"])
        self.assertEqual({"FluidName": "basepower:crude_oil", "Amount": 1730}, actual["Tank"])
        item = actual["Items"][0]
        self.assertEqual(("basesteam:musket", 1), (item["id"], item["Count"]))
        self.assertEqual("steamadvantage:musket", item["tag"]["display"]["Name"])
        self.assertEqual([{"id": "basesteam:rapid_reload", "lvl": 2}], item["tag"]["Enchantments"])

    def test_does_not_hide_an_unmigrated_target(self):
        old = {"id": "electricadvantage:electric_drill"}
        self.assertNotEqual(source_normalized(old), native_normalized(old))

    def test_keeps_base_family_and_other_mods_unchanged(self):
        for identity in ("basepower:crude_oil", "basesteam:steam_drill", "minecraft:water",
                         "mineralogy:crude_oil", "other:poweradvantage"):
            self.assertEqual(identity, target_id(identity))

    def test_retargets_only_native_command_and_fixture_identifiers(self):
        self.assertEqual("setblock 0 0 0 basesteam:steam_drill[facing=west]",
                         command_ids("setblock 0 0 0 steamadvantage:steam_drill[facing=west]"))
        self.assertEqual({"basepower:infinite_steam": ["baseelectric:electric_drill", 4]},
                         fixture_ids({"poweradvantage:infinite_steam": ["electricadvantage:electric_drill", 4]}))


if __name__ == "__main__":
    unittest.main()
