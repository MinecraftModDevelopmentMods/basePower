"""Pure rejection controls, not Minecraft migration evidence."""
import base64
import copy
import gzip
import io
import unittest

import nbtlib

import native110_rebrand_connected as rebrand


def snapshot(namespace):
    tag = nbtlib.parse_nbt('{id:"' + namespace + '.steam_furnace",Energy:[I;1065353216],'
        'Items:[{Slot:0b,id:"' + ("electricadvantage" if namespace == "steamadvantage" else "baseelectric")
        + ':lithium_battery",Count:1b,tag:{energy:123.5f,CustomName:"electricadvantage:keep"}}]}')
    data = io.BytesIO()
    nbtlib.File(tag).write(data)
    return dict(held=True, buildCallsThisProcess=0, advancedTicks=0, requestedTicks=0,
        snapshot=[dict(id="furnace", station="C-STEAM", kind="furnace", name="steamadvantage:steam_furnace",
            actualName=namespace + ":steam_furnace", x=80, y=80, z=80, properties={"facing": "north"},
            nbtBase64=base64.b64encode(gzip.compress(data.getvalue())).decode("ascii"))])


def change_nbt(document, change):
    row = document["snapshot"][0]
    tag = rebrand.binary_nbt(row)
    change(tag)
    row["nbtBase64"] = rebrand.encode_nbt(tag)


class RebrandPreservationTest(unittest.TestCase):
    def test_exact_namespace_only_change_passes_without_mutating_documents(self):
        old, new = snapshot("steamadvantage"), snapshot("basesteam")
        untouched = copy.deepcopy((old, new))
        self.assertEqual("PASS", rebrand.preservation(old, new)["status"])
        self.assertEqual(untouched, (old, new))
        self.assertEqual("PASS", rebrand.preservation(new, new)["status"])

    def test_removed_item_wrong_facing_and_lost_energy_fail(self):
        for mutation in ("item", "facing", "energy", "type", "extra", "custom", "old-id", "missing-tile"):
            old, new = snapshot("steamadvantage"), snapshot("basesteam")
            if mutation == "item": change_nbt(new, lambda tag: tag["Items"].clear())
            elif mutation == "facing": new["snapshot"][0]["properties"]["facing"] = "south"
            elif mutation == "energy": change_nbt(new, lambda tag: tag.__setitem__("Energy", nbtlib.IntArray([0])))
            elif mutation == "type": change_nbt(new, lambda tag: tag["Items"][0].__setitem__("Count", nbtlib.Int(1)))
            elif mutation == "extra": change_nbt(new, lambda tag: tag.__setitem__("HiddenEnergy", nbtlib.Int(1)))
            elif mutation == "custom": change_nbt(new, lambda tag: tag["Items"][0]["tag"].__setitem__("CustomName", nbtlib.String("baseelectric:keep")))
            elif mutation == "old-id": new["snapshot"][0]["actualName"] = "steamadvantage:steam_furnace"
            else: new["snapshot"][0].pop("nbtBase64")
            with self.subTest(mutation=mutation):
                self.assertEqual("FAIL", rebrand.preservation(old, new)["status"])

    def test_unticked_means_frozen_zero_builds_and_zero_ticks(self):
        for field, value in (("held", False), ("advancedTicks", 1), ("requestedTicks", 1), ("buildCallsThisProcess", 1)):
            new = snapshot("basesteam")
            new[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                rebrand.require_passive(new)

    def test_missing_extra_duplicate_and_moved_witnesses_fail(self):
        for mutation in ("missing", "extra", "duplicate", "moved"):
            old, new = snapshot("steamadvantage"), snapshot("basesteam")
            if mutation == "missing": new["snapshot"].clear()
            elif mutation == "moved": new["snapshot"][0]["x"] += 1
            else:
                row = copy.deepcopy(new["snapshot"][0])
                if mutation == "extra": row["id"] = "extra"
                new["snapshot"].append(row)
            with self.subTest(mutation=mutation):
                if mutation == "duplicate":
                    with self.assertRaises(AssertionError): rebrand.preservation(old, new)
                else: self.assertEqual("FAIL", rebrand.preservation(old, new)["status"])

    def test_only_exact_namespace_boundaries_are_translated(self):
        self.assertEqual("basepower:still", rebrand.target_id("poweradvantage:still"))
        for value in ("other:poweradvantage", "poweradvantage_extra:still", "custom poweradvantage:still"):
            self.assertEqual(value, rebrand.target_id(value))

    def test_electric_drill_does_not_invent_a_saved_facing_field(self):
        old, new = snapshot("steamadvantage"), snapshot("basesteam")
        for document, namespace in ((old, "electricadvantage"), (new, "baseelectric")):
            document["snapshot"][0].update(name="electricadvantage:electric_drill",
                actualName=namespace + ":electric_drill", kind="drill")
            change_nbt(document, lambda tag: tag.__setitem__("id", nbtlib.String(namespace + ".electric_drill")))
            change_nbt(document, lambda tag: tag.__setitem__("progress", nbtlib.Short(209)))
        self.assertEqual("PASS", rebrand.preservation(old, new)["status"])
        change_nbt(new, lambda tag: tag.__setitem__("Facing", nbtlib.Byte(2)))
        self.assertEqual("FAIL", rebrand.preservation(old, new)["status"])

    def test_only_steam_legacy_facing_is_derived_from_the_actual_block_state(self):
        old, new = snapshot("steamadvantage"), snapshot("basesteam")
        for document, namespace in ((old, "steamadvantage"), (new, "basesteam")):
            document["snapshot"][0].update(name="steamadvantage:steam_drill",
                actualName=namespace + ":steam_drill", kind="drill")
            change_nbt(document, lambda tag: tag.__setitem__("id", nbtlib.String(namespace + ".steam_drill")))
        change_nbt(new, lambda tag: tag.__setitem__("Facing", nbtlib.Byte(2)))
        self.assertEqual("PASS", rebrand.preservation(old, new)["status"])
        change_nbt(new, lambda tag: tag.__setitem__("Facing", nbtlib.Byte(3)))
        self.assertEqual("FAIL", rebrand.preservation(old, new)["status"])


if __name__ == "__main__":
    unittest.main()
