import unittest

from native117_commands import native


class NativeCommandTests(unittest.TestCase):
    def test_coordinates_slots_counts_and_emptying_are_retained(self):
        for item, count in (('minecraft:iron_ore', 16), ('minecraft:air', 1)):
            self.assertEqual('item replace block -14 8 230 container.26 with ' + item + ' ' + str(count),
                native('replaceitem block -14 8 230 container.26 ' + item + ' ' + str(count)))

    def test_other_commands_are_unchanged(self):
        for command in ('save-all flush', 'setblock 1 2 3 minecraft:air', 'advlifecycle snapshot tick'):
            self.assertEqual(command, native(command))

    def test_unknown_legacy_grammar_fails_rather_than_writing_nbt_or_clearing_a_different_inventory(self):
        for command in ('replaceitem entity @e container.1 minecraft:stone 1',
            'replaceitem block 1 2 3 container.1 minecraft:stone 0',
            'replaceitem block 1 2 3 container.1 minecraft:stone 1 extra'):
            with self.assertRaises(ValueError): native(command)


if __name__ == '__main__':
    unittest.main()
