import copy
import unittest

import nbtlib

import native117_machine_operations as frozen
import native12111_operations as native


class NativeExpectedActorLoadTest(unittest.TestCase):
    def documents(self, case_id="steam/extra-explosive-1", motion=(90.5, 0.383453342391226, 0), timestamp=101):
        before = nbtlib.Compound({"id": nbtlib.String("minecraft:cow"),
                                 "UUID": nbtlib.IntArray([0, 0, 0, 1]),
                                 "NoAI": nbtlib.Byte(1), "NoGravity": nbtlib.Byte(1),
                                 "Health": nbtlib.Float(995.2774),
                                 "Motion": nbtlib.List[nbtlib.Double](motion),
                                 "HurtByTimestamp": nbtlib.Int(timestamp)})
        after = copy.deepcopy(before)
        after["Motion"] = nbtlib.List[nbtlib.Double](0.0 if abs(value) > 10 else value for value in motion)
        after["HurtByTimestamp"] = nbtlib.Int(0)

        def case(tag):
            return {"id": case_id, "positions": [], "entities": [
                {"uuid": "00000000-0000-0000-0000-000000000001", "nbt": tag.snbt()}]}

        return {"cases": [case(before)]}, {"cases": [case(after)], "held": True, "constructionCallsThisProcess": 0}

    def change(self, document, callback):
        actor = document["cases"][0]["entities"][0]
        tag = nbtlib.parse_nbt(actor["nbt"])
        callback(tag)
        actor["nbt"] = tag.snbt()

    def compare(self, fixture, inspected):
        original = copy.deepcopy((fixture, inspected))
        try:
            return native.validate_restart(fixture, inspected)
        finally:
            self.assertEqual(original, (fixture, inspected), "Neither input may be rewritten")

    def test_only_native_adapter_accepts_the_proven_directional_transitions(self):
        for case_id in ("steam/extra-explosive-0", "steam/extra-explosive-1"):
            before, after = self.documents(case_id)
            self.assertEqual("FAIL", frozen.validate_restart(before, after)["status"])
            result = self.compare(before, after)
            self.assertEqual("PASS", result["status"])
            self.assertEqual({"Motion[0]", "HurtByTimestamp"}, {
                row["field"] for change in result["expectedBeforeLoadTransformations"] for row in change["fields"]})
            self.assertEqual("FAIL", frozen.validate_restart(before, after)["status"])

    def test_each_outlier_component_is_projected_and_boundary_components_are_exact(self):
        for motion in ((10, -10, 0.5), (-90.5, 90.5, 11), (-10.001, 10.001, -11)):
            before, after = self.documents(motion=motion, timestamp=0)
            result = self.compare(before, after)
            self.assertEqual("PASS", result["status"])
            fields = [field for row in result["expectedBeforeLoadTransformations"] for field in row["fields"]]
            self.assertEqual(sum(abs(value) > 10 for value in motion), len(fields))

    def test_in_range_velocity_corruption_fails(self):
        for value in (10, -10, 0.5):
            before, after = self.documents(motion=(value, 0, 0), timestamp=0)
            self.change(after, lambda tag: tag["Motion"].__setitem__(0, nbtlib.Double(0)))
            self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_observed_motion_is_not_projected_even_when_raw_documents_match(self):
        before, after = self.documents(timestamp=0)
        after["cases"] = copy.deepcopy(before["cases"])
        self.assertEqual("PASS", frozen.validate_restart(before, after)["status"])
        self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_observed_timestamp_is_not_projected(self):
        before, after = self.documents(motion=(0, 0, 0))
        after["cases"] = copy.deepcopy(before["cases"])
        self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_unexpected_nonzero_clamped_velocity_and_reversed_transition_fail(self):
        before, after = self.documents(timestamp=0)
        self.change(after, lambda tag: tag["Motion"].__setitem__(0, nbtlib.Double(1)))
        self.assertEqual("FAIL", self.compare(before, after)["status"])
        before, after = self.documents()
        self.assertEqual("FAIL", self.compare({"cases": after["cases"]},
                                             dict(after, cases=before["cases"]))["status"])

    def test_other_cases_and_entity_kinds_have_no_exception(self):
        before, after = self.documents(case_id="steam/extra-recoil-1")
        self.assertEqual("FAIL", self.compare(before, after)["status"])
        for field, value in (("id", nbtlib.String("minecraft:pig")), ("NoAI", nbtlib.Byte(0)),
                             ("NoGravity", nbtlib.Byte(0))):
            before, after = self.documents()
            for document in (before, after):
                self.change(document, lambda tag: tag.__setitem__(field, value))
            self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_relative_hurt_keys_and_attacker_references_are_not_projected(self):
        for key in ("last_hurt_by_mob", "ticks_since_last_hurt_by_mob"):
            before, after = self.documents()
            for document in (before, after):
                self.change(document, lambda tag: tag.__setitem__(key, nbtlib.Int(7)))
            self.assertEqual("FAIL", self.compare(before, after)["status"])
        before, after = self.documents()
        self.change(after, lambda tag: tag.__setitem__("ticks_since_last_hurt_by_mob", nbtlib.Int(0)))
        self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_health_unknown_keys_position_and_nbt_type_changes_fail(self):
        changes = (lambda tag: tag.__setitem__("Health", nbtlib.Float(1)),
                   lambda tag: tag.__setitem__("unknown", nbtlib.Int(7)),
                   lambda tag: tag.__delitem__("Health"),
                   lambda tag: tag.__setitem__("Health", nbtlib.Double(995.2774)),
                   lambda tag: tag.__setitem__("Pos", nbtlib.List[nbtlib.Double]([1, 2, 3])),
                   lambda tag: tag.__setitem__("Motion", nbtlib.List[nbtlib.Float]([0, 0, 0])))
        for change in changes:
            before, after = self.documents()
            self.change(after, change)
            self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_unknown_nested_payload_is_preserved_and_its_list_order_stays_exact(self):
        before, after = self.documents()
        payload = nbtlib.Compound({"opaque": nbtlib.IntArray([7, 8]),
                                   "ordered": nbtlib.List[nbtlib.Int]([3, 2, 1])})
        for document in (before, after):
            self.change(document, lambda tag: tag.__setitem__("private_payload", copy.deepcopy(payload)))
        self.assertEqual("PASS", self.compare(before, after)["status"])
        self.change(after, lambda tag: tag["private_payload"].__setitem__(
            "ordered", nbtlib.List[nbtlib.Int]([1, 2, 3])))
        self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_exception_does_not_expand_to_nested_passenger_payloads(self):
        before, after = self.documents()
        passenger = nbtlib.Compound({"id": nbtlib.String("minecraft:pig"),
                                    "Motion": nbtlib.List[nbtlib.Double]([90, 0, 0])})
        for document in (before, after):
            self.change(document, lambda tag: tag.__setitem__(
                "Passengers", nbtlib.List[nbtlib.Compound]([copy.deepcopy(passenger)])))
        self.change(after, lambda tag: tag["Passengers"][0]["Motion"].__setitem__(0, nbtlib.Double(0)))
        self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_malformed_expected_motion_and_timestamp_fail_closed(self):
        changes = (lambda tag: tag.__setitem__("Motion", nbtlib.List[nbtlib.Float]([90, 0, 0])),
                   lambda tag: tag.__setitem__("Motion", nbtlib.List[nbtlib.Double]([90, 0])),
                   lambda tag: tag.__delitem__("Motion"),
                   lambda tag: tag.__setitem__("HurtByTimestamp", nbtlib.Short(101)),
                   lambda tag: tag.__setitem__("HurtByTimestamp", nbtlib.Int(-1)))
        for change in changes:
            before, after = self.documents()
            self.change(before, change)
            with self.assertRaisesRegex(AssertionError, "Malformed native explosive actor"):
                self.compare(before, after)

    def test_actor_uuid_and_set_changes_are_rejected(self):
        before, after = self.documents()
        self.change(after, lambda tag: tag.__setitem__("UUID", nbtlib.IntArray([0, 0, 0, 2])))
        with self.assertRaisesRegex(AssertionError, "metadata and saved UUID"):
            self.compare(before, after)
        before, after = self.documents()
        after["cases"][0]["entities"].append(copy.deepcopy(after["cases"][0]["entities"][0]))
        with self.assertRaisesRegex(AssertionError, "Duplicate"):
            self.compare(before, after)
        before, after = self.documents()
        after["cases"][0]["entities"] = []
        self.assertEqual("FAIL", self.compare(before, after)["status"])

    def test_frozen_restart_and_block_position_gates_remain_in_force(self):
        for key, value in (("held", False), ("constructionCallsThisProcess", 1)):
            before, after = self.documents()
            after[key] = value
            with self.assertRaisesRegex(AssertionError, "Missing/rebuilt/unfrozen"):
                self.compare(before, after)
        before, after = self.documents()
        after["cases"][0]["positions"] = [{"x": 1, "y": 2, "z": 3, "block": "minecraft:stone", "state": "stone"}]
        self.assertEqual("FAIL", self.compare(before, after)["status"])


if __name__ == "__main__":
    unittest.main()
