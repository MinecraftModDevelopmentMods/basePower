import copy
import unittest

import nbtlib

from native117_machine_saved import compare_chunk, state


class SavedMachineTests(unittest.TestCase):
    def fixture(self):
        tile = nbtlib.parse_nbt('{id:"steamadvantage:steam_pump",x:1,y:80,z:2,NextPump:19b}')
        section = nbtlib.Compound(Y=nbtlib.Byte(5), Palette=nbtlib.List[nbtlib.Compound]([
            nbtlib.Compound(Name=nbtlib.String('steamadvantage:steam_pump'),
                Properties=nbtlib.Compound(facing=nbtlib.String('north')))]))
        data = nbtlib.Compound(Sections=nbtlib.List[nbtlib.Compound]([section]),
            TileEntities=nbtlib.List[nbtlib.Compound]([tile]))
        row = dict(x=1, y=80, z=2, block='steamadvantage:steam_pump',
            state='Block{steamadvantage:steam_pump}[facing=north]', nbt=tile.snbt())
        tile['keepPacked'] = nbtlib.Byte(0)
        return [row], data

    def test_exact_native_saved_state_and_typed_nbt_pass(self):
        rows, data = self.fixture()
        self.assertEqual([], compare_chunk(rows, data))

    def test_stale_timer_is_not_accepted_as_a_frozen_save(self):
        rows, data = self.fixture()
        data['TileEntities'][0]['NextPump'] = nbtlib.Byte(7)
        self.assertEqual(1, len(compare_chunk(rows, data)))

    def test_same_value_with_wrong_nbt_type_fails(self):
        rows, data = self.fixture()
        data['TileEntities'][0]['NextPump'] = nbtlib.Int(19)
        self.assertEqual(1, len(compare_chunk(rows, data)))

    def test_shortest_snbt_float_and_binary32_save_agree_without_rounding_other_types(self):
        from native117_machine_operations import typed_nbt
        import struct
        actual = struct.unpack('>f', struct.pack('>f', 1346.7579))[0]
        self.assertEqual(typed_nbt(nbtlib.Float(1346.7579)), typed_nbt(nbtlib.Float(actual)))
        self.assertNotEqual(typed_nbt(nbtlib.Double(1346.7579)), typed_nbt(nbtlib.Double(actual)))
        self.assertNotEqual(typed_nbt(nbtlib.Float(actual)), typed_nbt(nbtlib.Float(actual + 0.001)))

    def test_missing_tile_or_changed_facing_fails(self):
        rows, data = self.fixture()
        changed = copy.deepcopy(data)
        changed['TileEntities'].clear()
        self.assertTrue(compare_chunk(rows, changed))
        data['Sections'][0]['Palette'][0]['Properties']['facing'] = nbtlib.String('south')
        self.assertTrue(compare_chunk(rows, data))

    def test_inconsistent_or_duplicate_state_keys_rejected(self):
        with self.assertRaises(ValueError):
            state(dict(block='steamadvantage:steam_pump', state='Block{minecraft:stone}'))
        with self.assertRaises(ValueError):
            state(dict(block='minecraft:stone', state='Block{minecraft:stone}[facing=north,facing=south]'))

    def test_packed_or_wrongly_typed_storage_marker_is_not_ignored(self):
        rows, data = self.fixture()
        for value in (nbtlib.Byte(1), nbtlib.Int(0)):
            data['TileEntities'][0]['keepPacked'] = value
            with self.assertRaisesRegex(ValueError, 'unpacked-tile'):
                compare_chunk(rows, data)


if __name__ == '__main__':
    unittest.main()
