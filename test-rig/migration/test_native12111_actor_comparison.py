import copy
import unittest

import nbtlib

import native12111_actor_comparison as diagnosis


class NativeActorComparisonTest(unittest.TestCase):
    def fixture(self, motion=90.5, timestamp=101):
        tag = nbtlib.Compound({"id": nbtlib.String("minecraft:cow"),
                              "UUID": nbtlib.IntArray([0, 0, 0, 1]),
                              "NoAI": nbtlib.Byte(1), "NoGravity": nbtlib.Byte(1),
                              "Health": nbtlib.Float(995.2774),
                              "Motion": nbtlib.List[nbtlib.Double]([motion, 0.383453342391226, 0]),
                              "HurtByTimestamp": nbtlib.Int(timestamp)})
        after = copy.deepcopy(tag)
        after["Motion"][0] = nbtlib.Double(0)
        after["HurtByTimestamp"] = nbtlib.Int(0)
        return tag, after

    def compare(self, before, after, case_id="steam/extra-explosive-1"):
        def case(tag):
            return {"id": case_id, "positions": [], "entities": [
                {"uuid": "00000000-0000-0000-0000-000000000001", "nbt": tag.snbt()}]}
        fixture = {"cases": [case(before)]}
        inspected = {"cases": [case(after)], "held": True, "constructionCallsThisProcess": 0}
        original = copy.deepcopy((fixture, inspected))
        result = diagnosis.diagnose(fixture, inspected)
        self.assertEqual(original, (fixture, inspected))
        return result

    def test_exact_known_deltas_do_not_upgrade_the_frozen_failure(self):
        result = self.compare(*self.fixture())
        self.assertTrue(result["diagnosticOnly"])
        self.assertEqual("FAIL", result["frozenRestartResult"]["status"])
        self.assertEqual({"$.Motion[0]", "$.HurtByTimestamp"}, {r["path"] for r in result["actorDifferences"]})
        self.assertEqual(0, result["unexplainedActorDifferences"])

    def test_velocity_boundary_and_in_range_damage_are_not_canonicalized(self):
        for velocity in (10.0, -10.0, 0.5):
            with self.subTest(velocity=velocity):
                result = self.compare(*self.fixture(velocity, 0))
                self.assertEqual(1, result["unexplainedActorDifferences"])

    def test_exact_negative_outlier_is_explained(self):
        self.assertEqual(0, self.compare(*self.fixture(-90.6844709742601, 0))["unexplainedActorDifferences"])

    def test_reversing_the_native_load_transition_is_not_explained(self):
        before, after = self.fixture()
        self.assertEqual(2, self.compare(after, before)["unexplainedActorDifferences"])

    def test_health_damage_and_unknown_keys_remain_failures(self):
        for change in ("health", "add", "remove"):
            before, after = self.fixture()
            if change == "health":
                after["Health"] = nbtlib.Float(1)
            elif change == "add":
                after["unknown"] = nbtlib.Int(7)
            else:
                del after["NoGravity"]
            result = self.compare(before, after)
            self.assertGreater(result["unexplainedActorDifferences"], 0)
            self.assertEqual("FAIL", result["frozenRestartResult"]["status"])

    def test_known_shape_in_another_case_is_not_explained(self):
        result = self.compare(*self.fixture(), case_id="steam/another-case")
        self.assertEqual(2, result["unexplainedActorDifferences"])

    def test_relative_timestamp_or_attacker_reference_is_never_ignored(self):
        for name in ("last_hurt_by_mob", "ticks_since_last_hurt_by_mob"):
            before, after = self.fixture()
            before[name] = nbtlib.Int(7)
            after[name] = nbtlib.Int(7)
            self.assertEqual(1, self.compare(before, after)["unexplainedActorDifferences"])

    def test_motion_list_type_change_and_nonzero_clamped_result_fail(self):
        before, after = self.fixture(timestamp=0)
        after["Motion"][0] = nbtlib.Double(1)
        self.assertEqual(1, self.compare(before, after)["unexplainedActorDifferences"])
        before, after = self.fixture(timestamp=0)
        after["Motion"] = nbtlib.List[nbtlib.Float]([0, 0, 0])
        self.assertEqual(1, self.compare(before, after)["unexplainedActorDifferences"])

    def test_saved_uuid_cannot_disagree_with_actor_metadata(self):
        before, after = self.fixture()
        after["UUID"] = nbtlib.IntArray([0, 0, 0, 2])
        with self.assertRaisesRegex(AssertionError, "metadata and saved UUID"):
            self.compare(before, after)

    def test_duplicate_actor_uuid_is_rejected(self):
        before, _ = self.fixture()
        actor = {"uuid": "00000000-0000-0000-0000-000000000001", "nbt": before.snbt()}
        with self.assertRaisesRegex(AssertionError, "Duplicate"):
            diagnosis.actors({"entities": [actor, copy.deepcopy(actor)]})


if __name__ == "__main__":
    unittest.main()
