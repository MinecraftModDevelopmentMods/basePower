import unittest
from progression import EXCLUSIONS, NATURAL_RESOURCES, fixture, validate_tags


class ManufacturingOracleTest(unittest.TestCase):
    def test_natural_basis_cannot_supply_finished_advantage_parts(self):
        self.assertEqual(len(NATURAL_RESOURCES), len(set(NATURAL_RESOURCES)))
        advantage = [item for item in NATURAL_RESOURCES if item.split(":")[0]
                     in ("basepower", "basesteam", "baseelectric")]
        self.assertEqual(["baseelectric:li_ore", "baseelectric:sulfur_ore"], advantage)
        self.assertTrue(all(item.endswith("_ore") for item in NATURAL_RESOURCES if item.startswith("basemetals:")))

    def test_exclusions_do_not_hide_craftable_materials_or_machines(self):
        for item in ("baseelectric:psu", "basepower:starch", "baseelectric:li_nugget",
                     "basepower:converter_rf_quantum", "basesteam:steam_drill"):
            self.assertNotIn(item, EXCLUSIONS)
        self.assertEqual([16384, 78, 16384], fixture()["machineBounds"]["min"])

    def test_tag_oracle_rejects_the_original_empty_tags_and_wrong_board(self):
        tags = {key: [] for key in ("wires", "dusts/carbon", "rubber", "plastic", "circuit_boards", "solder")}
        with self.assertRaises(AssertionError):
            validate_tags({"tags": tags}, "NORMAL", True)
        tags.update({"wires": ["baseelectric:electric_conduit"],
                     "dusts/carbon": ["basemetals:coal_powder", "basemetals:charcoal_powder"],
                     "rubber": ["basepower:bioplastic_ingot"],
                     "plastic": ["basepower:bioplastic_ingot", "baseelectric:petrolplastic_ingot"],
                     "circuit_boards": ["baseelectric:control_circuit"],
                     "solder": ["baseelectric:solder"] + [f"basemetals:{m}_ingot" for m in ("lead", "tin", "silver")]})
        validate_tags({"tags": tags}, "NORMAL", True)
        tags["circuit_boards"].append("baseelectric:blank_circuit_board")
        with self.assertRaises(AssertionError):
            validate_tags({"tags": tags}, "NORMAL", True)
