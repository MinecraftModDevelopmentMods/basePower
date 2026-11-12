import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native2612_strict_migration as target


class StrictMigrationTest(unittest.TestCase):
    def test_every_previous_warning_is_a_failure_even_when_old_log_gate_accepts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "console.log"
            for text in target.REJECT:
                path.write_text("[Server thread/WARN] " + text + " 'old:rule'", encoding="utf-8")
                with patch.object(target.native, "require_clean_log", return_value=[]):
                    with self.assertRaisesRegex(AssertionError, "forbidden"):
                        target.clean_log(path)

    def test_clean_log_retains_native_platform_classifier(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "console.log"
            path.write_text("native startup completed", encoding="utf-8")
            with patch.object(target.native, "require_clean_log", return_value=["classified"]):
                self.assertEqual(["classified"], target.clean_log(path))
            with patch.object(target.native, "require_clean_log", side_effect=AssertionError("native failure")):
                with self.assertRaisesRegex(AssertionError, "native failure"): target.clean_log(path)

    def test_world_rates_disabled_choices_rule_ids_and_payloads_remain_authoritative(self):
        source = {"place_fluid_deposits": False, "ores": {"pack:saved": {"block": "electricadvantage:li_ore",
            "enabled": False, "dimensions": {"minecraft:overworld": {"frequency": 0.25, "private": "unchanged"}}}}}
        actual = copy.deepcopy(source)
        actual["ores"]["pack:saved"]["block"] = "baseelectric:li_ore"
        self.assertEqual("PASS", target.contract(source, actual, {"baseelectric:li_ore"})["status"])
        for change in ("rate", "enable", "root", "rule", "payload"):
            modified = copy.deepcopy(actual)
            row = modified["ores"]["pack:saved"]
            if change == "rate": row["dimensions"]["minecraft:overworld"]["frequency"] = 0.125
            elif change == "enable": row["enabled"] = True
            elif change == "root": modified["place_fluid_deposits"] = True
            elif change == "payload": row["dimensions"]["minecraft:overworld"]["private"] = "lost"
            else: modified["ores"]["pack:renamed"] = modified["ores"].pop("pack:saved")
            self.assertEqual("FAIL", target.contract(source, modified, {"baseelectric:li_ore"})["status"], change)

    def test_new_cross_section_active_fallback_cannot_hide_behind_positive_counts(self):
        source = {"ores": {"poweradvantage:legacy/oil": {"block": "poweradvantage:crude_oil", "enabled": False}}}
        actual = {"ores": {"poweradvantage:legacy/oil": {"block": "basepower:crude_oil", "enabled": False}},
                  "fluid_deposits": {"basepower:default": {"block": "basepower:crude_oil", "enabled": True,
                      "dimensions": {"minecraft:overworld": {"frequency": 1}}}}}
        self.assertEqual("FAIL", target.contract(source, actual, {"basepower:crude_oil"})["status"])
        actual["fluid_deposits"]["basepower:default"]["enabled"] = False
        self.assertEqual("PASS", target.contract(source, actual, {"basepower:crude_oil"})["status"])

    def test_unknown_primary_deep_and_weighted_outputs_cannot_pass_even_disabled(self):
        for field in ("block", "deep_output", "outputs"):
            actual = {"ores": {"test:ore": {"block": "baseelectric:li_ore", "enabled": False}}}
            row = actual["ores"]["test:ore"]
            row[field] = [{"block": "electricadvantage:li_ore", "weight": 1}] if field == "outputs" else "electricadvantage:li_ore"
            self.assertEqual("FAIL", target.contract({}, actual, {"baseelectric:li_ore"})["status"])


if __name__ == "__main__":
    unittest.main()
