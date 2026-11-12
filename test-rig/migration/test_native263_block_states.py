import copy
import hashlib
from pathlib import Path
import unittest
import nbtlib

from native263_block_states import block_state, decode_palette, palette_state
from native117_format import decode_palette as historical_decode


class Native263BlockStates(unittest.TestCase):
    def test_compact_default_and_full_compound_are_exact_projections(self):
        self.assertEqual(dict(Name="minecraft:stone", Properties={}), block_state("minecraft:stone"))
        source = dict(id="basepower:fluid_storage_tank", properties={"facing": "north", "active": "false"})
        before = copy.deepcopy(source)
        self.assertEqual(dict(Name=source["id"], Properties=source["properties"]), block_state(source))
        self.assertEqual(source, before)

    def test_historical_mixed_unknown_and_wrong_types_reject(self):
        for value in ({"Name": "minecraft:stone"}, {"id": "minecraft:stone", "Properties": {}},
                      {"id": "minecraft:stone", "Name": "minecraft:stone"},
                      {"id": "minecraft:stone", "properties": {"active": False}},
                      {"id": "minecraft:stone", "properties": []},
                      {"id": "minecraft:stone", "unknown": 1}, "stone", "minecraft:stone[active=false]", 1, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                block_state(value)

    def test_palette_padding_and_index_negatives_remain_strict(self):
        palette = ["minecraft:air", {"id": "minecraft:oak_log", "properties": {"axis": "x"}}]
        result = decode_palette(palette, [0x10] + [0] * 255)
        self.assertEqual(dict(Name="minecraft:air", Properties={}), result[0])
        self.assertEqual(dict(Name="minecraft:oak_log", Properties={"axis": "x"}), result[1])
        for entries, words in ((palette, []), (palette, [2] + [0] * 255), ([], [])):
            with self.subTest(entries=entries, words=len(words)), self.assertRaises(ValueError):
                decode_palette(entries, words)

    def test_historical_reader_is_unchanged_and_keeps_its_original_fields(self):
        source = [{"Name": "minecraft:stone", "Properties": {"unchanged": "true"}}]
        self.assertEqual(source * 4096, historical_decode(source, []))
        self.assertEqual([dict(Name="minecraft:stone", Properties={})] * 4096,
                         decode_palette(["minecraft:stone"], []))

    def test_native_list_storage_wrapper_is_unwrapped_once_not_normalized_recursively(self):
        palette = [{"": "minecraft:air"}, {"id": "minecraft:oak_log", "properties": {"axis": "x"}}]
        self.assertEqual("minecraft:air", decode_palette(palette, [0] * 256)[0]["Name"])
        for value in ({"": {"": "minecraft:air"}}, {"": "minecraft:air", "unknown": 1}, {"": 1}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                palette_state(value)

    def test_real_native_written_mixed_palette_fixture_keeps_both_states(self):
        path = Path(__file__).with_name("fixtures") / "native263-mixed-palette.dat"
        self.assertEqual("6B8446EECE93C2098710495E720F8594F405EF2400F2E5A2A94046CFA02CAE97",
                         hashlib.sha256(path.read_bytes()).hexdigest().upper())
        raw = nbtlib.load(path)
        self.assertEqual(5023, int(raw["DataVersion"]))
        values = raw["palette"].unpack()
        self.assertEqual({"": "minecraft:stone"}, values[0])
        actual = decode_palette(values, [0x10] + [0] * 255)
        self.assertEqual(dict(Name="minecraft:stone", Properties={}), actual[0])
        self.assertEqual(dict(Name="minecraft:oak_log", Properties={"axis": "x"}), actual[1])


if __name__ == "__main__":
    unittest.main()
