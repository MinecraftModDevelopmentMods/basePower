import copy
from pathlib import Path
import tempfile
import unittest

import native2612_retained_worldgen as retained


def fixture():
    return dict(manage_vanilla_ores=False, place_fluid_deposits=True, suppress_all_ore_features=False,
                history={"note": "electricadvantage:li_ore is literal history"}, ores={
        "electricadvantage:legacy/custom_lithium": dict(block="electricadvantage:li_ore", enabled=True,
            source_provider="electricadvantage", opaque={"keep": [1, 2, 3], "block": "electricadvantage:li_ore"}, dimension_selectors={
                "orespawn:all_except_nether_end": dict(enabled=True, min_y=3, max_y=29,
                    frequency=0.375, min_quantity=5, max_quantity=11, pattern="default",
                    spread=7, vertical_spread=2, node_size=8, biome_ids=["minecraft:forest"],
                    excluded_biome_ids=["minecraft:desert"])})}, fluid_deposits={})


class RetainedWorldgenContractTest(unittest.TestCase):
    registry = {"baseelectric:li_ore", "baseelectric:sulfur_ore", "basepower:crude_oil", "minecraft:nether_quartz_ore"}

    def fixed(self, source):
        result = copy.deepcopy(source)
        rule = result["ores"]["electricadvantage:legacy/custom_lithium"]
        rule["block"] = "baseelectric:li_ore"
        return result

    def test_full_payload_and_rule_id_are_retained_without_rewriting_history(self):
        source = fixture()
        result = retained.audit_transition(source, self.fixed(source), self.registry)
        self.assertEqual("PASS", result["status"])
        self.assertEqual("electricadvantage:li_ore is literal history", source["history"]["note"])

    def test_provider_identity_and_opaque_block_text_are_not_output_references(self):
        source = fixture()
        actual = self.fixed(source)
        self.assertEqual("PASS", retained.audit_transition(source, actual, self.registry)["status"])
        actual["ores"]["electricadvantage:legacy/custom_lithium"]["source_provider"] = "baseelectric"
        self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_zero_rate_saved_rule_cannot_be_reenabled_by_missing_default(self):
        source = fixture()
        source["ores"]["electricadvantage:legacy/custom_lithium"]["dimension_selectors"]["orespawn:all_except_nether_end"]["frequency"] = 0.0
        actual = self.fixed(source)
        actual["ores"]["baseelectric:ore/lithium"] = dict(block="baseelectric:li_ore", enabled=True,
            dimensions={"minecraft:overworld": {"enabled": True, "frequency": 0.125}})
        self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_old_unknown_output_is_failure_even_if_valid_fallback_exists(self):
        source = fixture()
        actual = copy.deepcopy(source)
        actual["ores"]["baseelectric:ore/lithium"] = dict(block="baseelectric:li_ore", enabled=True,
            dimensions={"minecraft:overworld": {"enabled": True}})
        result = retained.audit_transition(source, actual, self.registry)
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any(row["kind"] == "UNKNOWN_OUTPUT" for row in result["issues"]))

    def test_each_user_bound_geometry_filter_and_enabled_mutation_is_rejected(self):
        source = fixture()
        for key, value in dict(min_y=4, max_y=30, frequency=0.5, min_quantity=6, max_quantity=12,
                               spread=8, vertical_spread=4, node_size=4, enabled=False,
                               biome_ids=[], excluded_biome_ids=[]).items():
            with self.subTest(key=key):
                actual = self.fixed(source)
                actual["ores"]["electricadvantage:legacy/custom_lithium"]["dimension_selectors"][
                    "orespawn:all_except_nether_end"][key] = value
                self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_dimension_selector_must_not_be_replaced_by_overworld_fallback(self):
        source = fixture()
        actual = self.fixed(source)
        rule = actual["ores"]["electricadvantage:legacy/custom_lithium"]
        rule["dimensions"] = {"minecraft:overworld": rule.pop("dimension_selectors")["orespawn:all_except_nether_end"]}
        self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_disabled_saved_rule_does_not_allow_new_enabled_default(self):
        source = fixture()
        source["ores"]["electricadvantage:legacy/custom_lithium"]["enabled"] = False
        actual = self.fixed(source)
        actual["ores"]["baseelectric:ore/lithium"] = dict(block="baseelectric:li_ore", enabled=True,
            dimensions={"minecraft:overworld": {"enabled": True}})
        result = retained.audit_transition(source, actual, self.registry)
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any(row["kind"] == "NEW_ACTIVE_DEFAULT" for row in result["issues"]))

    def test_enabled_saved_rule_also_rejects_new_duplicate_generation(self):
        source = fixture()
        actual = self.fixed(source)
        actual["ores"]["baseelectric:ore/lithium"] = copy.deepcopy(actual["ores"]["electricadvantage:legacy/custom_lithium"])
        self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_explicit_current_id_collision_is_not_replaced_with_old_default(self):
        source = fixture()
        source["ores"]["baseelectric:ore/lithium"] = dict(block="baseelectric:li_ore", enabled=False,
            dimensions={"minecraft:overworld": dict(enabled=True, min_y=9, max_y=17, frequency=0.875)})
        actual = self.fixed(source)
        self.assertEqual("PASS", retained.audit_transition(source, actual, self.registry)["status"])
        actual["ores"]["baseelectric:ore/lithium"]["enabled"] = True
        self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_rule_keys_must_not_silently_rename_or_disappear(self):
        source = fixture()
        actual = self.fixed(source)
        actual["ores"]["baseelectric:legacy/custom_lithium"] = actual["ores"].pop("electricadvantage:legacy/custom_lithium")
        self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_opaque_rule_payload_and_root_history_cannot_be_dropped(self):
        source = fixture()
        for remove in ("opaque", "history"):
            actual = self.fixed(source)
            if remove == "opaque": del actual["ores"]["electricadvantage:legacy/custom_lithium"][remove]
            else: del actual[remove]
            self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_source_controls_cannot_change_even_with_all_positive_sample_counts(self):
        source = fixture()
        for key in ("manage_vanilla_ores", "suppress_all_ore_features", "place_fluid_deposits"):
            actual = self.fixed(source)
            actual[key] = not source[key]
            self.assertEqual("FAIL", retained.audit_transition(source, actual, self.registry)["status"])

    def test_os1_oracle_uses_raw_source_rates_and_original_selector(self):
        source = dict(dimensions=[dict(dimension="+", ores=[dict(blockID="electricadvantage:li_ore",
            minHeight=3, maxHeight=30, frequency=0.375, size=8, variation=2, biomes=["FOREST"])])])
        expected = retained.os1_profile(source, "baseelectric", "electricadvantage")
        row = expected["ores"]["baseelectric:legacy/baseelectric_li_ore_0"]
        rule = row["dimension_selectors"]["orespawn:all_except_nether_end"]
        self.assertEqual((3, 29, 0.375, 6, 9, 2, 8), tuple(rule[k] for k in
            ("min_y", "max_y", "frequency", "min_quantity", "max_quantity", "vertical_spread", "node_size")))
        self.assertEqual(["FOREST"], rule["biome_dictionary"])

    def test_new_chunk_custody_rejects_any_preexisting_region_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            retained.require_new_world(root)
            region = root / "dimensions/minecraft/overworld/region/r.0.0.mca"
            region.parent.mkdir(parents=True)
            region.write_bytes(b"old-region")
            with self.assertRaisesRegex(AssertionError, "preexisting"):
                retained.require_new_world(root)


if __name__ == "__main__":
    unittest.main()
