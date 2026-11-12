import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native2612_legacy_alternatives as current
import native2612_retained_worldgen as retained
import native2612_worldgen as old


class VisibleAlternativeContractTest(unittest.TestCase):
    def fixture(self, root):
        expected = dict(ores={}, fluid_deposits={})
        files = []
        for old_owner, owner, blocks in (("poweradvantage", "basepower", ["crude_oil"]),
                                        ("electricadvantage", "baseelectric", ["li_ore", "sulfur_ore"])):
            document = dict(dimensions=[dict(dimension="+", ores=[dict(blockID=old_owner + ":" + block,
                minHeight=4, maxHeight=32, frequency=0.375, size=8, variation=2, biomes=[]) for block in blocks])])
            source = root / (old_owner + ".json")
            source.write_text(json.dumps(document), encoding="utf-8")
            files.append((source, Path("config/orespawn") / source.name))
            expected["ores"].update(retained.os1_profile(document, owner, old_owner)["ores"])
        result = copy.deepcopy(expected)
        result["schema_version"] = 7
        for target in current.contract_spec()["replacesOnly"]:
            dimension = dict(enabled=True, min_y=1, max_y=31, frequency=0.125,
                             host_blocks=["minecraft:stone"], host_tags=["forge:stone"],
                             min_quantity=6, max_quantity=9, pattern="default", placement_channel="orespawn:standard",
                             min_radius=5, max_radius=12, min_vertical_radius=2, max_vertical_radius=5)
            result[target["section"]][target["entry"]] = dict(block=target["block"], enabled=False,
                retrogen=False, dimensions={"minecraft:overworld": dimension})
        spec = dict(configFixtures=files)
        assertions = [dict(id=row["id"], section=row["section"], entry=row["entry"],
                           **current.contract_spec()["replacement"]) for row in current.contract_spec()["replacesOnly"]]
        return result, spec, assertions

    def test_only_three_explicit_absence_assertions_change_and_history_stays_unchanged(self):
        before = old.campaign_spec("legacy-import")
        changed = current.campaign_spec("legacy-import")
        targets = {row["id"] for row in current.contract_spec()["replacesOnly"]}
        expected = copy.deepcopy(before)
        for phase in ("first", "restart"):
            for row in expected[phase + "ProfileAssertions"]:
                if row["id"] in targets:
                    row.update(current.contract_spec()["replacement"])
        self.assertEqual(expected, changed)
        self.assertEqual(before, old.campaign_spec("legacy-import"))
        self.assertIs(current.worldgen.validate_restart, old.validate_restart)
        with self.assertRaises(AssertionError): current.campaign_spec("advantage")

    def test_complete_disabled_alternatives_and_all_raw_os1_settings_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            document, spec, assertions = self.fixture(Path(directory))
            with patch.object(current, "campaign_spec", return_value=spec):
                rows = current.validate_profile(document, assertions)
            self.assertEqual("PASS", rows[-1]["status"])

    def test_enabled_missing_or_incomplete_alternatives_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            original, spec, assertions = self.fixture(Path(directory))
            for mutation in ("enabled", "missing", "empty", "channel", "host", "geometry"):
                document = copy.deepcopy(original)
                rule = document["ores"]["baseelectric:ore/lithium"]
                if mutation == "enabled": rule["enabled"] = True
                elif mutation == "missing": del document["ores"]["baseelectric:ore/lithium"]
                elif mutation == "empty": rule["dimensions"] = {}
                else:
                    dimension = rule["dimensions"]["minecraft:overworld"]
                    dimension.pop({"channel": "placement_channel", "host": "host_blocks", "geometry": "max_y"}[mutation])
                with self.subTest(mutation=mutation), patch.object(current, "campaign_spec", return_value=spec):
                    with self.assertRaises(AssertionError): current.validate_profile(document, assertions)

    def test_changed_legacy_rates_or_a_new_active_duplicate_cannot_hide_behind_disabled_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            original, spec, assertions = self.fixture(Path(directory))
            for mutation in ("rate", "duplicate", "disabled", "output"):
                document = copy.deepcopy(original)
                key = "baseelectric:legacy/baseelectric_li_ore_0"
                rule = document["ores"][key]
                if mutation == "rate": rule["dimension_selectors"]["orespawn:all_except_nether_end"]["frequency"] = 99
                elif mutation == "duplicate": document["ores"]["pack:unapproved_lithium"] = copy.deepcopy(rule)
                elif mutation == "disabled": rule["enabled"] = False
                else: rule["block"] = "baseelectric:unknown_block"
                with self.subTest(mutation=mutation), patch.object(current, "campaign_spec", return_value=spec):
                    with self.assertRaises(AssertionError): current.validate_profile(document, assertions)


if __name__ == "__main__":
    unittest.main()
