import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native12111_upgrade as upgrade


class ExpectedComponentsTest(unittest.TestCase):
    def stack(self):
        return {"id": "basesteam:musket", "count": 1, "Slot": 3, "unknown": [7, {"marker": "keep"}],
                "components": {"minecraft:damage": 5,
                               "minecraft:enchantments": {"levels": {"basesteam:powderless": 1}},
                               "minecraft:stored_enchantments": {"levels": {"minecraft:unbreaking": 255}},
                               "minecraft:custom_data": {"loaded": 1, "unknown": {"levels": {"x": "keep"}}},
                               "other:opaque": {"id": "other:item", "count": 1, "components": {
                                   "minecraft:enchantments": {"levels": {"other:opaque": 2}}}}}}

    def adapt(self, value, version="1.20.6"):
        return upgrade.expected_components(value, source_version=version)

    def test_both_modern_versions_translate_only_the_two_enchantment_wrappers(self):
        for version in ("1.20.6", "1.21.1"):
            with self.subTest(version=version):
                source = {"Items": [self.stack()], "components": {}, "unknown": {"marker": [1, 2]}}
                original = copy.deepcopy(source)
                expected = copy.deepcopy(source)
                expected["Items"][0]["components"]["minecraft:enchantments"] = {"basesteam:powderless": 1}
                expected["Items"][0]["components"]["minecraft:stored_enchantments"] = {"minecraft:unbreaking": 255}
                actual = self.adapt(source, version)
                self.assertEqual(expected, actual)
                self.assertEqual(original, source)
                actual["Items"][0]["components"]["other:opaque"]["count"] = 99
                self.assertEqual(original, source)

    def test_empty_enchantments_stay_present_and_absent_components_stay_absent(self):
        value = self.stack()
        value["components"] = {"minecraft:enchantments": {"levels": {}},
                               "minecraft:stored_enchantments": {"levels": {}}}
        expected = copy.deepcopy(value)
        expected["components"] = {"minecraft:enchantments": {}, "minecraft:stored_enchantments": {}}
        self.assertEqual(expected, self.adapt(value))
        del value["components"]
        self.assertEqual(value, self.adapt(value))

    def test_legacy_1201_translation_is_retained(self):
        source = {"id": "basesteam:musket", "Count": 1, "Slot": 3,
                  "tag": {"Damage": 5, "Enchantments": [{"id": "basesteam:powderless", "lvl": 1}],
                          "StoredEnchantments": [{"id": "minecraft:unbreaking", "lvl": 2}], "unknown": [7]}}
        original = copy.deepcopy(source)
        self.assertEqual(upgrade.components(source), self.adapt(source, "1.20.1"))
        self.assertEqual(original, source)

    def test_source_version_is_required_and_native_observations_are_not_a_source_format(self):
        with self.assertRaises(TypeError):
            upgrade.expected_components(self.stack())
        for version in (None, "1.20.5", "1.21", "1.21.11", 1.21, True, {}):
            with self.subTest(version=version), self.assertRaisesRegex(AssertionError, "source.*version"):
                self.adapt(self.stack(), version)

    def test_modern_sources_reject_legacy_or_mixed_count_representation(self):
        for version in ("1.20.6", "1.21.1"):
            for mixed in (False, True):
                value = self.stack()
                value["Count"] = 1
                if not mixed:
                    del value["count"]
                with self.subTest(version=version, mixed=mixed), self.assertRaisesRegex(AssertionError, "count"):
                    self.adapt(value, version)

    def test_modern_stack_and_component_types_are_not_coerced(self):
        for field, invalid in (("count", True), ("count", "1"), ("count", 1.0), ("count", 0),
                               ("id", 1), ("id", ""), ("components", []), ("components", None)):
            value = self.stack()
            value[field] = invalid
            with self.subTest(field=field, invalid=invalid), self.assertRaises(AssertionError):
                self.adapt(value)

    def test_wrapper_fields_are_exact_and_tooltip_changes_require_a_separate_proven_adapter(self):
        for wrapper in ({}, {"levels": {}, "unknown": 7}, {"levels": {}, "show_in_tooltip": False},
                        {"basesteam:powderless": 1}, [], None, True):
            for component in ("minecraft:enchantments", "minecraft:stored_enchantments"):
                value = self.stack()
                value["components"][component] = wrapper
                original = copy.deepcopy(value)
                with self.subTest(component=component, wrapper=wrapper), self.assertRaisesRegex(AssertionError, "wrapper"):
                    self.adapt(value)
                self.assertEqual(original, value)

    def test_levels_require_a_map_of_exact_identifiers_and_native_integer_range(self):
        for levels in ([], None, True, 1, {7: 1}, {"": 1}, {"bad id": 1}, {"other:bad name": 1},
                       {"minecraft:unbreaking": True}, {"minecraft:unbreaking": "1"},
                       {"minecraft:unbreaking": 1.0}, {"minecraft:unbreaking": 0},
                       {"minecraft:unbreaking": -1}, {"minecraft:unbreaking": 256}):
            value = self.stack()
            value["components"]["minecraft:enchantments"] = {"levels": levels}
            with self.subTest(levels=levels), self.assertRaises(AssertionError):
                self.adapt(value)

    def receipt_gate(self, declared, data_version, *, historical=False):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            args = SimpleNamespace(source=source, legacy_reference=source if historical else None,
                                   receipt_sha256="sealed", profile=source, seed=source, inventory=source,
                                   inventory_sha256="sealed")
            receipt = {"status": "PASS", "sourceUnchanged": True, "phases": {"restarted": {
                "status": "PASS", "snapshotSha256": "sealed", "serialized": {"nativeDataVersion": data_version}}}}
            if declared != "absent":
                receipt["minecraft"] = declared
            with patch.object(upgrade, "validate_hash"), patch.object(upgrade, "read_json", side_effect=[receipt, []]), \
                    patch.object(upgrade, "read_family_target", side_effect=RuntimeError("source-version-accepted")):
                upgrade.run(args)

    def test_sealed_receipt_gate_accepts_exact_modern_versions_and_the_proven_legacy_fallback(self):
        for version, data_version in (("1.20.1", 3465), ("1.20.6", 3839), ("1.21.1", 3955)):
            with self.subTest(version=version), self.assertRaisesRegex(RuntimeError, "source-version-accepted"):
                self.receipt_gate(version, data_version)
        with self.assertRaisesRegex(RuntimeError, "source-version-accepted"):
            self.receipt_gate("absent", 3465, historical=True)

    def test_sealed_receipt_gate_rejects_mismatch_missing_version_and_untyped_data_version(self):
        for version, data_version, historical in (("1.20.6", 3955, False), ("1.21.1", 3839, False),
                                                 ("absent", 3465, False), ("absent", 3839, True),
                                                 (None, 3465, True), ("1.20.6", "3839", False),
                                                 ("1.20.6", 3839.0, False), ("1.20.6", True, False)):
            with self.subTest(version=version, data_version=data_version), \
                    self.assertRaisesRegex(AssertionError, "source component version"):
                self.receipt_gate(version, data_version, historical=historical)

    def test_comparison_adapts_only_expected_values_and_keeps_negative_controls(self):
        witness = {"name": "chest", "x": 1, "y": 5, "z": 1, "actualName": "minecraft:chest",
                   "properties": {}, "nbtBase64": "fixture"}
        source = {"Items": [self.stack()], "components": {}}
        native = self.adapt(source)
        for version in ("1.20.6", "1.21.1"):
            for change in (None, "level", "count", "extra", "missing", "wrapper", "unknown"):
                observed = copy.deepcopy(native)
                stack = observed["Items"][0]
                enchantments = stack["components"]["minecraft:enchantments"]
                if change == "level":
                    enchantments["basesteam:powderless"] = 2
                elif change == "count":
                    stack["count"] = 2
                elif change == "extra":
                    enchantments["other:added"] = 1
                elif change == "missing":
                    del enchantments["basesteam:powderless"]
                elif change == "wrapper":
                    stack["components"]["minecraft:enchantments"] = {"levels": enchantments}
                elif change == "unknown":
                    stack["components"]["other:added"] = 7
                original = copy.deepcopy((source, observed))
                with self.subTest(version=version, change=change), patch.object(
                        upgrade, "parse_nbt", side_effect=[source, observed]):
                    result = upgrade.comparison([witness], [witness], modern=True, source_version=version)
                self.assertEqual("PASS" if change is None else "FAIL", result["status"])
                self.assertEqual(original, (source, observed))


if __name__ == "__main__":
    unittest.main()
