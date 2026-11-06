import unittest

from native113_rebrand_order import compare_exact, migrated_names


class RebrandOrderOracleTest(unittest.TestCase):
    def test_old_oil_item_and_fluid_are_distinct(self):
        saved = {
            "Items": [{"id": "poweradvantage:crude_oil", "Count": 1, "Slot": 3,
                       "tag": {"Witness": "poweradvantage:crude_oil"}}],
            "Tank": {"FluidName": "poweradvantage:crude_oil", "Amount": 3250},
        }
        expected = migrated_names(saved)
        self.assertEqual("basepower:crude_oil_bucket", expected["Items"][0]["id"])
        self.assertEqual("basepower:crude_oil", expected["Tank"]["FluidName"])
        self.assertEqual(3250, expected["Tank"]["Amount"])
        self.assertEqual(3, expected["Items"][0]["Slot"])
        self.assertEqual(saved["Items"][0]["tag"], expected["Items"][0]["tag"])

    def test_missing_or_replaced_state_fails(self):
        expected = {"id": "baseelectric:hydroturbine", "UUIDMost": 3}
        for actual in ({"id": "electricadvantage:hydroturbine", "UUIDMost": 3},
                       {"id": "baseelectric:hydroturbine", "UUIDMost": 4},
                       {"id": "baseelectric:hydroturbine"},
                       dict(expected, extra=True)):
            with self.subTest(actual=actual):
                self.assertEqual("FAIL", compare_exact(expected, actual)["status"])
        self.assertEqual("PASS", compare_exact(expected, dict(expected))["status"])
