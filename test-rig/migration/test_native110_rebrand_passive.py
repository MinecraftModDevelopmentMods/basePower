"""Synthetic oracle rejection tests, never labelled authentic fixture evidence."""
import copy
import unittest
from pathlib import Path
from unittest.mock import patch

import nbtlib

from native110_rebrand_connected import encode_nbt
import native110_rebrand_passive as passive


def rows(namespace="steamadvantage", enchantment_id=11):
    result = []
    for index in range(355):
        tag = nbtlib.parse_nbt('{id:"' + namespace + '.steam_furnace",Energy:[I;1065353216],Items:[{Slot:0b,id:"'
            + namespace + ':musket",Count:1b,Damage:5s,tag:{MigrationWitness:"steamadvantage:musket",'
            + 'ench:[{id:' + str(enchantment_id) + 's,lvl:1s}],Nested:{Bytes:[B;1b,-2b],Ints:[I;3,-7]}}}]}')
        result.append(dict(x=index, y=80, z=0, name="steamadvantage:steam_furnace", metadata=2,
            actualName=namespace + ":steam_furnace", properties={"facing": "north"}, nbtBase64=encode_nbt(nbtlib.File(tag))))
    return result


class Passive355Test(unittest.TestCase):
    def compare(self, old, new, target_registry=None):
        return passive.compare_rows(old, new, {11: "steamadvantage:recoil"}, target_registry or {47: "steamadvantage:recoil"})

    def test_exact_namespace_translation_and_enchantment_semantics_pass_without_mutation(self):
        old, new = rows(), rows("basesteam", 47)
        untouched = copy.deepcopy((old, new))
        self.assertEqual("PASS", self.compare(old, new)["status"])
        self.assertEqual(untouched, (old, new))

    def test_missing_extra_duplicate_moved_meta_facing_and_wrong_namespace_fail(self):
        for mutation in ("missing", "extra", "duplicate", "moved", "meta", "facing", "old-id"):
            old, new = rows(), rows("basesteam", 47)
            if mutation == "missing": new.pop()
            elif mutation == "extra": new.append(copy.deepcopy(new[-1]))
            elif mutation == "duplicate": new[-1] = copy.deepcopy(new[0])
            elif mutation == "moved": new[-1]["x"] += 1000
            elif mutation == "meta": new[-1]["metadata"] = 3
            elif mutation == "facing": new[-1]["properties"]["facing"] = "south"
            else: new[-1]["actualName"] = "steamadvantage:steam_furnace"
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): self.compare(old, new)

    def test_removed_item_lost_energy_changed_type_enchantment_and_extra_key_fail(self):
        for mutation in ("item", "energy", "type", "level", "enchantment", "extra", "custom"):
            old, new = rows(), rows("basesteam", 47)
            tag = passive.binary_nbt(new[-1])
            if mutation == "item": tag["Items"].clear()
            elif mutation == "energy": tag["Energy"] = nbtlib.IntArray([0])
            elif mutation == "type": tag["Items"][0]["Count"] = nbtlib.Int(1)
            elif mutation == "level": tag["Items"][0]["tag"]["ench"][0]["lvl"] = nbtlib.Short(2)
            elif mutation == "enchantment": tag["Items"][0]["tag"]["ench"][0]["id"] = nbtlib.Short(48)
            elif mutation == "extra": tag["HiddenEnergy"] = nbtlib.Int(1)
            else: tag["Items"][0]["tag"]["MigrationWitness"] = nbtlib.String("basesteam:musket")
            new[-1]["nbtBase64"] = encode_nbt(tag)
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): self.compare(old, new)

    def test_equal_numeric_ids_do_not_hide_an_incorrect_registry_semantic(self):
        with self.assertRaises(AssertionError):
            self.compare(rows(), rows("basesteam", 11), {11: "steamadvantage:powderless"})

    def test_retained_canonical_enchantments_must_not_be_renamed_in_the_target(self):
        with self.assertRaises(AssertionError):
            self.compare(rows(), rows("basesteam", 47), {47: "basesteam:recoil"})

    def test_only_steam_derives_exact_typed_facing_from_original_metadata(self):
        old, new = rows(), rows("basesteam", 47)
        for meta, facing in enumerate(("down", "up", "north", "south", "west", "east")):
            for collection, namespace in ((old, "steamadvantage"), (new, "basesteam")):
                row = collection[meta]
                row.update(name="steamadvantage:steam_drill", actualName=namespace + ":steam_drill", metadata=meta,
                           properties={"facing": facing})
                tag = passive.binary_nbt(row)
                tag["id"] = nbtlib.String(namespace + ".steam_drill")
                if collection is new: tag["Facing"] = nbtlib.Byte(meta)
                row["nbtBase64"] = encode_nbt(tag)
        self.assertEqual("PASS", self.compare(old, new)["status"])
        for mutation in ("wrong-facing", "wrong-type", "foreign-key", "metadata-disagreement"):
            damaged = copy.deepcopy(new)
            source = copy.deepcopy(old)
            tag = passive.binary_nbt(damaged[2])
            if mutation == "wrong-facing": tag["Facing"] = nbtlib.Byte(3)
            elif mutation == "wrong-type": tag["Facing"] = nbtlib.Int(2)
            elif mutation == "foreign-key": tag["ForeignState"] = nbtlib.Int(1)
            else: source[2]["properties"]["facing"] = "south"
            damaged[2]["nbtBase64"] = encode_nbt(tag)
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): self.compare(source, damaged)

    def test_electric_missing_facing_stays_absent_and_extra_target_facing_fails(self):
        old, new = rows(), rows("basesteam", 47)
        for collection, namespace in ((old, "electricadvantage"), (new, "baseelectric")):
            row = collection[0]
            row.update(name="electricadvantage:electric_drill", actualName=namespace + ":electric_drill")
            tag = passive.binary_nbt(row)
            tag["id"] = nbtlib.String(namespace + ".electric_drill")
            row["nbtBase64"] = encode_nbt(tag)
        self.assertEqual("PASS", self.compare(old, new)["status"])
        tag = passive.binary_nbt(new[0]); tag["Facing"] = nbtlib.Byte(2)
        new[0]["nbtBase64"] = encode_nbt(tag)
        with self.assertRaises(AssertionError): self.compare(old, new)

    def test_observer_has_no_state_repair_or_construction_path(self):
        java = (Path(passive.__file__).parents[1] / "src/main/java/zone/moddev/mc/advantageworks/NativePassiveMigrationProbe.java").read_text()
        for forbidden in ("readFromNBT", "setBlockState", "setInventorySlotContents", "markDirty", "build(", "addEnchantment"):
            self.assertNotIn(forbidden, java)
        self.assertIn("tile.writeToNBT(new NBTTagCompound())", java)

    def test_synthetic_station_cannot_be_accepted_as_authentic_player_evidence(self):
        with self.assertRaises(AssertionError): passive.validate_nested({"scope": passive.AUTHENTIC_SCOPE, "cases": []})

    def test_synthetic_xp_seed_remains_exact_and_typed_not_ignored(self):
        tag = nbtlib.File(nbtlib.parse_nbt('{XpSeed:305441741,Inventory:[],EnderItems:[]}'))
        encoded = encode_nbt(tag)
        document = dict(scope=passive.SYNTHETIC_SCOPE, status="PASS", worldInsertions=0, playerFileWrites=0,
            cases=[dict(id=case, status="PASS", nativeSemantics=True, inputNbtBase64=encoded,
                        expectedNbtBase64=encoded, actualNbtBase64=encoded) for case in sorted(passive.SYNTHETIC_CASES)])
        self.assertEqual("PASS", passive.validate_nested(document)["status"])
        for value in (nbtlib.Int(305441742), nbtlib.Long(305441741)):
            bad = copy.deepcopy(document)
            actual = nbtlib.File(nbtlib.parse_nbt('{XpSeed:305441741,Inventory:[],EnderItems:[]}'))
            actual["XpSeed"] = value
            bad["cases"][0]["actualNbtBase64"] = encode_nbt(actual)
            with self.subTest(value=type(value).__name__), self.assertRaises(AssertionError): passive.validate_nested(bad)

    def test_only_the_exact_original_observer_is_allowed_and_legacy_commands_are_forbidden(self):
        expected = passive.SOURCE_JARS["AdvantageMigrationProbe-110-1.0.0.jar"]
        with patch.object(passive, "digest", return_value=expected):
            self.assertEqual(Path("fixture/mods/AdvantageMigrationProbe-110-1.0.0.jar"), passive.source_observer(Path("fixture")))
        with patch.object(passive, "digest", return_value="changed"), self.assertRaises(AssertionError):
            passive.source_observer(Path("fixture"))
        for command in ("advprobe build source", "advprobe snapshot source", "setblock 0 80 0 stone", "advconnected build source"):
            with self.subTest(command=command), self.assertRaises(AssertionError): passive.PassiveServer.send(None, command)


if __name__ == "__main__": unittest.main()
